"""Bakes console-only Linux rootfs archives for the app's PRoot engine.

These are the counterpart to the GNOME desktop container: the same native-CPU
container, without an X server, a desktop or an editor. What they are for is a
shell — one that starts in a couple of seconds, weighs tens of megabytes
instead of a gigabyte, and has a working package manager the moment it opens,
so `apt install` (or `apk add`) is all it takes to make it whatever the user
needs.

Every flavour is produced the same way and differs only in the table below:
boot the distribution's own cloud image once under hardware acceleration,
let cloud-init configure it for life inside a container, then stream
`tar | gzip` of the finished filesystem onto an attached scratch disk. The
host trims that disk to the byte count the guest reported, and the result is
the archive.

What "ready to use" means here, concretely:

  - the package manager works under PRoot — which is not automatic, see
    APT_CONTAINER_CONFIG,
  - DNS resolves without a DHCP client,
  - the shell knows the terminal's real size, so long command lines do not
    wrap over themselves,
  - Android's unnamed group ids do not print a warning per shell,
  - and nothing that needs a kernel, an init system or a login session is
    left installed to fail noisily.
"""

from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from cloud_image_bake import BakeBootRequest, ImageBakeError
from proot_rootfs_builder import (
    APT_CONFIG,
    ARCHIVE_SIZE_MARKER,
    COLOUR_PROFILE_SCRIPT,
    CONSOLE_SIZE_PROFILE_SCRIPT,
    EXPORT_DISK_BYTES,
    FAILED_STEP_MARKER,
    GROUP_NAMER_SCRIPT,
    PROCESS_BUDGET_SCRIPT,
    _create_export_disk,
    _indent_for_write_files,
    _read_reported_archive_size,
    _require_no_failed_steps,
    _trim_export_to_archive,
)


@dataclass(frozen=True)
class ServerDistro:
    """Everything that differs between one console flavour and the next."""

    key: str
    """Human name for the archive and the logs, e.g. "Ubuntu 24.04"."""
    display_name: str
    """Packages every flavour of this distro should carry."""
    packages: tuple[str, ...]
    """Shell commands run inside the guest before the export, already
    newline-separated. Where a distro needs something none of the others do."""
    extra_setup: str = ""
    """Files that must exist in the finished archive, or the bake fails."""
    required_entries: tuple[str, ...] = ()
    """True when cloud-init on this image speaks the Debian package tooling."""
    uses_apt: bool = True
    packages_extra: tuple[str, ...] = field(default_factory=tuple)


# A shell, a package manager, networking tools and the certificates any of it
# needs. Deliberately small: the point of a server image is that the user adds
# what they want, quickly, rather than carrying someone else's guesses.
DEBIAN_FAMILY_PACKAGES = (
    "bash",
    "ca-certificates",
    "curl",
    "less",
    "nano",
    "procps",
    "iproute2",
    "iputils-ping",
    "git",
)

UBUNTU_SERVER = ServerDistro(
    key="ubuntu",
    display_name="Ubuntu 24.04",
    packages=DEBIAN_FAMILY_PACKAGES,
    required_entries=(
        "usr/bin/apt",
        "bin/bash",
        "usr/bin/script",
        "usr/local/bin/dex-session",
    ),
)

DEBIAN_SERVER = ServerDistro(
    key="debian",
    display_name="Debian 13",
    packages=DEBIAN_FAMILY_PACKAGES,
    required_entries=(
        "usr/bin/apt",
        "bin/bash",
        "usr/bin/script",
        "usr/local/bin/dex-session",
    ),
)

# Kali's cloud image is Debian underneath, so it configures identically; what
# makes it Kali is its own archive, which must keep working after the export.
KALI_SERVER = ServerDistro(
    key="kali",
    display_name="Kali Rolling",
    packages=DEBIAN_FAMILY_PACKAGES,
    required_entries=(
        "usr/bin/apt",
        "bin/bash",
        "usr/bin/script",
        "usr/local/bin/dex-session",
        "etc/apt/sources.list",
    ),
)

