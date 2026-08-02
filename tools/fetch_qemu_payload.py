#!/usr/bin/env python3
"""Fetch the VM runtime payload (QEMU + PRoot) and repackage it for Android.

Why this exists
---------------
Modern Android (targetSdk >= 29) only allows an app to exec() binaries that
live in the APK's extracted native-library directory. That directory is
populated by the package manager from `lib/<abi>/*.so` entries, so every
executable and shared library we ship must:

  1. be named `lib<something>.so`, and
  2. have its DT_NEEDED / DT_SONAME entries rewritten to match the renamed
     dependencies (done with patchelf).

This script downloads prebuilt ARM64 binaries from the Termux apt repository,
resolves the full dependency closure, performs the rename + patchelf pass and
drops the results into:

    app/src/main/jniLibs/arm64-v8a/   (executables + shared libraries)
    app/src/main/assets/vm/           (QEMU firmware/keymaps, PRoot rootfs,
                                       payload manifest with sha256 sums)

Usage:  python3 tools/fetch_qemu_payload.py [--work-dir DIR]

The script is idempotent: downloads are cached in the work dir and outputs are
regenerated from scratch on every run.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import sys
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

TERMUX_REPO_URL = "https://packages.termux.dev/apt/termux-main"
TERMUX_INDEX_URL = f"{TERMUX_REPO_URL}/dists/stable/main/binary-aarch64/Packages"

ALPINE_MINIROOTFS_URL = (
    "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/"
    "alpine-minirootfs-3.22.2-aarch64.tar.gz"
)

# Termux installs under this prefix inside every .deb.
TERMUX_PREFIX = "data/data/com.termux/files"

# Packages whose binaries we actually run. virglrenderer-android is the host
# half of Mesa's virpipe protocol: it renders through Android EGL/GLES while a
# PRoot guest sends Gallium commands over a Unix socket.
ROOT_PACKAGES = [
    "qemu-system-aarch64-headless",
    "qemu-utils",
    "proot",
    "virglrenderer-android",
]

# Executables to expose in jniLibs, renamed to lib*.so.
EXECUTABLES = {
    "usr/bin/qemu-system-aarch64": "libqemu-system-aarch64.so",
    "usr/bin/qemu-img": "libqemu-img.so",
    "usr/bin/proot": "libproot.so",
    "usr/libexec/proot/loader": "libproot-loader.so",
    "usr/bin/virgl_test_server_android": "libvirgl-test-server-android.so",
}

# Termux's Android virgl build embeds the Termux data directory for its ANGLE
# fallback. The replacement is shorter, stable for the primary Android user,
# and points at files copied from our own signed APK. Padding preserves every
# ELF offset; no executable section is resized.
ANGLE_PATH_REPLACEMENTS = {
    b"/data/data/com.termux/files/usr/opt/angle-android/gl":
        b"/data/data/com.crunzex.linuxondex/files/vm/angle/gl",
    b"/data/data/com.termux/files/usr/opt/angle-android/vulkan\0":
        b"/data/data/com.crunzex.linuxondex/files/vm/angle/vulkan\0",
}

# virglrenderer 1.3 requests EGL_CONTEXT_MINOR_VERSION_KHR=2 for every GLES
# context. Android Studio's API 34/36 ARM64 renderer advertises GLES 3.0/3.1
# and aborts the server with EGL_BAD_CONFIG. In the pinned ARM64 build this
# unique instruction loads the minor-version attribute key. Replacing it with
# EGL_NONE terminates the list after the major version (3), letting EGL choose
# its supported 3.x context. Both byte sequences are verified before writing.
VIRGL_GLES_CONTEXT_PATCH = {
    bytes.fromhex("68 1f 86 52"): bytes.fromhex("08 07 86 52"),
}

# QEMU data files required for the aarch64 'virt' machine. Everything else in
# qemu-common's share dir (x86 BIOSes and friends) is dead weight.
QEMU_DATA_KEEP_PATTERNS = [
    rf"^{TERMUX_PREFIX}/usr/share/qemu/edk2-aarch64-code\.fd(\.gz)?$",
    rf"^{TERMUX_PREFIX}/usr/share/qemu/edk2-arm-vars\.fd(\.gz)?$",
    rf"^{TERMUX_PREFIX}/usr/share/qemu/efi-virtio\.rom$",
    rf"^{TERMUX_PREFIX}/usr/share/qemu/keymaps/.*$",
    rf"^{TERMUX_PREFIX}/usr/share/qemu/trace-events-all$",
]

PROJECT_ROOT = Path(__file__).resolve().parent.parent
JNILIBS_DIR = PROJECT_ROOT / "app/src/main/jniLibs/arm64-v8a"
ASSETS_VM_DIR = PROJECT_ROOT / "app/src/main/assets/vm"


@dataclass
class DebianPackage:
    name: str
    version: str
    filename: str
    sha256: str
    depends: list[str] = field(default_factory=list)


def log(message: str) -> None:
    print(f"[payload] {message}", flush=True)


def fail(message: str) -> "NoReturn":  # noqa: F821 - py3.9 friendly
    print(f"[payload] ERROR: {message}", file=sys.stderr)
    sys.exit(1)


def download(url: str, destination: Path, expected_sha256: str | None = None) -> None:
    """Download with a cached-file fast path and optional integrity check."""
    if destination.exists() and expected_sha256:
        if sha256_of(destination) == expected_sha256:
            return
        log(f"cache mismatch, re-downloading {destination.name}")
        destination.unlink()
    if not destination.exists():
        log(f"downloading {url}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(destination.suffix + ".part")
        with urllib.request.urlopen(url, timeout=120) as response:
            with open(temporary, "wb") as output:
                shutil.copyfileobj(response, output)
        temporary.rename(destination)
    if expected_sha256 and sha256_of(destination) != expected_sha256:
        fail(f"sha256 mismatch for {destination.name}")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_package_index(index_text: str) -> dict[str, DebianPackage]:
    """Parse a Debian Packages index into name -> package metadata."""
    packages: dict[str, DebianPackage] = {}
    for stanza in index_text.split("\n\n"):
        fields: dict[str, str] = {}
        for line in stanza.splitlines():
            if ":" in line and not line.startswith(" "):
                key, value = line.split(":", 1)
                fields[key] = value.strip()
        name = fields.get("Package")
        if not name:
            continue
        packages[name] = DebianPackage(
            name=name,
            version=fields.get("Version", "?"),
            filename=fields.get("Filename", ""),
            sha256=fields.get("SHA256", ""),
            depends=parse_depends(fields.get("Depends", "")),
        )
    return packages


def parse_depends(depends_field: str) -> list[str]:
    """'a (>= 1.0), b | c' -> ['a', 'b'] (first alternative wins)."""
    names: list[str] = []
    for clause in depends_field.split(","):
        clause = clause.strip()
        if not clause:
            continue
        first_alternative = clause.split("|")[0].strip()
        name = re.split(r"[\s(]", first_alternative, maxsplit=1)[0].strip()
        if name:
            names.append(name)
    return names


def resolve_dependency_closure(
    packages: dict[str, DebianPackage], roots: list[str]
) -> list[DebianPackage]:
    closure: dict[str, DebianPackage] = {}
    queue = list(roots)
    while queue:
        name = queue.pop()
        if name in closure:
            continue
        package = packages.get(name)
        if package is None:
            fail(f"package '{name}' not found in Termux index")
        closure[name] = package
        queue.extend(dep for dep in package.depends if dep not in closure)
    ordered = sorted(closure.values(), key=lambda p: p.name)
    log(f"dependency closure: {len(ordered)} packages: " + ", ".join(p.name for p in ordered))
    return ordered


def read_ar_member(archive_path: Path, member_prefix: str) -> bytes:
    """Read a member from a Unix `ar` archive (what a .deb really is).

    Implemented in Python because macOS BSD `ar` mishandles the trailing
    slashes Debian uses in member names.
    """
    data = archive_path.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        fail(f"{archive_path.name} is not an ar archive")
    offset = 8
    while offset + 60 <= len(data):
        header = data[offset : offset + 60]
        name = header[0:16].decode("ascii", "replace").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        content_start = offset + 60
        if name.startswith(member_prefix):
            return data[content_start : content_start + size]
        offset = content_start + size + (size % 2)  # members are 2-byte aligned
    fail(f"{archive_path.name}: no member starting with '{member_prefix}'")
    raise AssertionError  # unreachable


def extract_deb(deb_path: Path, extract_dir: Path) -> None:
    """Extract data.tar.* from a .deb into extract_dir."""
    extract_dir.mkdir(parents=True, exist_ok=True)
    data_tar = extract_dir / ".data.tar"
    data_tar.write_bytes(read_ar_member(deb_path, "data.tar"))
    # bsdtar auto-detects xz/zstd/gz compression from content.
    subprocess.run(
        ["tar", "-xf", str(data_tar), "-C", str(extract_dir)], check=True
    )
    data_tar.unlink()


def is_elf(path: Path) -> bool:
    if not path.is_file() or path.is_symlink():
        return False
    with open(path, "rb") as handle:
        return handle.read(4) == b"\x7fELF"


def read_soname(path: Path, patchelf: str) -> str | None:
    result = subprocess.run(
        [patchelf, "--print-soname", str(path)], capture_output=True, text=True
    )
    soname = result.stdout.strip()
    return soname if result.returncode == 0 and soname else None


def read_needed(path: Path, patchelf: str) -> list[str]:
    result = subprocess.run(
        [patchelf, "--print-needed", str(path)], capture_output=True, text=True
    )
    return result.stdout.split() if result.returncode == 0 else []


def android_safe_library_name(original_soname: str) -> str:
    """Map a soname to a name PackageManager will extract from the APK.

    Extraction requires 'lib' prefix and '.so' suffix. Versioned sonames like
    'libglib-2.0.so.0' become 'libglib-2.0_0.so'.
    """
    name = original_soname
    if name.endswith(".so"):
        base = name[: -len(".so")]
        suffix = ""
    else:
        match = re.match(r"^(.*)\.so\.(.+)$", name)
        if match:
            base = match.group(1)
            suffix = "_" + match.group(2).replace(".", "_")
        else:
            base = name
            suffix = ""
    if not base.startswith("lib"):
        base = "lib" + base
    return f"{base}{suffix}.so"


def index_shared_libraries(
    extract_root: Path, patchelf: str
) -> tuple[dict[str, Path], dict[str, str]]:
    """Index every extracted shared library.

    Returns (lookup_name -> real file, lookup_name -> soname). Lookup names
    include both real sonames and the symlink aliases (libfoo.so -> libfoo.so.1)
    that DT_NEEDED entries may reference.
    """
    file_by_name: dict[str, Path] = {}
    soname_by_name: dict[str, str] = {}

    library_patterns = (
        f"*/{TERMUX_PREFIX}/usr/lib/*.so*",
        f"*/{TERMUX_PREFIX}/usr/opt/virglrenderer-android/lib/*.so*",
    )
    libraries = sorted(
        {library for pattern in library_patterns for library in extract_root.glob(pattern)}
    )
    for library in libraries:
        if library.is_symlink() or not is_elf(library):
            continue
        soname = read_soname(library, patchelf) or library.name
        for name in {soname, library.name}:
            file_by_name.setdefault(name, library)
            soname_by_name.setdefault(name, soname)

    for link in libraries:
        if not link.is_symlink():
            continue
        target = link.resolve()
        if not target.exists() or not is_elf(target):
            continue
        soname = read_soname(target, patchelf) or target.name
        file_by_name.setdefault(link.name, target)
        soname_by_name.setdefault(link.name, soname)

    return file_by_name, soname_by_name


def repackage_elves(
    extract_root: Path, patchelf: str
) -> tuple[dict[Path, str], dict[str, str]]:
    """Plan the minimal rename set: executables plus every library reachable
    through their DT_NEEDED graphs. Anything else in the package closure
    (Python, GStreamer, X11 pulled in by optional plugins) is dropped.

    Returns (source file -> output name, referenced name -> output name).
    """
    file_by_name, soname_by_name = index_shared_libraries(extract_root, patchelf)

    executables: dict[Path, str] = {}
    for source_relative, output_name in EXECUTABLES.items():
        found = list(extract_root.glob(f"*/{TERMUX_PREFIX}/{source_relative}"))
        if not found:
            fail(f"expected executable '{source_relative}' not found in any package")
        executables[found[0]] = output_name

    reachable_sonames: set[str] = set()
    visit_queue: list[Path] = list(executables.keys())
    while visit_queue:
        elf = visit_queue.pop()
        for needed in read_needed(elf, patchelf):
            soname = soname_by_name.get(needed)
            if soname is None or soname in reachable_sonames:
                continue  # None => system library (libc.so etc.)
            reachable_sonames.add(soname)
            visit_queue.append(file_by_name[needed])

    outputs: dict[Path, str] = dict(executables)
    rename_map: dict[str, str] = {}
    for soname in sorted(reachable_sonames):
        output_name = android_safe_library_name(soname)
        rename_map[soname] = output_name
        outputs[file_by_name[soname]] = output_name
    # Aliases (libfoo.so) must rewrite to the same output as their soname.
    for name, soname in soname_by_name.items():
        if soname in rename_map:
            rename_map.setdefault(name, rename_map[soname])

    log(f"reachable libraries: {len(reachable_sonames)} of {len(file_by_name)} indexed names")
    return outputs, rename_map


def patch_and_install_elves(
    outputs: dict[Path, str], rename_map: dict[str, str], patchelf: str
) -> None:
    if JNILIBS_DIR.exists():
        shutil.rmtree(JNILIBS_DIR)
    JNILIBS_DIR.mkdir(parents=True)

    for source, output_name in sorted(outputs.items(), key=lambda item: item[1]):
        destination = JNILIBS_DIR / output_name
        if destination.exists():
            continue  # e.g. the same lib shipped by two packages
        shutil.copy2(source, destination)
        destination.chmod(0o755)

        if output_name == EXECUTABLES["usr/bin/virgl_test_server_android"]:
            rewrite_embedded_paths(destination, ANGLE_PATH_REPLACEMENTS)
        if output_name == "libvirglrenderer.so":
            rewrite_fixed_bytes(destination, VIRGL_GLES_CONTEXT_PATCH)

        commands: list[list[str]] = []
        if read_soname(destination, patchelf) is not None:
            commands.append(["--set-soname", output_name])
        for needed in read_needed(destination, patchelf):
            replacement = rename_map.get(needed)
            if replacement and replacement != needed:
                commands.append(["--replace-needed", needed, replacement])

        for command in commands:
            subprocess.run(
                [patchelf, *command, str(destination)],
                check=True,
                capture_output=True,
            )
        # Termux runpath points at /data/data/com.termux — dead weight here.
        # Best-effort: static binaries (proot loader) have no dynamic section.
        subprocess.run(
            [patchelf, "--remove-rpath", str(destination)], capture_output=True
        )
    log(f"installed {len(list(JNILIBS_DIR.iterdir()))} ELF files into {JNILIBS_DIR}")


def rewrite_embedded_paths(path: Path, replacements: dict[bytes, bytes]) -> None:
    """Replace fixed-width absolute paths embedded in an ELF safely."""
    contents = path.read_bytes()
    for original, replacement in replacements.items():
        if len(replacement) > len(original):
            fail(f"embedded path replacement is too long for {path.name}")
        occurrences = contents.count(original)
        if occurrences != 1:
            fail(
                f"expected one embedded path in {path.name}, found {occurrences}: "
                f"{original.decode()}"
            )
        padded_replacement = replacement + (b"\0" * (len(original) - len(replacement)))
        contents = contents.replace(original, padded_replacement, 1)
    path.write_bytes(contents)


def rewrite_fixed_bytes(path: Path, replacements: dict[bytes, bytes]) -> None:
    """Apply a same-size machine-code patch to one pinned ELF."""
    contents = path.read_bytes()
    for original, replacement in replacements.items():
        if len(original) != len(replacement):
            fail(f"machine-code replacement changes size for {path.name}")
        occurrences = contents.count(original)
        if occurrences != 1:
            fail(
                f"expected one machine-code pattern in {path.name}, found {occurrences}: "
                f"{original.hex()}"
            )
        contents = contents.replace(original, replacement, 1)
    path.write_bytes(contents)


def check_unresolved_dependencies(patchelf: str) -> None:
    """Every DT_NEEDED must resolve inside jniLibs or the system (libc, etc.)."""
    system_libraries = {
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so",
        "libandroid.so", "libc++_shared.so", "libEGL.so", "libGLESv2.so",
        "libvulkan.so", "libmediandk.so", "libaaudio.so", "libOpenSLES.so",
        "libjnigraphics.so", "libnativewindow.so", "libsync.so", "libcamera2ndk.so",
        "libbinder_ndk.so",
    }
    provided = {path.name for path in JNILIBS_DIR.iterdir()}
    problems: list[str] = []
    for elf in sorted(JNILIBS_DIR.iterdir()):
        for needed in read_needed(elf, patchelf):
            if needed not in provided and needed not in system_libraries:
                problems.append(f"{elf.name} needs unresolved '{needed}'")
    if problems:
        fail("unresolved dependencies:\n  " + "\n  ".join(problems))
    log("dependency check passed: all DT_NEEDED entries resolve")


def install_qemu_data_files(extract_root: Path) -> None:
    data_output_dir = ASSETS_VM_DIR / "qemu"
    if data_output_dir.exists():
        shutil.rmtree(data_output_dir)
    keep_patterns = [re.compile(pattern) for pattern in QEMU_DATA_KEEP_PATTERNS]
    installed = 0
    for package_dir in sorted(extract_root.iterdir()):
        for file in sorted(package_dir.rglob("*")):
            if not file.is_file():
                continue
            relative = file.relative_to(package_dir).as_posix()
            if not any(pattern.match(relative) for pattern in keep_patterns):
                continue
            output_relative = Path(relative).relative_to(f"{TERMUX_PREFIX}/usr/share/qemu")
            destination = data_output_dir / output_relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if file.suffix == ".gz":
                with gzip.open(file, "rb") as compressed:
                    destination = destination.with_suffix("")
                    with open(destination, "wb") as output:
                        shutil.copyfileobj(compressed, output)
            else:
                shutil.copy2(file, destination)
            installed += 1
    if installed == 0:
        fail("no QEMU data files matched — check QEMU_DATA_KEEP_PATTERNS")
    log(f"installed {installed} QEMU data files into {data_output_dir}")


def install_proot_rootfs(work_dir: Path) -> None:
    rootfs_cache = work_dir / "downloads" / Path(ALPINE_MINIROOTFS_URL).name
    download(ALPINE_MINIROOTFS_URL, rootfs_cache)
    destination_dir = ASSETS_VM_DIR / "proot"
    destination_dir.mkdir(parents=True, exist_ok=True)
    # Stored uncompressed: AAPT2 would transparently gunzip a ".gz" asset at
    # packaging time anyway, which breaks size/sha manifest verification.
    destination = destination_dir / "alpine-minirootfs-aarch64.tar"
    with gzip.open(rootfs_cache, "rb") as compressed:
        with open(destination, "wb") as output:
            shutil.copyfileobj(compressed, output)
    log(f"installed PRoot rootfs: {destination.name} ({destination.stat().st_size >> 20} MiB)")


def install_angle_libraries(extract_root: Path) -> None:
    """Install ANGLE's Vulkan and OpenGL compatibility backends."""
    angle_root = ASSETS_VM_DIR / "angle"
    if angle_root.exists():
        shutil.rmtree(angle_root)

    installed = 0
    for backend in ("vulkan", "gl"):
        source_dir_matches = list(
            extract_root.glob(
                f"angle-android/{TERMUX_PREFIX}/usr/opt/angle-android/{backend}"
            )
        )
        if len(source_dir_matches) != 1:
            fail(f"expected one ANGLE {backend} directory, found {len(source_dir_matches)}")

        destination_dir = angle_root / backend
        destination_dir.mkdir(parents=True)
        for library in sorted(source_dir_matches[0].glob("*.so")):
            shutil.copy2(library, destination_dir / library.name)
            installed += 1
    if installed == 0:
        fail("ANGLE package contains no renderer libraries")
    log(f"installed {installed} ANGLE renderer libraries into {angle_root}")


