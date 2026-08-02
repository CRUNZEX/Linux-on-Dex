#!/usr/bin/env python3
"""Build a ready-to-boot Ubuntu ARM64 virtual machine for Linux on DeX.

Why this exists
---------------
Installing from a desktop ISO inside a software-emulated VM takes a very
long time: the live session unpacks a 3 GB squashfs, seeds snaps, and only
then offers an installer that writes the system a second time.

Ubuntu publishes *cloud images* — the same distribution, already installed
into a qcow2 disk. Paired with a tiny cloud-init seed ISO they boot straight
to a login prompt in seconds. This script produces exactly that pair:

    linux-on-dex-ubuntu-<version>-arm64.qcow2   the root disk
    linux-on-dex-seed.iso                       cloud-init: user, autologin

The seed configures user `dex` (password `dex`, passwordless sudo), enables
the serial console so the app's Terminal screen works from power-on, and
disables the slow first-boot work a phone does not need.

Usage:
    python3 tools/build_ready_vm.py [--release 24.04] [--disk-size 16G]
                                    [--username dex] [--password dex]

Requires: qemu-img and either xorriso, genisoimage or hdiutil (macOS).
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

import cloud_image_bake

# The guest keeps running after cloud-init finishes (phone first boot)…
STAY_UP_POWER_STATE = """power_state:
  mode: reboot
  condition: false
"""

# …or powers off so the build machine can compact the finished disk.
BAKE_POWER_STATE = """power_state:
  mode: poweroff
  timeout: 120
  condition: true