# Alpine is the small one: busybox, musl and apk. It needs bash explicitly,
# because the console-size and colour profile scripts are bash scripts, and
# ash would silently skip them.
ALPINE_SERVER = ServerDistro(
    key="alpine",
    display_name="Alpine 3.22",
    packages=(
        "bash",
        "ca-certificates",
        "curl",
        "less",
        "nano",
        "procps",
        "iproute2",
        "git",
        "shadow",
        "tar",
        # Provides /usr/bin/script: the app runs every terminal through it so
        # the shell gets a pseudo-terminal, without which there is no prompt,
        # no echo and no working `stty`.
        "util-linux-misc",
    ),
    uses_apt=False,
    required_entries=(
        "sbin/apk",
        "bin/bash",
        "usr/bin/script",
        "usr/local/bin/dex-session",
    ),
    extra_setup="""
# apk keeps its index in /var/cache/apk; a missing directory makes the first
# `apk update` fail the same way a missing /var/log/apt breaks apt.
mkdir -p /var/cache/apk /var/lib/apk /etc/apk
# Alpine's login shell is ash. bash is installed above, so make it the shell
# the container actually opens with.
sed -i 's|^root:.*:/bin/ash$|root:x:0:0:root:/root:/bin/bash|' /etc/passwd 2>/dev/null || true
""",
)

SERVER_DISTROS = {
    distro.key: distro
    for distro in (UBUNTU_SERVER, DEBIAN_SERVER, KALI_SERVER, ALPINE_SERVER)
}


@dataclass(frozen=True)
class ProotServerBuildRequest:
    """Everything one console-flavour bake needs, resolved by the caller."""

    distro: ServerDistro
    base_cloud_image: Path
    output_archive: Path
    work_directory: Path
    build_timeout_seconds: int = 3600


def log(message: str) -> None:
    print(f"[proot-server] {message}", flush=True)


def build_proot_server_rootfs(request: ProotServerBuildRequest) -> Path:
    """Runs one flavour's bake and returns the finished archive."""
    from build_ready_vm import build_seed_iso_from_documents  # same tools dir
    from cloud_image_bake import bake_cloud_image, prepare_staging_disk

    request.work_directory.mkdir(parents=True, exist_ok=True)
    staging_disk = request.work_directory / f"{request.distro.key}-server-build.qcow2"
    seed_iso = request.work_directory / f"{request.distro.key}-server-seed.iso"
    export_disk = request.work_directory / f"{request.distro.key}-rootfs-export.img"

    log(f"baking {request.distro.display_name} (console only)")
    prepare_staging_disk(request.base_cloud_image, staging_disk, STAGING_DISK_SIZE, log)
    _create_export_disk(export_disk)
    build_seed_iso_from_documents(
        output_iso=seed_iso,
        user_data=render_server_user_data(request.distro),
        meta_data="instance-id: linux-on-dex-001\nlocal-hostname: dex\n",
    )

    console_log = request.work_directory / "bake-console.log"
    bake_cloud_image(
        BakeBootRequest(
            staging_disk=staging_disk,
            seed_iso=seed_iso,
            work_directory=request.work_directory,
            # Printed only after a successful export, so it is the real
            # success signal — cloud-init's own final message prints even
            # when the work before it failed.
            success_marker=ARCHIVE_SIZE_MARKER,
            memory_mb=2048,
            timeout_seconds=request.build_timeout_seconds,
            extra_raw_disks=(export_disk,),
        ),
        log,
    )

    _require_no_failed_steps(console_log)
    archive_bytes = _read_reported_archive_size(
        console_log, minimum_bytes=MINIMUM_CONSOLE_ARCHIVE_BYTES,
    )
    _trim_export_to_archive(export_disk, archive_bytes, request.output_archive)
    _verify_archive_contents(request.output_archive, request.distro)

    log(f"{request.distro.display_name} ready: {request.output_archive.name} "
        f"({request.output_archive.stat().st_size >> 20} MiB)")
    return request.output_archive