def write_payload_manifest(closure: list[DebianPackage]) -> None:
    manifest = {
        "source": TERMUX_REPO_URL,
        "packages": {p.name: p.version for p in closure},
        "files": {},
    }
    for directory in [
        JNILIBS_DIR,
        ASSETS_VM_DIR / "qemu",
        ASSETS_VM_DIR / "proot",
        ASSETS_VM_DIR / "angle",
    ]:
        for file in sorted(directory.rglob("*")):
            if file.is_file():
                key = file.relative_to(PROJECT_ROOT / "app/src/main").as_posix()
                manifest["files"][key] = {
                    "sha256": sha256_of(file),
                    "size": file.stat().st_size,
                }
    manifest_path = ASSETS_VM_DIR / "payload-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True))
    log(f"wrote {manifest_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=PROJECT_ROOT / ".payload-work",
        help="cache/extract directory (default: .payload-work)",
    )
    arguments = parser.parse_args()
    work_dir: Path = arguments.work_dir

    patchelf = shutil.which("patchelf")
    if patchelf is None:
        fail("patchelf not found — install it first (brew install patchelf)")

    index_path = work_dir / "Packages"
    download(TERMUX_INDEX_URL, index_path)
    packages = parse_package_index(index_path.read_text(errors="replace"))
    closure = resolve_dependency_closure(packages, ROOT_PACKAGES)

    extract_root = work_dir / "extracted"
    if extract_root.exists():
        shutil.rmtree(extract_root)
    for package in closure:
        deb_path = work_dir / "downloads" / Path(package.filename).name
        download(f"{TERMUX_REPO_URL}/{package.filename}", deb_path, package.sha256)
        extract_deb(deb_path, extract_root / package.name)

    outputs, rename_map = repackage_elves(extract_root, patchelf)
    patch_and_install_elves(outputs, rename_map, patchelf)
    check_unresolved_dependencies(patchelf)
    install_qemu_data_files(extract_root)
    install_proot_rootfs(work_dir)
    install_angle_libraries(extract_root)
    write_payload_manifest(closure)
    log("payload ready — rebuild the APK to pick it up")


if __name__ == "__main__":
    main()