"""

CLOUD_IMAGE_URL_TEMPLATES = {
    "ubuntu": (
        "https://cloud-images.ubuntu.com/releases/{release}/release/"
        "ubuntu-{release}-server-cloudimg-arm64.img"
    ),
    # genericcloud: the smaller Debian variant meant for exactly this kind
    # of virtual machine (no bare-metal firmware payloads).
    "debian": (
        "https://cloud.debian.org/images/cloud/trixie/latest/"
        "debian-{release}-genericcloud-arm64.qcow2"
    ),
    # The UEFI cloud-init build of Alpine; {series} is "v3.22" for "3.22.0".
    # "alpine" bakes the container host, "alpine-light" the bare minimum —
    # both start from this same base image.
    "alpine": (
        "https://dl-cdn.alpinelinux.org/alpine/{series}/releases/cloud/"
        "nocloud_alpine-{release}-aarch64-uefi-cloudinit-r0.qcow2"
    ),
    "alpine-light": (
        "https://dl-cdn.alpinelinux.org/alpine/{series}/releases/cloud/"
        "nocloud_alpine-{release}-aarch64-uefi-cloudinit-r0.qcow2"
    ),
    # Kali ships its cloud image as a tar.xz holding a sparse raw disk, so
    # this one is unpacked and converted before use (see prepare_kali_base).
    "kali": (
        "https://kali.download/cloud-images/kali-{release}/"
        "kali-linux-{release}-cloud-genericcloud-arm64.tar.xz"
    ),
}

# Published alongside each Kali image; verified before the archive is used.
KALI_CHECKSUMS_URL = "https://kali.download/cloud-images/kali-{release}/SHA256SUMS"

DEFAULT_RELEASES = {
    "ubuntu": "24.04",
    "debian": "13",
    "alpine": "3.22.0",
    "alpine-light": "3.22.0",
    "kali": "2026.2",
}

ALPINE_DISTROS = ("alpine", "alpine-light")


def release_series(release: str) -> str:
    """The Alpine mirror path segment for a release: "3.22.0" → "v3.22"."""
    parts = release.split(".")
    if len(parts) < 2:
        fail(f"cannot derive a series from release '{release}' (want e.g. 3.22.0)")
    return f"v{parts[0]}.{parts[1]}"

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "dist" / "ready-vm"

# cloud-init reads these two files from a volume labelled "cidata".
SEED_VOLUME_LABEL = "cidata"


def log(message: str) -> None:
    print(f"[ready-vm] {message}", flush=True)


def fail(message: str) -> "NoReturn":  # noqa: F821
    print(f"[ready-vm] ERROR: {message}", file=sys.stderr)
    sys.exit(1)


def require_tool(name: str) -> str:
    path = shutil.which(name)
    if path is None:
        fail(f"'{name}' is required but not installed")
    return path


def download_cloud_image(distro: str, release: str, destination: Path) -> None:
    """Downloads the cloud image, reusing an existing complete copy."""
    url = CLOUD_IMAGE_URL_TEMPLATES[distro].format(
        release=release,
        series=release_series(release) if distro in ALPINE_DISTROS else "",
    )
    if destination.exists() and destination.stat().st_size > MIN_PLAUSIBLE_IMAGE_BYTES:
        log(f"reusing cached {destination.name} "
            f"({destination.stat().st_size >> 20} MiB)")
        return

    log(f"downloading {url}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            total_bytes = int(response.headers.get("Content-Length", 0))
            downloaded = 0
            next_report = REPORT_EVERY_BYTES
            with open(partial, "wb") as output:
                while True:
                    chunk = response.read(DOWNLOAD_CHUNK_BYTES)
                    if not chunk:
                        break
                    output.write(chunk)
                    downloaded += len(chunk)
                    if downloaded >= next_report:
                        percent = f"{downloaded * 100 // total_bytes}%" if total_bytes else "?"
                        log(f"  {downloaded >> 20} MiB ({percent})")
                        next_report += REPORT_EVERY_BYTES
    except Exception as error:  # noqa: BLE001 - report and stop
        partial.unlink(missing_ok=True)
        fail(f"download failed: {error}")
    partial.rename(destination)
    log(f"downloaded {destination.stat().st_size >> 20} MiB")


def prepare_kali_base(archive: Path, release: str, prepared_image: Path) -> None:
    """Turns Kali's published tar.xz into a qcow2 the builders can boot.

    The archive holds one sparse raw disk (25 GiB of mostly holes), so it is
    unpacked to a scratch file and converted; qcow2 stores only the written
    blocks, taking the result from 25 GiB to roughly 1 GiB.

    The download is checked against Kali's published SHA256 first: a
    truncated or tampered archive should stop the build, not surface later
    as an unbootable image on the phone.
    """
    if prepared_image.exists() and prepared_image.stat().st_size > MIN_PLAUSIBLE_IMAGE_BYTES:
        log(f"reusing prepared {prepared_image.name} "
            f"({prepared_image.stat().st_size >> 20} MiB)")
        return

    verify_kali_checksum(archive, release)
    qemu_img = require_tool("qemu-img")
    with tempfile.TemporaryDirectory(dir=str(archive.parent)) as scratch:
        log(f"unpacking {archive.name}")
        raw_disk = extract_single_disk(archive, Path(scratch))
        log(f"converting {raw_disk.name} to qcow2")
        subprocess.run(
            [qemu_img, "convert", "-O", "qcow2", str(raw_disk), str(prepared_image)],
            check=True,
            capture_output=True,
        )
    log(f"prepared base image ({prepared_image.stat().st_size >> 20} MiB)")


def extract_single_disk(archive: Path, destination: Path) -> Path:
    """Extracts the one disk file inside [archive] and returns its path."""
    with tarfile.open(archive, "r:xz") as bundle:
        disk_members = [member for member in bundle.getmembers() if member.isfile()]
        if len(disk_members) != 1:
            fail(
                f"expected exactly one file inside {archive.name}, "
                f"found {len(disk_members)}"
            )
        member = disk_members[0]
        # The archive is a vendor download, but a member path is still
        # attacker-controlled data: refuse anything that escapes the
        # destination instead of trusting it.
        if member.name.startswith("/") or ".." in Path(member.name).parts:
            fail(f"unsafe path inside {archive.name}: {member.name}")
        bundle.extract(member, path=destination)
    return destination / member.name


def verify_kali_checksum(archive: Path, release: str) -> None:
    """Compares the archive against Kali's published SHA256SUMS."""
    url = KALI_CHECKSUMS_URL.format(release=release)
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            checksums = response.read().decode("utf-8", errors="replace")
    except Exception as unreachable:  # noqa: BLE001 - a warning, not a stop
        log(f"WARNING: could not fetch {url} ({unreachable}); skipping checksum check")
        return

    expected = next(
        (line.split()[0] for line in checksums.splitlines() if archive.name in line),
        None,
    )
    if expected is None:
        # Our cache file is renamed, so match on the published name instead.
        expected = next(
            (line.split()[0] for line in checksums.splitlines()
             if line.strip().endswith("cloud-genericcloud-arm64.tar.xz")),
            None,
        )
    if expected is None:
        log("WARNING: no arm64 entry in SHA256SUMS; skipping checksum check")
        return

    log("verifying the download against Kali's published SHA256")
    digest = hashlib.sha256()
    with open(archive, "rb") as stream:
        for chunk in iter(lambda: stream.read(DOWNLOAD_CHUNK_BYTES), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        fail(
            f"{archive.name} does not match the published checksum. "
            "Delete it and run the build again."
        )


def build_root_disk(cloud_image: Path, output_disk: Path, disk_size: str) -> None:
    """Copies the cloud image to the output disk and grows it."""
    qemu_img = require_tool("qemu-img")
    output_disk.parent.mkdir(parents=True, exist_ok=True)
    log(f"creating {output_disk.name} ({disk_size})")
    shutil.copyfile(cloud_image, output_disk)
    subprocess.run(
        [qemu_img, "resize", str(output_disk), disk_size],
        check=True,
        capture_output=True,
    )
    # A sanity check beats discovering a broken image on the phone.
    info = subprocess.run(
        [qemu_img, "info", str(output_disk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    if "file format: qcow2" not in info:
        fail(f"{output_disk.name} is not a qcow2 image after resize")
    log(f"root disk ready ({output_disk.stat().st_size >> 20} MiB on disk)")


def render_user_data(
    username: str,
    password: str,
    *,
    bake_mode: bool = False,
    extra_runcmd: str = "",
) -> str:
    """cloud-init user-data: the account, the console, and speed-ups.

    The same document serves both ways this project configures a guest:

      - on the phone at first boot (the default), where the VM must stay up
        afterwards, and
      - during a build-machine bake ([bake_mode]), where cloud-init powers
        the guest off and prints a marker the harness waits for.

    [extra_runcmd] is appended, already YAML-indented, for flavour-specific
    work such as extra service trimming.
    """
    power_state = BAKE_POWER_STATE if bake_mode else STAY_UP_POWER_STATE
    final_message = (
        cloud_image_bake.BAKE_COMPLETE_MARKER if bake_mode
        else f"Linux on DeX is ready. Log in as {username}/{password}."
    )
    return f"""#cloud-config
hostname: dex
manage_etc_hosts: true

users:
  - name: {username}
    gecos: Linux on DeX
    groups: [adm, sudo, users, video, audio, plugdev]
    shell: /bin/bash
    sudo: "ALL=(ALL) NOPASSWD:ALL"
    lock_passwd: false
    plain_text_passwd: {password}

ssh_pwauth: true
disable_root: true

# Phones have no cloud metadata service; skipping the probe saves the long
# start-up stall cloud-init would otherwise spend timing out.
datasource_list: [NoCloud, None]

growpart:
  mode: auto
  devices: ["/"]
resize_rootfs: true

write_files:
  # Log the user straight into the serial console the app's Terminal shows.
  # TERM=xterm-256color: the app ships a real terminal emulator; systemd's
  # vt220 serial default would strip it down to monochrome.
  - path: /etc/systemd/system/serial-getty@ttyAMA0.service.d/autologin.conf
    permissions: "0644"
    content: |
      [Service]
      Environment=TERM=xterm-256color
      Environment=COLORTERM=truecolor
      ExecStart=
      ExecStart=-/sbin/agetty --autologin {username} --noclear %I $TERM
  # The app's extra terminal windows attach to virtio consoles
  # (/dev/hvc0, /dev/hvc1); each needs its own logged-in shell.
  - path: /etc/systemd/system/serial-getty@hvc0.service.d/autologin.conf
    permissions: "0644"
    content: |
      [Service]
      Environment=TERM=xterm-256color
      Environment=COLORTERM=truecolor
      ExecStart=
      ExecStart=-/sbin/agetty --autologin {username} --noclear %I $TERM
  - path: /etc/systemd/system/serial-getty@hvc1.service.d/autologin.conf
    permissions: "0644"
    content: |
      [Service]
      Environment=TERM=xterm-256color
      Environment=COLORTERM=truecolor
      ExecStart=
      ExecStart=-/sbin/agetty --autologin {username} --noclear %I $TERM
  - path: /etc/sysctl.d/99-dex.conf
    permissions: "0644"
    content: |
      # Emulated storage is slow; batch writeback instead of trickling it.
      vm.dirty_ratio = 30
      vm.dirty_background_ratio = 10
  # QEMU's user-mode network resolves DNS through the host's /etc/resolv.conf,
  # which does not exist on Android — the DHCP-provided 10.0.2.3 resolver is
  # dead on a phone. Public resolvers, reached as ordinary UDP traffic, work
  # everywhere.
  - path: /etc/systemd/resolved.conf.d/50-linux-on-dex-dns.conf
    permissions: "0644"
    content: |
      [Resolve]
      DNS=1.1.1.1 8.8.8.8
      FallbackDNS=9.9.9.9
      Domains=~.
  # Serial lines cannot deliver SIGWINCH, so the guest never learns the
  # terminal size. This asks the terminal directly (cursor-position report)
  # at login, and by hand via `fix_console` after a resize.
  # Colour lives in its own file, sorted early on purpose: /etc/profile
  # sources profile.d in glob order, and a `return` inside any one of those
  # scripts ends the *whole* loop — cloud-init's locale script does exactly
  # that, so anything sorted after it never ran. That is why a serial login
  # kept TERM=dumb and every program turned colour off, while `sudo`, which
  # builds its own environment, looked fine.
  - path: /etc/profile.d/10-linux-on-dex-colour.sh
    permissions: "0644"
    content: |
      case "${{TERM:-}}" in
          ""|dumb|unknown|vt100|vt102|vt220|linux) TERM=xterm-256color ;;
      esac
      export TERM
      export COLORTERM=truecolor
  - path: /etc/profile.d/98-linux-on-dex-console.sh
    permissions: "0644"
    content: |
      [ -n "$BASH_VERSION" ] || return 0
      fix_console() {{
          [ -t 0 ] && [ -t 1 ] || return 0
          local saved rows cols discard
          saved=$(stty -g 2>/dev/null) || return 0
          stty raw -echo min 0 time 5 2>/dev/null || return 0
          printf '\\0337\\033[999;999H\\033[6n\\0338' > /dev/tty
          IFS='[;R' read -r -t 1 -d R discard rows cols < /dev/tty 2>/dev/null
          stty "$saved" 2>/dev/null
          if [ -n "$rows" ] && [ -n "$cols" ] && [ "$rows" -gt 0 ] 2>/dev/null; then
              stty rows "$rows" cols "$cols" 2>/dev/null
          fi
      }}
      # Sync once at login, then again before every prompt: the app's
      # terminal can be resized or popped into its own DeX window at any
      # time, and the next prompt heals the guest's size before the next
      # command (apt, dpkg, editors) draws by it. The probe costs one
      # instant cursor-position round trip on a connected console.
      case "$(tty 2>/dev/null)" in
          /dev/ttyAMA*|/dev/ttyS*|/dev/hvc*)
              fix_console
              PROMPT_COMMAND="fix_console${{PROMPT_COMMAND:+;$PROMPT_COMMAND}}"
              ;;
      esac

bootcmd:
  # Snaps have no place in a phone VM, and seeding them costs many minutes
  # of emulated CPU on the very first boot. bootcmd runs before services
  # start, so the mask lands before seeding can begin. Debian images have
  # no snapd; the guard keeps this a no-op there.
  - [sh, -c, "command -v snap >/dev/null 2>&1 && systemctl mask --now snapd.service snapd.socket snapd.seeded.service 2>/dev/null || true"]

runcmd:
  # Nothing here needs to run on a phone, and each one costs real time
  # under software emulation. Missing units (e.g. snapd on Debian) just log
  # and move on — runcmd lines are independent.
  - [sh, -c, "systemctl disable --now snapd.service snapd.socket snapd.seeded.service 2>/dev/null || true"]
  - [sh, -c, "systemctl disable --now unattended-upgrades.service 2>/dev/null || true"]
  - [sh, -c, "systemctl disable --now apt-daily.timer apt-daily-upgrade.timer 2>/dev/null || true"]
  - [sh, -c, "systemctl disable --now motd-news.timer 2>/dev/null || true"]
  # Ubuntu Pro advertising, storage multipathing, weekly database and
  # filesystem chores: none of it earns its boot time in a phone VM.
  - [sh, -c, "systemctl disable --now ua-timer.timer apt-news.service esm-cache.service 2>/dev/null || true"]
  - [sh, -c, "systemctl disable --now multipathd.service multipathd.socket 2>/dev/null || true"]
  - [sh, -c, "systemctl disable --now man-db.timer e2scrub_all.timer fwupd-refresh.timer 2>/dev/null || true"]
  - [systemctl, mask, systemd-networkd-wait-online.service]
  # Qualcomm-only units that restart forever on QEMU's virt board.
  - [systemctl, mask, pd-mapper.service, qrtr-ns.service]
  - [systemctl, daemon-reload]
  # DNS, on whichever resolver stack this distro uses: systemd-resolved
  # picks up the drop-in written above; classic dhclient setups get a
  # supersede rule (so lease renewals keep it) plus an immediate
  # /etc/resolv.conf so names work before the next renewal.
  - [sh, -c, "printf 'supersede domain-name-servers 1.1.1.1, 8.8.8.8;\\n' >> /etc/dhcp/dhclient.conf 2>/dev/null || true"]
  - [sh, -c, "if systemctl is-enabled --quiet systemd-resolved 2>/dev/null || systemctl is-active --quiet systemd-resolved 2>/dev/null; then systemctl restart systemd-resolved; else printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > /etc/resolv.conf; fi"]
  - [systemctl, restart, "serial-getty@ttyAMA0.service"]
  # Extra terminal windows: a getty per virtio console.
  - [sh, -c, "systemctl enable serial-getty@hvc0.service 2>/dev/null || true"]
  - [sh, -c, "systemctl enable serial-getty@hvc1.service 2>/dev/null || true"]
  # Restart, not just enable: a getty already started before the
  # autologin drop-in existed would keep prompting for a password.
  - [sh, -c, "systemctl restart serial-getty@hvc0.service 2>/dev/null || true"]
  - [sh, -c, "systemctl restart serial-getty@hvc1.service 2>/dev/null || true"]
{extra_runcmd}  # Hand every block the install no longer needs back to the image
  # file. Package archives and temporary files were written and deleted,
  # and until they are discarded the qcow2 keeps paying for them: the
  # compaction pass afterwards can only drop clusters it knows are free.
  - [sh, -c, "apt-get clean 2>/dev/null || true"]
  - [sh, -c, "rm -rf /var/lib/apt/lists/* /var/log/journal/* /tmp/* 2>/dev/null || true"]
  - [sh, -c, "fstrim -av || true"]
  # Everything above is now baked into the disk. Without this flag,
  # cloud-init would re-run all four of its stages on every later boot —
  # several seconds of pure start-up cost under software emulation.
  - [touch, /etc/cloud/cloud-init.disabled]

{power_state}
final_message: "{final_message}"
"""


def render_meta_data() -> str:
    return "instance-id: linux-on-dex-001\nlocal-hostname: dex\n"


def build_seed_iso(output_iso: Path, username: str, password: str) -> None:
    """Writes the runtime NoCloud seed for a server-flavour image."""
    build_seed_iso_from_documents(
        output_iso=output_iso,
        user_data=render_user_data(username, password),
        meta_data=render_meta_data(),
    )


def build_seed_iso_from_documents(output_iso: Path, user_data: str, meta_data: str) -> None:
    """Writes a NoCloud seed ISO from the two cloud-init documents.

    Shared with the desktop builder, which needs a different user-data but
    the same ISO layout: a volume labelled `cidata` holding both files.
    """
    staging = output_iso.parent / f"{output_iso.stem}-staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    (staging / "user-data").write_text(user_data)
    (staging / "meta-data").write_text(meta_data)

    output_iso.unlink(missing_ok=True)
    builder = pick_iso_builder()
    log(f"building {output_iso.name} with {builder[0]}")
    subprocess.run(builder[1](staging, output_iso), check=True, capture_output=True)
    shutil.rmtree(staging)

    if not output_iso.exists() or output_iso.stat().st_size == 0:
        fail("seed ISO was not produced")
    log(f"seed ISO ready ({output_iso.stat().st_size >> 10} KiB)")


def pick_iso_builder():
    """Returns (tool name, argv builder) for the first available ISO tool."""
    if shutil.which("xorriso"):
        return "xorriso", lambda staging, iso: [
            "xorriso", "-as", "mkisofs", "-output", str(iso),
            "-volid", SEED_VOLUME_LABEL, "-joliet", "-rock", str(staging),
        ]
    if shutil.which("genisoimage"):
        return "genisoimage", lambda staging, iso: [
            "genisoimage", "-output", str(iso),
            "-volid", SEED_VOLUME_LABEL, "-joliet", "-rock", str(staging),
        ]
    if shutil.which("hdiutil"):
        # macOS: -iso9660 keeps the volume name readable by cloud-init.
        return "hdiutil", lambda staging, iso: [
            "hdiutil", "makehybrid", "-iso", "-joliet",
            "-default-volume-name", SEED_VOLUME_LABEL,
            "-o", str(iso), str(staging),
        ]
    fail("need one of xorriso, genisoimage or hdiutil to build the seed ISO")


def write_install_notes(output_dir: Path, username: str, password: str) -> None:
    """One README covering every flavour that can live in this folder."""
    notes = f"""Linux on DeX — ready-to-boot images (arm64)

Flavours (each .qcow2 pairs with its <name>-seed.iso)
  linux-on-dex-ubuntu-24.04-arm64.qcow2                Ubuntu server, console only
  linux-on-dex-debian-13-arm64.qcow2                   Debian server, console only
  linux-on-dex-alpine-3.22.0-arm64.qcow2               Alpine container host:
                                                       podman, docker and LXC
                                                       preinstalled and enabled
  linux-on-dex-alpine-light-3.22.0-arm64.qcow2         Alpine light: the
                                                       smallest and fastest-
                                                       booting flavour
  linux-on-dex-kali-2026.2-arm64.qcow2                 Kali Linux server,
                                                       console only and light
                                                       (add tools with apt)
  linux-on-dex-ubuntu-24.04-desktop-xfce-arm64.qcow2   XFCE desktop (light, smooth)
  linux-on-dex-ubuntu-24.04-desktop-gnome-arm64.qcow2  GNOME Flashback desktop
  linux-on-dex-<distro>-proot-arm64.rootfs.tar.gz      Console containers on
                                                       PRoot: Ubuntu, Debian,
                                                       Kali and Alpine, each
                                                       with a working package
                                                       manager and nothing
                                                       else. Alpine is 49 MB
                                                       and unpacks in a second
  linux-on-dex-ubuntu-24.04-proot-gnome-arm64.rootfs.tar.gz
                                                       GNOME Shell on PRoot:
                                                       native CPU speed (no VM),
                                                       git + ssh + VS Code +
                                                       Firefox
                                                       preinstalled, apt ready
                                                       to use
  linux-on-dex-ubuntu-24.04-proot-xfce-arm64.rootfs.tar.gz
                                                       GNOME-like lightweight
                                                       XFCE on PRoot: top bar,
                                                       dock, compositor off,
                                                       VS Code preinstalled

Every flavour is preconfigured:
  - user {username} / password {password}, passwordless sudo
  - serial console autologin with TERM=xterm-256color
  - working DNS on the phone, on any resolver stack (systemd-resolved,
    classic dhclient, or Alpine's static resolv.conf) — QEMU's built-in
    resolver cannot work on Android
  - `fix_console` sizes the console to the app's terminal at login and
    before every prompt
  - snaps masked and cloud-init disabled after first boot, so servers boot
    with no background chores stealing emulated CPU

Alpine container host
  docker is running from boot ({username} is in the docker group):
    docker run --rm hello-world
  podman needs no daemon:  podman run --rm docker.io/library/alpine echo hi
  LXC is installed with lxcfs; configure networking to taste.

Kali Linux server
  The published cloud image, kept console-only and light (~340 packages),
  baked here so the phone boots a finished system. Add what you need:
    sudo apt update && sudo apt install <tool>
    sudo apt install kali-linux-headless    # the usual toolset (large)

Install from the app (recommended)
  Settings -> Ready-made VM -> Import VM image... -> pick the .qcow2
  The app generates the matching cloud-init seed itself.

Or push over USB
  adb push <image>.qcow2      /sdcard/Android/data/com.crunzex.linuxondex/files/vm-images/
  adb push <image>-seed.iso   /sdcard/Android/data/com.crunzex.linuxondex/files/vm-images/

Desktops
  XFCE runs without a compositor — the smooth choice for a software VM.
  GNOME uses the supported Flashback session with GNOME Panel and Metacity,
  avoiding GNOME Shell's costly software compositor. Both boot with cloud-init disabled:
  everything is baked at build time, so the phone boots straight into
  the desktop.

GNOME-like XFCE on PRoot (recommended)
  Import linux-on-dex-ubuntu-24.04-proot-xfce-arm64.rootfs.tar.gz.
  The GNOME-like top bar and dock use only lightweight XFCE components. The
  compositor, desktop manager, animations and unused service helpers stay off.
  SSH, git, a terminal, file manager and Mesa diagnostics are included. The
  app validates native virgl/ANGLE acceleration at every start and falls back
  to llvmpipe only when the device's Android EGL path cannot start.

GNOME Shell on PRoot
  Not a VM: the app runs this Ubuntu tree through PRoot's syscall
  translation at native CPU speed, which no emulated qcow2 desktop can
  match. Everything runs as root (PRoot has a single user), sign in over
  ssh with root/{password} on 127.0.0.1:8022, and VS Code launches
  sandbox-free (a PRoot necessity). The first start extracts the archive
  once — under a minute, with a progress bar — and every start after that
  goes straight to the desktop. The app's terminal windows open shells
  into the same system.

  Why this image runs so few background services
    Android 12 and newer kill an app's forked child processes once they
    pass a limit (max_phantom_processes, 32 by default) — and they kill
    them as a group, which takes the X server and the desktop with them.
    A stock Ubuntu GNOME session starts more than forty processes, so it
    cannot survive here at all. This image therefore keeps only X, D-Bus,
    sshd, GNOME Shell/Mutter, XSettings and dconf: the sixteen
    settings-daemon plugins, ibus, Evolution, Online Accounts, PackageKit,
    upower, the portals and the file indexers are removed. That is what
    makes it both survivable and fast.

    If you still hit the limit while running something process-heavy, the
    monitor can be switched off over adb (no root needed):
      adb shell settings put global settings_enable_monitor_phantom_procs false

  Installing your own packages
    apt works out of the box: the image fetches as root (PRoot cannot hand
    file ownership to apt's unprivileged helper, so the helper could not read
    what it had just downloaded), skips HTTP pipelining, retries three times
    and keeps apt's and dpkg's working directories present. Just:
      apt update && apt install <package>

  Measuring smoothness on your own device
    Open a terminal (on the desktop, or the app's Terminal) and run:
      dex-fps            # 20 seconds, or dex-fps 60 for a longer run
    It prints the renderer in use and a measured frame rate every five
    seconds. A supported native bridge reports virgl; an unsupported Android
    EGL path reports llvmpipe. This image turns off animations, compositing
    effects and blinking cursors in both cases. Lowering the display resolution
    remains the single biggest lever on the number you see.

Display input and frame ceiling
  The embedded viewer forwards DeX mouse buttons, wheel, hardware keyboard,
  touch taps/drags and Android IME text directly over RFB. It never captures
  the Android pointer. TigerVNC accepts up to 240 updates per second; the
  visible rate is still capped by the phone or monitor refresh rate.

Server images run one-time setup on first boot (account, disk grow), so
that boot takes a little longer; afterwards every boot goes straight to
a login prompt.
"""
    (output_dir / "README.txt").write_text(notes, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--flavour",
        choices=["server", "desktop", "proot-gnome", "proot-server"],
        default="server",
        help="server boots to a shell; desktop installs a graphical "
        "environment into a qcow2 VM disk; proot-gnome bakes an Ubuntu GNOME "
        "rootfs archive for the app's native-speed PRoot container; "
        "proot-server bakes a console-only container for --distro "
        "(ubuntu, debian, kali or alpine)",
    )
    parser.add_argument(
        "--desktop-environment",
        choices=["xfce", "gnome"],
        default="xfce",
        help="desktop flavour only: xfce is light and fast, gnome uses "
        "GNOME Flashback for stable software-rendered graphics",
    )
    parser.add_argument(
        "--distro",
        choices=sorted(CLOUD_IMAGE_URL_TEMPLATES),
        default="ubuntu",
        help="ubuntu/debian configure on first boot; alpine, alpine-light and "
        "kali are baked here so the phone boots a finished system; the "
        "desktop flavour is Ubuntu-based",
    )
    parser.add_argument(
        "--release",
        default=None,
        help="distro release (defaults: "
        + ", ".join(f"{name} {version}" for name, version in sorted(DEFAULT_RELEASES.items()))
        + ")",
    )
    parser.add_argument("--disk-size", default="16G", help="virtual disk size (default 16G)")
    parser.add_argument("--username", default="dex")
    parser.add_argument("--password", default="dex")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=PROJECT_ROOT / ".payload-work" / "cloudimg",
        help="where downloaded cloud images are kept",
    )
    arguments = parser.parse_args()

    distro: str = arguments.distro
    release: str = arguments.release or DEFAULT_RELEASES[distro]
    if arguments.flavour in ("desktop", "proot-gnome") and distro != "ubuntu":
        fail(f"the {arguments.flavour} flavour is Ubuntu-based; use --distro ubuntu")
    if distro in ALPINE_DISTROS and arguments.flavour not in ("server", "proot-server"):
        fail("alpine flavours are console images; drop --flavour desktop")

    # Both Alpine flavours start from the identical base image; share the
    # cached download instead of keeping two copies of it.
    cache_key = "alpine" if distro in ALPINE_DISTROS else distro
    cloud_image = arguments.cache_dir / f"{cache_key}-{release}-cloudimg-arm64.img"
    if distro == "kali":
        # Kali publishes an archive rather than a ready qcow2.
        archive = arguments.cache_dir / f"kali-{release}-cloudimg-arm64.tar.xz"
        download_cloud_image(distro, release, archive)
        prepare_kali_base(archive, release, cloud_image)
    else:
        download_cloud_image(distro, release, cloud_image)

    output_dir: Path = arguments.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    if arguments.flavour == "proot-server":
        # A console container, like the GNOME one but without a desktop: it
        # needs no cloud-init seed on the phone, because everything it will
        # ever be configured with is baked in here.
        archive = output_dir / (
            f"linux-on-dex-{distro}-{release}-proot-arm64.rootfs.tar.gz"
        )
        build_proot_server_flavour(cloud_image, archive, distro, arguments)
        write_install_notes(output_dir, arguments.username, arguments.password)
        log(f"done — {archive}")
        return

    if arguments.flavour == "proot-gnome":
        # A rootfs archive, not a disk: it needs no cloud-init seed — its
        # whole configuration is baked in, and PRoot never runs cloud-init.
        archive = output_dir / (
            f"linux-on-dex-{distro}-{release}-proot-gnome-arm64.rootfs.tar.gz"
        )
        build_proot_gnome_flavour(cloud_image, archive, arguments)
        write_install_notes(output_dir, arguments.username, arguments.password)
        log(f"done — {archive}")
        log(f"desktop + ssh sign-in: root/{arguments.password}")
        return

    # e.g. "" (server), "-desktop-xfce", "-desktop-gnome" — so different
    # flavours never overwrite each other's disk.
    flavour_suffix = (
        "" if arguments.flavour == "server"
        else f"-desktop-{arguments.desktop_environment}"
    )
    root_disk = output_dir / (
        f"linux-on-dex-{distro}-{release}{flavour_suffix}-arm64.qcow2"
    )
    # Named after the disk so the app pairs them exactly, even when several
    # flavours sit in the same folder.
    seed_iso = output_dir / f"{root_disk.stem}-seed.iso"

    if arguments.flavour == "desktop":
        build_desktop_flavour(cloud_image, root_disk, arguments)
    elif distro in ALPINE_DISTROS:
        # Alpine flavours are baked on this machine, so the phone boots a
        # finished system with no first-boot installs.
        build_alpine_flavour(
            cloud_image,
            root_disk,
            arguments,
            include_container_stack=(distro == "alpine"),
        )
    elif distro == "kali":
        build_kali_flavour(cloud_image, root_disk, arguments)
    else:
        build_root_disk(cloud_image, root_disk, arguments.disk_size)

    build_seed_iso(seed_iso, arguments.username, arguments.password)
    write_install_notes(output_dir, arguments.username, arguments.password)

    log(f"done — {output_dir}")
    log(f"sign in as {arguments.username}/{arguments.password}")


def build_alpine_flavour(
    cloud_image: Path,
    root_disk: Path,
    arguments,
    include_container_stack: bool,
) -> None:
    """Bakes an Alpine image: the container host, or the light variant."""
    from alpine_image_builder import AlpineBuildRequest, build_alpine_image
    from desktop_image_builder import DesktopBuildError

    variant = "containers" if include_container_stack else "light"
    try:
        build_alpine_image(
            AlpineBuildRequest(
                base_cloud_image=cloud_image,
                output_disk=root_disk,
                work_directory=arguments.cache_dir / f"alpine-build-{variant}",
                disk_size=arguments.disk_size,
                username=arguments.username,
                password=arguments.password,
                include_container_stack=include_container_stack,
            )
        )
    except DesktopBuildError as error:
        fail(str(error))


def build_kali_flavour(cloud_image: Path, root_disk: Path, arguments) -> None:
    """Bakes the console-only Kali server image on this machine."""
    from cloud_image_bake import ImageBakeError
    from kali_image_builder import KaliBuildRequest, build_kali_image

    try:
        build_kali_image(
            KaliBuildRequest(
                base_cloud_image=cloud_image,
                output_disk=root_disk,
                work_directory=arguments.cache_dir / "kali-build",
                disk_size=arguments.disk_size,
                username=arguments.username,
                password=arguments.password,
            )
        )
    except ImageBakeError as error:
        fail(str(error))


def build_proot_server_flavour(
    cloud_image: Path,
    output_archive: Path,
    distro: str,
    arguments,
) -> None:
    """Bakes one console-only container: Ubuntu, Debian, Kali or Alpine."""
    from cloud_image_bake import ImageBakeError
    from proot_server_builder import (
        SERVER_DISTROS,
        ProotServerBuildRequest,
        build_proot_server_rootfs,
    )

    server_distro = SERVER_DISTROS.get(distro)
    if server_distro is None:
        fail(
            f"'{distro}' has no console container recipe; "
            f"choose from {', '.join(sorted(SERVER_DISTROS))}"
        )

    try:
        build_proot_server_rootfs(
            ProotServerBuildRequest(
                distro=server_distro,
                base_cloud_image=cloud_image,
                output_archive=output_archive,
                work_directory=arguments.cache_dir / f"proot-server-{distro}",
            )
        )
    except ImageBakeError as error:
        fail(str(error))


def build_proot_gnome_flavour(cloud_image: Path, output_archive: Path, arguments) -> None:
    """Bakes the Ubuntu GNOME rootfs archive for the PRoot container."""
    from cloud_image_bake import ImageBakeError
    from proot_rootfs_builder import (
        ProotDesktopBuildRequest,
        build_proot_desktop_rootfs,
    )

    try:
        build_proot_desktop_rootfs(
            ProotDesktopBuildRequest(
                base_cloud_image=cloud_image,
                output_archive=output_archive,
                work_directory=arguments.cache_dir / "proot-gnome-build",
                password=arguments.password,
            )
        )
    except ImageBakeError as error:
        fail(str(error))


def build_desktop_flavour(cloud_image: Path, root_disk: Path, arguments) -> None:
    """Installs a desktop into the image on this machine, once."""
    from desktop_image_builder import (
        DesktopBuildError,
        DesktopBuildRequest,
        build_desktop_image,
    )

    try:
        build_desktop_image(
            DesktopBuildRequest(
                base_cloud_image=cloud_image,
                output_disk=root_disk,
                work_directory=arguments.cache_dir / f"desktop-build-{arguments.desktop_environment}",
                disk_size=arguments.disk_size,
                username=arguments.username,
                password=arguments.password,
                desktop_environment=arguments.desktop_environment,
                # GNOME pulls far more than XFCE; give the install room.
                build_timeout_seconds=5400,
            )
        )
    except DesktopBuildError as error:
        fail(str(error))


MIN_PLAUSIBLE_IMAGE_BYTES = 100 * 1024 * 1024
DOWNLOAD_CHUNK_BYTES = 1 << 20
REPORT_EVERY_BYTES = 100 * 1024 * 1024


if __name__ == "__main__":
    main()