def _verify_archive_contents(archive: Path, distro: ServerDistro) -> None:
    """Lists the archive here, so a broken image fails now and not on a phone."""
    log("verifying the archive (full listing)")
    listing = subprocess.run(["tar", "-tzf", str(archive)], capture_output=True, text=True)
    if listing.returncode != 0:
        raise ImageBakeError(
            f"the produced archive does not list cleanly: {listing.stderr.strip()[:500]}"
        )
    entries = listing.stdout.splitlines()
    for required in distro.required_entries:
        if not any(line.rstrip("/").endswith(required) for line in entries):
            raise ImageBakeError(
                f"required file missing from {distro.display_name}: {required}"
            )
    log(f"archive verified: {len(entries)} entries, all required files present")


# ---- The guest-side recipe ---------------------------------------------------


def render_server_user_data(distro: ServerDistro) -> str:
    """cloud-config that turns a cloud image into a console container."""
    # cloud-init's own `packages:` module does nothing on the Alpine cloud
    # image — it installs silently nothing at all — so apk-based flavours
    # list their packages in runcmd instead, where the result is visible.
    package_block = (
        "packages:\n" + "\n".join(f"  - {name}" for name in distro.packages)
        if distro.uses_apt
        else "# packages are installed in runcmd; see the note in this module"
    )
    apk_packages = " ".join(distro.packages)
    session_script = _indent_for_write_files(SERVER_SESSION_SCRIPT)
    group_namer = _indent_for_write_files(GROUP_NAMER_SCRIPT)
    process_budget = _indent_for_write_files(PROCESS_BUDGET_SCRIPT)
    colour_profile = _indent_for_write_files(COLOUR_PROFILE_SCRIPT)
    console_profile = _indent_for_write_files(CONSOLE_SIZE_PROFILE_SCRIPT)
    apt_config = _indent_for_write_files(APT_CONFIG)
    motd = _indent_for_write_files(WELCOME_MESSAGE.format(name=distro.display_name))
    package_manager_setup = (
        DEBIAN_PACKAGE_MANAGER_SETUP
        if distro.uses_apt
        else ALPINE_PACKAGE_MANAGER_SETUP.format(apk_packages=apk_packages)
    )
    extra_setup = _indent_shell_block(distro.extra_setup)

    return f"""#cloud-config
hostname: dex
manage_etc_hosts: true
disable_root: false
ssh_pwauth: false
datasource_list: [NoCloud, None]

growpart:
  mode: auto
  devices: ["/"]
resize_rootfs: true

package_update: true
{package_block}

write_files:
  # Started by the app as the container's first process; see the script.
  - path: /usr/local/bin/dex-session
    permissions: "0755"
    content: |
{session_script}
  - path: /usr/local/bin/dex-name-groups
    permissions: "0755"
    content: |
{group_namer}
  - path: /usr/local/bin/dex-processes
    permissions: "0755"
    content: |
{process_budget}
  - path: /etc/profile.d/05-linux-on-dex-groups.sh
    permissions: "0644"
    content: |
      [ -x /usr/local/bin/dex-name-groups ] && /usr/local/bin/dex-name-groups 2>/dev/null
      :
  - path: /etc/profile.d/10-linux-on-dex-colour.sh
    permissions: "0644"
    content: |
{colour_profile}
  # Keeps the shell's idea of the window the same as the real one; without it
  # a long command line wraps at the wrong column and overwrites itself.
  - path: /etc/profile.d/15-linux-on-dex-console.sh
    permissions: "0644"
    content: |
{console_profile}
  - path: /etc/apt/apt.conf.d/99-linux-on-dex
    permissions: "0644"
    content: |
{apt_config}
  - path: /etc/motd
    permissions: "0644"
    content: |
{motd}

runcmd:
  # Each entry is its own subshell: cloud-init concatenates runcmd into one
  # script, so a bare `set -e` anywhere would abort every later entry —
  # including the export that produces the artifact.
{package_manager_setup}
{extra_setup}
  # Nothing here has a kernel to talk to, an init system to be supervised by,
  # or a login session to belong to. Left installed they fail noisily on
  # every start and take up room in Android's process budget.
  - |
    export DEBIAN_FRONTEND=noninteractive
    apt-get purge -y 'linux-image-*' 'linux-headers-*' 'linux-modules-*' \\
        'linux-virtual*' 'linux-generic*' 'linux-firmware*' 'grub-efi*' \\
        grub-common grub2-common shim-signed flash-kernel snapd \\
        cloud-init unattended-upgrades 2>/dev/null || true
    apt-get autoremove --purge -y 2>/dev/null || true
    rm -rf /usr/src/* /lib/modules /boot/* 2>/dev/null || true
  # Documentation and translations nobody reads on a phone, and a dpkg rule
  # so they do not come back with the next install.
  - |
    rm -rf /usr/share/doc /usr/share/man /usr/share/info /usr/share/lintian \\
           /usr/share/locale-langpack 2>/dev/null || true
    find /usr/share/locale -mindepth 1 -maxdepth 1 -type d \\
         ! -name 'en*' ! -name 'C*' -exec rm -rf {{}} + 2>/dev/null || true
    mkdir -p /etc/dpkg/dpkg.cfg.d
    printf 'path-exclude=/usr/share/doc/*\\npath-exclude=/usr/share/man/*\\n' \\
        > /etc/dpkg/dpkg.cfg.d/01-linux-on-dex-slim
  # Per-device identity must not be baked into an image every phone shares.
  - |
    rm -f /etc/ssh/ssh_host_* /etc/machine-id /var/lib/dbus/machine-id
    rm -rf /var/lib/cloud /root/.cache /tmp/* 2>/dev/null || true
    find /var/log -type f -delete 2>/dev/null || true
  # Stream the finished filesystem out and report its exact size, which is
  # the only way the host learns where the archive ends on the scratch disk.
  #
  # /var/log keeps its directory tree on purpose: apt refuses to install
  # anything when /var/log/apt is missing, and the tree costs nothing once
  # the files inside it are gone.
  - |
    [ -b /dev/vdc ] || {{ echo 'DEX_EXPORT_DISK_MISSING'; exit 1; }}
    # The export needs GNU tar: busybox's tar, which is what Alpine has by
    # default, rejects --format=gnu and quietly writes an empty archive
    # instead of failing. Where a distro installs GNU tar is not worth
    # guessing, so it is discovered by asking each candidate what it is.
    TAR_BIN=""
    for candidate in tar gtar /usr/bin/tar /bin/tar /usr/local/bin/tar; do
      if command -v "$candidate" >/dev/null 2>&1 &&
         "$candidate" --version 2>/dev/null | head -1 | grep -q 'GNU tar'; then
        TAR_BIN="$candidate"
        break
      fi
    done
    [ -n "$TAR_BIN" ] || {{ echo 'DEX_BAKE_STEP_FAILED: no GNU tar for the export'; exit 1; }}
    cd /
    "$TAR_BIN" --numeric-owner --format=gnu -cpf - \\
        --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./run \\
        --exclude=./boot --exclude=./mnt --exclude=./media \\
        --exclude=./lost+found --exclude=./var/lib/cloud \\
        --exclude=./usr/lib/firmware --exclude=./usr/lib/modules \\
        --exclude='./tmp/*' \\
        . | gzip -6 | tee /dev/vdc | wc -c > /run/rootfs-bytes
    sync
    echo "{ARCHIVE_SIZE_MARKER}$(cat /run/rootfs-bytes)"

power_state:
  mode: poweroff
  timeout: 120
  condition: true

final_message: "Linux on DeX server rootfs complete."
"""


def _indent_shell_block(commands: str) -> str:
    """Wraps a shell snippet as one indented cloud-init runcmd entry."""
    body = commands.strip("\n")
    if not body:
        return ""
    indented = "\n".join(f"    {line}".rstrip() for line in body.splitlines())
    return f"  - |\n{indented}\n"


# apt under PRoot needs the same treatment as in the desktop image: it drops
# to an unprivileged helper to fetch packages, and PRoot only pretends to be
# root, so the helper cannot read the files it just wrote. Verified here so a
# broken package manager fails the build rather than reaching a phone.
DEBIAN_PACKAGE_MANAGER_SETUP = """  - |
    (
      set -e
      export DEBIAN_FRONTEND=noninteractive
      mkdir -p /var/log/apt /var/log/dpkg /var/cache/apt/archives/partial \\
               /var/lib/apt/lists/partial /var/lib/dpkg/updates
      apt-get update
      apt-get clean
    ) || echo 'DEX_BAKE_STEP_FAILED: apt setup'
"""

ALPINE_PACKAGE_MANAGER_SETUP = """  - |
    (
      set -e
      mkdir -p /var/cache/apk /var/lib/apk /etc/apk
      apk update
      apk add --no-cache {apk_packages}
      command -v bash >/dev/null
      test -x /usr/bin/script
      tar --version | head -1 | grep -q "GNU tar"
    ) || echo 'DEX_BAKE_STEP_FAILED: apk setup'
"""


# The app runs this as the container's first process. It is deliberately not
# a shell: the app opens its own shells for each terminal window, and this
# process only has to prepare the container and then stay alive, because when
# it exits PRoot tears the whole container down.
SERVER_SESSION_SCRIPT = r"""#!/bin/sh
# Linux on DeX console session. Prepares the container, then waits.
#
# The app starts this as PRoot's first process and treats its lifetime as the
# session's lifetime, so it must not exit while the user still wants a shell.
# Every terminal window the app opens is a separate shell into this same
# container, not a child of this script.
set -u

log() { echo "[dex-session] $*"; }

export HOME=/root USER=root LOGNAME=root
export LANG=C.UTF-8

# /run and /tmp hold nothing across a start, and the directories apt and dpkg
# insist on existing are cheap to guarantee here — a missing one produces an
# error message that gives no hint that a directory is all that is needed.
mkdir -p /run /tmp /var/log/apt /var/log/dpkg \
         /var/cache/apt/archives/partial /var/lib/apt/lists/partial \
         /var/cache/apk 2>/dev/null || true
chmod 1777 /tmp 2>/dev/null || true

# Android's supplementary group ids have no names inside the container, and
# without them every shell greets the user with one warning per id.
/usr/local/bin/dex-name-groups 2>/dev/null || true

log "console container ready"
log "open a terminal window to use it"

# Waiting forever is the whole job. `sleep infinity` is not in every busybox,
# so this loops instead, which works the same everywhere.
while true; do
    sleep 3600
done
"""


WELCOME_MESSAGE = """Linux on DeX — {name} (console container)

Running natively through PRoot: no virtual machine, no emulation.
The package manager is ready to use, for example:

    apt update && apt install <package>      (Ubuntu, Debian, Kali)
    apk update && apk add <package>          (Alpine)

    dex-processes    how much of Android's process budget is in use

Everything runs as root; there is one user and no login.
"""

STAGING_DISK_SIZE = "12G"

# A console container has no desktop, so it is a fraction of the GNOME image.
# Alpine is the smallest by far; this floor only has to catch an export that
# produced nothing, which is what a wrong tar does.
MINIMUM_CONSOLE_ARCHIVE_BYTES = 20 << 20
