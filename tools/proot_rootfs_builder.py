"""Bakes an Ubuntu GNOME rootfs archive for the app's PRoot engine.

Why a rootfs archive and not a qcow2
------------------------------------
PRoot runs a directory tree through syscall translation at native CPU speed —
no /dev/kvm needed, no TCG emulation tax. That is what makes a GNOME desktop
smooth on stock Samsung firmware, where QEMU has to emulate every
instruction. A block image would need a kernel to mount it; a tar archive is
the natural artifact.

How it is produced
------------------
The same recipe as every baked flavour: boot the Ubuntu cloud image once
under hardware acceleration with a one-shot cloud-init seed that installs
GNOME, TigerVNC, git, OpenSSH and VS Code, writes the PRoot session scripts,
strips everything a kernel-less container cannot use (kernel, firmware,
bootloader, snapd), and finally streams `tar | gzip` of the finished root
filesystem onto an attached scratch disk. The host trims that scratch disk to
the byte count the guest reported and the result IS the archive.

The app pairs this with ProotCommandFactory/dex-desktop: the archive bakes
`/usr/local/bin/dex-desktop`, which the engine starts inside PRoot to bring
up D-Bus, Xvnc (the VNC server the app's Display screen connects to) and the
GNOME Xorg session.
"""

from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

import cloud_image_bake
from cloud_image_bake import BakeBootRequest, ImageBakeError


RELEASE_VERSION = "1.1.11"


@dataclass(frozen=True)
class ProotDesktopBuildRequest:
    """Everything the rootfs bake needs, resolved by the caller."""

    base_cloud_image: Path
    output_archive: Path
    work_directory: Path
    password: str
    build_timeout_seconds: int = 5400


# What "implausibly small" means for the desktop container. Console flavours
# pass their own, far smaller, floor.
MIN_PLAUSIBLE_ARCHIVE_BYTES = 300 << 20


def log(message: str) -> None:
    print(f"[proot-gnome] {message}", flush=True)


def build_proot_desktop_rootfs(request: ProotDesktopBuildRequest) -> Path:
    """Runs the whole bake and returns the finished archive."""
    from build_ready_vm import build_seed_iso_from_documents  # same tools dir
    from cloud_image_bake import bake_cloud_image, prepare_staging_disk

    request.work_directory.mkdir(parents=True, exist_ok=True)
    staging_disk = request.work_directory / "proot-gnome-build.qcow2"
    seed_iso = request.work_directory / "proot-gnome-seed.iso"
    export_disk = request.work_directory / "rootfs-export.img"

    prepare_staging_disk(
        request.base_cloud_image, staging_disk, STAGING_DISK_SIZE, log,
    )
    _create_export_disk(export_disk)
    build_seed_iso_from_documents(
        output_iso=seed_iso,
        user_data=_render_build_user_data(request.password),
        meta_data="instance-id: linux-on-dex-001\nlocal-hostname: dex\n",
    )

    console_log = request.work_directory / "bake-console.log"
    bake_cloud_image(
        BakeBootRequest(
            staging_disk=staging_disk,
            seed_iso=seed_iso,
            work_directory=request.work_directory,
            # The byte-count marker only prints after a successful export,
            # so it is the real success signal — cloud-init's final message
            # prints even when the install failed.
            success_marker=ARCHIVE_SIZE_MARKER,
            memory_mb=4096,
            timeout_seconds=request.build_timeout_seconds,
            extra_raw_disks=(export_disk,),
        ),
        log,
    )

    _require_no_failed_steps(console_log)
    archive_bytes = _read_reported_archive_size(console_log)
    _trim_export_to_archive(export_disk, archive_bytes, request.output_archive)
    _verify_archive_contents(request.output_archive)

    log(f"rootfs archive ready: {request.output_archive.name} "
        f"({request.output_archive.stat().st_size >> 20} MiB)")
    return request.output_archive


# ---- Steps -------------------------------------------------------------------


def _create_export_disk(export_disk: Path) -> None:
    """A sparse raw disk the guest streams the archive onto (/dev/vdc)."""
    export_disk.unlink(missing_ok=True)
    with open(export_disk, "wb") as scratch:
        scratch.truncate(EXPORT_DISK_BYTES)


def _require_no_failed_steps(console_log: Path) -> None:
    """A bake with a failed step must never ship a quietly incomplete image."""
    console_text = console_log.read_text(errors="replace") if console_log.exists() else ""
    failed_steps = sorted(
        {line.split(FAILED_STEP_MARKER, 1)[1].strip()
         for line in console_text.splitlines() if FAILED_STEP_MARKER in line}
    )
    if failed_steps:
        raise ImageBakeError(
            "these bake steps failed inside the guest: "
            + "; ".join(failed_steps)
            + f". Console log: {console_log}"
        )


def _read_reported_archive_size(
    console_log: Path,
    minimum_bytes: int = MIN_PLAUSIBLE_ARCHIVE_BYTES,
) -> int:
    """The guest prints the exact gzip byte count; nothing else knows it.

    [minimum_bytes] is what "implausibly small" means for this flavour. A
    console container is legitimately a fifth the size of the desktop one, so
    a single global floor would reject a perfectly good image.
    """
    console_text = console_log.read_text(errors="replace") if console_log.exists() else ""
    match = None
    for match in re.finditer(rf"{ARCHIVE_SIZE_MARKER}(\d+)", console_text):
        pass  # keep the last occurrence: earlier boots may linger in the log
    if match is None:
        raise ImageBakeError(
            f"the guest never reported '{ARCHIVE_SIZE_MARKER}<bytes>' on its console — "
            f"the rootfs export step did not run. Console log: {console_log}"
        )
    reported = int(match.group(1))
    if reported < minimum_bytes:
        raise ImageBakeError(
            f"the exported rootfs is implausibly small ({reported} bytes); "
            f"check the bake console: {console_log}"
        )
    if reported > EXPORT_DISK_BYTES:
        raise ImageBakeError(
            f"the exported rootfs ({reported} bytes) overflowed the "
            f"{EXPORT_DISK_BYTES >> 30} GiB export disk — it was truncated. "
            "Raise EXPORT_DISK_BYTES and rebuild."
        )
    return reported


def _trim_export_to_archive(export_disk: Path, archive_bytes: int, output: Path) -> None:
    """The first [archive_bytes] of the export disk ARE the tar.gz."""
    with open(export_disk, "r+b") as scratch:
        scratch.truncate(archive_bytes)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    export_disk.rename(output)


def _verify_archive_contents(archive: Path) -> None:
    """Lists the archive on the host: a truncated stream fails here, not on the phone."""
    log("verifying the archive (full listing)")
    listing = subprocess.run(
        ["tar", "-tzf", str(archive)],
        capture_output=True,
        text=True,
    )
    if listing.returncode != 0:
        raise ImageBakeError(
            f"the produced archive does not list cleanly: {listing.stderr.strip()[:500]}"
        )
    entries = listing.stdout.splitlines()
    for required in REQUIRED_ARCHIVE_ENTRIES:
        if not any(line.rstrip("/").endswith(required) for line in entries):
            raise ImageBakeError(
                f"required file missing from the archive: {required} — "
                "the desktop would not start on the phone"
            )
    log(f"archive verified: {len(entries)} entries, all required files present")


# ---- The guest-side recipe ---------------------------------------------------


def _render_build_user_data(password: str) -> str:
    """cloud-config that assembles the rootfs and streams it out."""
    supervisor = _indent_for_write_files(DESKTOP_SUPERVISOR_SCRIPT)
    code_wrapper = _indent_for_write_files(VSCODE_WRAPPER_SCRIPT)
    firefox_wrapper = _indent_for_write_files(FIREFOX_WRAPPER_SCRIPT)
    fps_benchmark = _indent_for_write_files(FPS_BENCHMARK_SCRIPT)
    process_budget = _indent_for_write_files(PROCESS_BUDGET_SCRIPT)
    group_namer = _indent_for_write_files(GROUP_NAMER_SCRIPT)
    apt_config = _indent_for_write_files(APT_CONFIG)
    sshd_config = _indent_for_write_files(SSHD_CONFIG)
    dconf_defaults = _indent_for_write_files(DCONF_DEFAULTS)
    colour_profile = _indent_for_write_files(COLOUR_PROFILE_SCRIPT)
    console_size_profile = _indent_for_write_files(CONSOLE_SIZE_PROFILE_SCRIPT)
    desktop_profile = _indent_for_write_files(DESKTOP_PROFILE_SCRIPT)
    package_list = "\n".join(f"  - {name}" for name in GUEST_PACKAGES)
    return f"""#cloud-config
hostname: dex
manage_etc_hosts: true

# Everything in a PRoot container runs as (fake) root; the password is for
# the baked sshd on 127.0.0.1:8022.
chpasswd:
  expire: false
  users:
    - {{name: root, password: {password}, type: text}}
disable_root: false
ssh_pwauth: true
datasource_list: [NoCloud, None]

growpart:
  mode: auto
  devices: ["/"]
resize_rootfs: true

package_update: true
packages:
{package_list}

bootcmd:
  # Snap seeding would fight the desktop install for the whole bake.
  - [sh, -c, "systemctl mask --now snapd.service snapd.socket snapd.seeded.service 2>/dev/null || true"]

write_files:
  # The app starts this inside PRoot; it owns the graphical session.
  - path: /usr/local/bin/dex-desktop
    permissions: "0755"
    content: |
{supervisor}
  # PRoot offers no sandboxing namespaces and no GPU; VS Code needs both
  # switched off. Staged here and installed to /usr/local/bin/code AFTER
  # the deb: VS Code's postinst deletes any legacy /usr/local/bin/code it
  # finds, so writing the final path first is undone during install.
  - path: /opt/linux-on-dex/code-wrapper
    permissions: "0755"
    content: |
{code_wrapper}
  # Firefox cannot use Linux namespaces from PRoot. Keep its launcher in one
  # reviewable place instead of weakening the package or desktop file.
  - path: /opt/linux-on-dex/firefox-wrapper
    permissions: "0755"
    content: |
{firefox_wrapper}
  # Gives Android's supplementary group ids names inside the container.
  - path: /usr/local/bin/dex-name-groups
    permissions: "0755"
    content: |
{group_namer}
  # Runs before anything else can complain about unnamed groups.
  - path: /etc/profile.d/05-linux-on-dex-groups.sh
    permissions: "0644"
    content: |
      [ -x /usr/local/bin/dex-name-groups ] && /usr/local/bin/dex-name-groups 2>/dev/null
      :
  # Reports the container's use of Android's process budget.
  - path: /usr/local/bin/dex-processes
    permissions: "0755"
    content: |
{process_budget}
  # Measures what the desktop can actually draw, on this device.
  - path: /usr/local/bin/dex-fps
    permissions: "0755"
    content: |
{fps_benchmark}
  # Makes apt work inside the container. Without this, the very first
  # `apt update` fails.
  - path: /etc/apt/apt.conf.d/99-linux-on-dex
    permissions: "0644"
    content: |
{apt_config}
  # Written before anything is installed, so documentation and translations
  # for every language are never unpacked in the first place — cheaper than
  # deleting them afterwards, and it keeps the image lean when the user
  # installs their own packages later.
  - path: /etc/dpkg/dpkg.cfg.d/01-linux-on-dex-slim
    permissions: "0644"
    content: |
      path-exclude=/usr/share/doc/*
      path-exclude=/usr/share/man/*
      path-exclude=/usr/share/info/*
      path-exclude=/usr/share/groff/*
      path-exclude=/usr/share/lintian/*
      path-exclude=/usr/share/locale/*
      path-include=/usr/share/locale/en*
      path-include=/usr/share/locale/locale.alias
  - path: /etc/ssh/sshd_config.d/10-linux-on-dex.conf
    permissions: "0644"
    content: |
{sshd_config}
  - path: /etc/linux-on-dex-release
    permissions: "0644"
    content: |
      Linux on DeX {RELEASE_VERSION} Ubuntu 24.04 GNOME Shell
  - path: /etc/dconf/profile/user
    permissions: "0644"
    content: |
      user-db:user
      system-db:local
  - path: /etc/dconf/db/local.d/00-linux-on-dex
    permissions: "0644"
    content: |
{dconf_defaults}
  - path: /etc/profile.d/10-linux-on-dex-colour.sh
    permissions: "0644"
    content: |
{colour_profile}
  # Keeps the shell's idea of the window the same as the real one.
  - path: /etc/profile.d/15-linux-on-dex-console.sh
    permissions: "0644"
    content: |
{console_size_profile}
  # Interactive PRoot shells should reach the desktop's display and use the
  # same software-rendering choices as the session itself.
  - path: /etc/profile.d/20-linux-on-dex-desktop.sh
    permissions: "0644"
    content: |
{desktop_profile}

runcmd:
  # Every entry below runs in its own subshell: cloud-init concatenates
  # runcmd into ONE script, so a bare `set -e` here would silently abort
  # all later entries — including the export that produces the artifact.
  # Firefox: Mozilla's signed native arm64 package. The key fingerprint is
  # checked before APT is allowed to trust it.
  - |
    (
      set -e
      export DEBIAN_FRONTEND=noninteractive
      install -d -m 0755 /etc/apt/keyrings
      curl -fsSL 'https://packages.mozilla.org/apt/repo-signing-key.gpg' -o /etc/apt/keyrings/packages.mozilla.org.asc
      fingerprint="$(gpg --batch --show-keys --with-colons /etc/apt/keyrings/packages.mozilla.org.asc | awk -F: '$1 == "fpr" {{ print $10; exit }}')"
      test "$fingerprint" = '35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3'
      printf '%s\\n' 'deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main' > /etc/apt/sources.list.d/mozilla.list
      printf '%s\\n' 'Package: *' 'Pin: origin packages.mozilla.org' 'Pin-Priority: 1000' > /etc/apt/preferences.d/mozilla
      apt-get update
      apt-get install -y firefox
      install -m 0755 /opt/linux-on-dex/firefox-wrapper /usr/local/bin/firefox
      test -x /usr/lib/firefox/firefox
      test -x /usr/local/bin/firefox
    ) || echo 'DEX_BAKE_STEP_FAILED: firefox install'
  # VS Code: Microsoft's arm64 build, installed with full dependency
  # resolution. Point the desktop launchers at the PRoot-safe wrapper.
  - |
    (
      set -e
      export DEBIAN_FRONTEND=noninteractive
      curl -fsSL -o /tmp/code.deb 'https://update.code.visualstudio.com/latest/linux-deb-arm64/stable'
      apt-get install -y /tmp/code.deb
      rm -f /tmp/code.deb
      install -m 0755 /opt/linux-on-dex/code-wrapper /usr/local/bin/code
      test -x /usr/local/bin/code
      sed -i 's|Exec=/usr/share/code/code|Exec=/usr/local/bin/code|g' /usr/share/applications/code.desktop /usr/share/applications/code-url-handler.desktop
    ) || echo 'DEX_BAKE_STEP_FAILED: vscode install'
  - [sh, -c, "dconf update || echo 'DEX_BAKE_STEP_FAILED: dconf update'"]
  # THE PROCESS BUDGET — the single hardest constraint on this image.
  #
  # Android 12+ kills an app's forked children once they pass
  # max_phantom_processes (32 by default) and does not stop at one: it
  # trims the whole set, which takes the X server and the session with it.
  # A stock Ubuntu GNOME session starts well over forty processes (sixteen
  # settings-daemon plugins, five ibus processes, the Evolution factories,
  # Online Accounts, PackageKit, upower, portals, gvfs, at-spi), so it
  # cannot survive here — and every one of them also costs start-up time
  # and CPU that the desktop should be spending on frames.
  #
  # The session therefore keeps only X, D-Bus, sshd, GNOME Shell/Mutter,
  # XSettings and dconf. GNOME Shell is launched directly so an optional
  # session component cannot replace the desktop with the recovery screen.
  - |
    mkdir -p /usr/share/linux-on-dex/disabled-autostart
    for entry in /etc/xdg/autostart/*.desktop; do
      [ -e "$entry" ] || continue
      case "$(basename "$entry")" in
        org.gnome.SettingsDaemon.XSettings.desktop) ;;
        *) mv "$entry" /usr/share/linux-on-dex/disabled-autostart/ ;;
      esac
    done
    for service in org.gnome.OnlineAccounts org.gnome.Identity \
                   org.freedesktop.PackageKit org.freedesktop.UPower \
                   org.freedesktop.portal.Desktop org.freedesktop.portal.Documents \
                   org.freedesktop.impl.portal.desktop.gtk \
                   org.gnome.evolution.dataserver.Sources5 \
                   org.gnome.evolution.dataserver.Calendar8 \
                   org.gnome.evolution.dataserver.AddressBook10 \
                   org.gnome.evolution.dataserver.Subprocess.Backend \
                   org.freedesktop.IBus org.freedesktop.IBus.Portal \
                   org.gnome.Rygel1 org.gnome.RemoteDesktop \
                   org.a11y.Bus org.a11y.atspi.Registry; do
      rm -f "/usr/share/dbus-1/services/$service.service" \
            "/usr/share/dbus-1/system-services/$service.service"
    done
    # The gvfs family is the largest remaining group: seven daemons that
    # GNOME Shell and Nautilus start on demand over D-Bus, for removable
    # media, MTP cameras, phones and online accounts — none of which exist in
    # a container. Removing their autostart entries was not enough, because
    # D-Bus activation starts them anyway; the activation files have to go.
    # This matters far beyond tidiness: every one of them counts against the
    # phantom-process cap, and running out of that budget kills the desktop
    # outright. Leaving them in meant an `apt update` in a terminal, which
    # spawns processes of its own, could take the session down with it.
    rm -f /usr/share/dbus-1/services/org.gtk.vfs.*.service \
          /usr/share/dbus-1/services/org.gtk.Private.*.service 2>/dev/null || true
    # Nautilus declares gvfs as a package dependency. Purging it makes apt
    # remove the file manager, so keep the libraries but disable activation.
    # Keep the libraries but leave activation disabled above, so the helpers
    # cannot consume Android's process budget.
    # The last few daemons with nothing to do here, each costing a slot in
    # the process budget: a supplicant with no radio, an authority that
    # cannot escalate what is already fake root, a permission store for
    # portals that were removed above, and a calendar server with no
    # calendars.
    for service in org.freedesktop.PolicyKit1 \
                   org.freedesktop.impl.portal.PermissionStore; do
      rm -f "/usr/share/dbus-1/services/$service.service" \
            "/usr/share/dbus-1/system-services/$service.service"
    done
    apt-get purge -y wpasupplicant 2>/dev/null || true
    apt-get autoremove --purge -y 2>/dev/null || true
    for essential in /usr/bin/gnome-shell /usr/bin/Xtigervnc; do
      test -x "$essential" || echo "DEX_BAKE_STEP_FAILED: trimming removed $essential"
    done
    # ibus is an input-method framework with nothing to do here, and it
    # alone accounts for five processes.
    export DEBIAN_FRONTEND=noninteractive
    apt-get purge -y ibus packagekit rygel gnome-remote-desktop 2>/dev/null || true
    apt-get autoremove --purge -y 2>/dev/null || true
    # Purging must never take the desktop with it.
    for essential in /usr/bin/gnome-shell /usr/bin/Xtigervnc /usr/local/bin/code /usr/local/bin/firefox; do
      test -x "$essential" || echo "DEX_BAKE_STEP_FAILED: purge removed $essential"
    done
    for removed in /usr/bin/gnome-flashback /usr/bin/gnome-panel /usr/bin/openbox; do
      test ! -e "$removed" || echo "DEX_BAKE_STEP_FAILED: legacy desktop remains: $removed"
    done
  # A container has no kernel to keep: everything below is dead weight the
  # phone would extract, store and never execute.
  - |
    export DEBIAN_FRONTEND=noninteractive
    apt-get purge -y 'linux-image-*' 'linux-headers-*' 'linux-modules-*' 'linux-virtual*' 'linux-generic*' 'linux-firmware*' 'grub-efi*' grub-common grub2-common shim-signed flash-kernel snapd 2>/dev/null || true
    apt-get autoremove --purge -y 2>/dev/null || true
  # Content the phone would extract, store and never open. Removed by
  # category rather than by guessing at individual packages, so nothing the
  # desktop actually links against can go missing.
  - |
    set -u
    # Documentation, manuals and examples: ~120 MB that no phone reads.
    rm -rf /usr/share/doc /usr/share/man /usr/share/info /usr/share/doc-base \
           /usr/share/lintian /usr/share/help /usr/share/gtk-doc \
           /usr/share/devhelp /usr/share/bug 2>/dev/null || true
    # Translations, for every language and every desktop file. English stays.
    find /usr/share/locale -mindepth 1 -maxdepth 1 -type d \
         ! -name 'en*' ! -name 'C*' -exec rm -rf {{}} + 2>/dev/null || true
    rm -rf /usr/share/locale-langpack /usr/share/i18n/locales 2>/dev/null || true
    # Wallpapers: one is configured, the rest are ~40 MB of alternatives.
    find /usr/share/backgrounds -type f ! -name 'warty-final-ubuntu.png' \
         -delete 2>/dev/null || true
    # Icon themes the session never selects, and their oversized variants.
    rm -rf /usr/share/icons/Adwaita/512x512 /usr/share/icons/Adwaita/384x384 \
           /usr/share/icons/Adwaita/256x256 /usr/share/icons/HighContrast \
           2>/dev/null || true
    # Fonts for scripts this image cannot type: CJK and Indic alone are large.
    rm -rf /usr/share/fonts/truetype/kacst /usr/share/fonts/truetype/lohit* \
           /usr/share/fonts/truetype/samyak* /usr/share/fonts/truetype/Gargi \
           /usr/share/fonts/truetype/Gubbi /usr/share/fonts/truetype/Navilu \
           /usr/share/fonts/truetype/pagul /usr/share/fonts/truetype/teluguvijayam \
           /usr/share/fonts/truetype/tlwg /usr/share/fonts/truetype/abyssinica \
           /usr/share/fonts/truetype/sinhala 2>/dev/null || true
    # Kernel headers: 90 MB of interfaces to a kernel this container does not
    # have and cannot build modules for.
    rm -rf /usr/src/* /lib/modules /usr/share/linux-headers-* 2>/dev/null || true
    # Static libraries: nothing in the image compiles
    # against them, and a user who needs them can apt install them.
    find /usr/lib /usr/lib/aarch64-linux-gnu -maxdepth 1 -name '*.a' -delete 2>/dev/null || true
  # Per-device state must not be baked: host keys and machine-id are
  # regenerated by dex-desktop on the first start of each phone.
  - |
    apt-get clean
    rm -rf /var/lib/apt/lists/* /root/.cache /tmp/* 2>/dev/null || true
    rm -f /etc/ssh/ssh_host_* /etc/machine-id /var/lib/dbus/machine-id
  # Stream the finished filesystem onto the scratch disk and report the
  # exact byte count — the host trims the disk to it. tee also keeps the
  # pipeline status independent of tar's live-filesystem warnings.
  - |
    [ -b /dev/vdc ] || {{ echo 'DEX_EXPORT_DISK_MISSING'; exit 1; }}
    cd /
    # Empty the logs but KEEP the directory tree. Excluding ./var/log from the
    # archive instead looks equivalent and is not: apt refuses to install
    # anything when /var/log/apt is missing ("E: Directory '/var/log/apt/'
    # missing"), and dpkg needs its own directories to exist as well. Only
    # file *contents* are disposable here.
    find /var/log -type f -delete 2>/dev/null || true
    tar --numeric-owner --format=gnu -cpf - \\
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

final_message: "{cloud_image_bake.BAKE_COMPLETE_MARKER}"
"""


def _indent_for_write_files(body: str) -> str:
    """Indents a file body to sit under a write_files `content: |` block."""
    return "\n".join(f"      {line}".rstrip() for line in body.strip("\n").splitlines())


# What the desktop needs and nothing more. Recommends stay on: that is how
# ubuntu-session, the Yaru theme and the session's D-Bus services arrive.
GUEST_PACKAGES = (
    "gnome-shell",
    "gnome-session",
    "gnome-settings-daemon",
    "gnome-terminal",
    "nautilus",
    "gnome-control-center",
    "gnome-text-editor",
    "ubuntu-settings",
    "ubuntu-wallpapers",
    "fonts-ubuntu",
    "dbus-x11",
    "tigervnc-standalone-server",
    "git",
    "openssh-server",
    "openssh-client",
    "curl",
    "ca-certificates",
    "gnupg",
    # glxgears and glxinfo, so desktop smoothness can be measured on the
    # actual phone instead of estimated (see /usr/local/bin/dex-fps).
    "mesa-utils",
    "x11-utils",
    "xdotool",
)

# Started by the app inside PRoot; its lifetime IS the session's lifetime.
DESKTOP_SUPERVISOR_SCRIPT = r"""#!/bin/sh
# Linux on DeX desktop supervisor. The app runs this as PRoot's root
# process; when it exits the whole session is taken down (--kill-on-exit).
# The app provides: DEX_RESOLUTION (e.g. 1280x800), DEX_VNC_PORT (e.g. 5901).
set -u

RESOLUTION="${DEX_RESOLUTION:-1280x800}"
VNC_PORT="${DEX_VNC_PORT:-5901}"
MAX_FRAME_RATE=240

log() { echo "[dex-desktop] $*"; }

export HOME=/root USER=root LOGNAME=root SHELL=/bin/bash
export LANG=C.UTF-8
/usr/local/bin/dex-name-groups 2>/dev/null || true
export DISPLAY=:1
export XDG_RUNTIME_DIR=/run/user/0
export XDG_SESSION_TYPE=x11 XDG_SESSION_CLASS=user
export XDG_CURRENT_DESKTOP=GNOME
export XDG_SESSION_DESKTOP=gnome
export XDG_SESSION_MODE=user
export GDMSESSION=gnome-xorg
# Use the native virgl bridge after the app has validated it with EGL. Keep a
# predictable llvmpipe fallback for devices whose Android EGL cannot start.
if [ "${DEX_GPU_BRIDGE:-0}" = 1 ]; then
    export LIBGL_ALWAYS_SOFTWARE=1
    export GALLIUM_DRIVER=virpipe
    export MESA_GL_VERSION_OVERRIDE=3.3
    export MESA_GLES_VERSION_OVERRIDE=3.1
    log "native Android virgl bridge enabled"
else
    export LIBGL_ALWAYS_SOFTWARE=1
    log "native GPU bridge unavailable; using llvmpipe"
fi
export GSK_RENDERER=cairo
# Every avoidable helper process matters: Android kills the whole tree once
# an app's children pass its phantom-process cap. These three switch off
# the accessibility bus, the gvfs FUSE daemon and GTK's own a11y bridge.
export NO_AT_BRIDGE=1
export GTK_A11Y=none
export GVFS_DISABLE_FUSE=1

# /run and /tmp hold nothing across a boot, so the skeleton is made fresh.
mkdir -p "$XDG_RUNTIME_DIR" /run/dbus /run/sshd /tmp/.X11-unix /var/lib/dbus
chmod 700 "$XDG_RUNTIME_DIR"
chmod 1777 /tmp /tmp/.X11-unix
rm -f /run/dbus/pid /tmp/.X1-lock /tmp/.X11-unix/X1

# Directories apt and dpkg require to exist before they will do anything.
# They are normally shipped by the packages themselves, so a missing one is a
# packaging accident rather than something the user could fix — and the error
# it produces ("E: Directory '/var/log/apt/' missing") gives no hint that
# creating a directory is all that is needed.
mkdir -p /var/log/apt /var/log/dpkg /var/cache/apt/archives/partial \
         /var/lib/apt/lists/partial /var/lib/dpkg/updates /var/lib/dpkg/info

# Per-device identity, generated once on the first start.
dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true
dbus-uuidgen --ensure 2>/dev/null || true
[ -f /etc/ssh/ssh_host_ed25519_key ] || ssh-keygen -A >/dev/null 2>&1 || true

# System bus first: polkit and friends get activated over it on demand.
dbus-daemon --system --fork 2>/dev/null || log "system D-Bus failed (continuing)"

if /usr/sbin/sshd 2>/dev/null; then
    log "sshd listening on 127.0.0.1:8022 (root / your image password)"
else
    log "sshd failed to start (continuing without it)"
fi

log "starting Xvnc :1 at $RESOLUTION on port $VNC_PORT"
# -FrameRate 240: the requested per-client ceiling. Android still presents at
# the physical display refresh rate (120 Hz on the target S23 Ultra).
# -AlwaysShared: a newly recreated DeX window must not evict the viewer that
#   is still unwinding its blocking socket read.
#
# Deliberately not tuned further: TigerVNC 1.13's remaining update options
# (CompareFB in particular) trade bandwidth against CPU for a *network*
# client. Over loopback that trade is not obviously either way, and this
# server rejects options it does not know by refusing to start at all — so
# only flags verified against its own help output are used here.
Xtigervnc :1 -geometry "$RESOLUTION" -depth 24 \
    -rfbport "$VNC_PORT" -localhost -SecurityTypes None -AlwaysShared \
    -FrameRate "$MAX_FRAME_RATE" \
    -desktop "Linux on DeX" &
XVNC_PID=$!

# X must accept clients before the session may start.
tries=0
while [ ! -S /tmp/.X11-unix/X1 ]; do
    tries=$((tries + 1))
    if [ "$tries" -gt 100 ]; then log "Xvnc never created its socket"; exit 1; fi
    kill -0 "$XVNC_PID" 2>/dev/null || { log "Xvnc exited early"; exit 1; }
    sleep 0.1
done

log "starting the native GNOME Shell X11 session"
# GNOME Shell is the real desktop and Mutter is its window manager. Launch it
# directly: gnome-session's recovery screen treats any optional helper that
# cannot use logind/systemd inside PRoot as a fatal desktop failure.
exec dbus-run-session -- sh -c '\
    /usr/libexec/gsd-xsettings >/tmp/gsd-xsettings.log 2>&1 & \
    exec gnome-shell --x11 >/tmp/gnome-shell.log 2>&1'
"""

VSCODE_WRAPPER_SCRIPT = r"""#!/bin/sh
# VS Code inside PRoot: no user namespaces (so no Chromium sandbox), no GPU,
# and no keyring daemon worth waiting for.
#
# The three --disable-features flags are not cosmetic. Android kills an
# app's whole forked tree once it passes the phantom-process cap, and
# Electron's crash reporter, GPU-process fallbacks and update checker each
# add processes for work that cannot succeed in a container anyway.
exec /usr/share/code/code \
    --no-sandbox \
    --disable-gpu \
    --disable-dev-shm-usage \
    --disable-crash-reporter \
    --disable-features=CalculateNativeWinOcclusion,UseChromeOSDirectVideoDecoder \
    --password-store=basic \
    "$@"
"""

FIREFOX_WRAPPER_SCRIPT = r"""#!/bin/sh
# Firefox's Linux content sandbox requires namespaces that PRoot cannot
# create. Keep the exception scoped to Firefox inside this single-user
# container; normal TLS and site isolation remain enabled.
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1
export MOZ_ENABLE_WAYLAND=0
exec /usr/lib/firefox/firefox --no-remote "$@"
"""

# Apps may not bind ports below 1024, and the phone's own network must not
# be exposed by default; localhost matches the VNC trust model.
# Everything apt needs in order to work under syscall translation.
#
# Sandbox::User: apt normally drops to the unprivileged `_apt` user to fetch
#   packages. PRoot only pretends to be root — it cannot actually hand file
#   ownership to another user — so `_apt` ends up unable to read the very
#   partial files it just wrote, and `apt update` fails on every index with
#   permission errors. Fetching as root is how every PRoot distribution
#   solves this, and it costs nothing here: the container is single-user.
# _apt sandbox off + no signature-check weakening: package signatures are
#   still verified exactly as usual. Only the privilege drop is skipped.
# Pipeline-Depth 0: HTTP pipelining goes wrong when reads are relayed through
#   a tracer, producing "Hash Sum mismatch" on unlucky mirrors.
# Retries + timeout: a phone changes network constantly; a single dropped
#   connection should not fail the whole update.
# Languages none: skips downloading translated package descriptions, which
#   are a large part of an `apt update` and of no use here.
# Use-Pty 0: dpkg's progress redraw assumes a terminal it does not have when
#   apt is driven from the app's own console.
#
# Deliberately NOT set: Install-Recommends. This file is written before the
# desktop is installed, and Ubuntu ships its session, theme and fonts as
# recommends — turning them off here would bake a broken GNOME. It would also
# silently change what `apt install` does for the user later.
# Queue-Mode access: apt starts one fetch process per *host* by default. One
#   per access method instead keeps its process count flat no matter how many
#   mirrors a sources list names — which matters because Android kills the
#   whole container once the app's process count passes its phantom-process
#   cap, and an apt run inside a live desktop session is exactly what pushes
#   it over.
APT_CONFIG = """APT::Sandbox::User "root";
Acquire::Queue-Mode "access";
Acquire::http::Pipeline-Depth "0";
Acquire::Retries "3";
Acquire::http::Timeout "30";
Acquire::https::Timeout "30";
Acquire::Languages "none";
Dpkg::Use-Pty "0";
"""

GROUP_NAMER_SCRIPT = r"""#!/bin/sh
# Gives Android's supplementary group ids a name inside the container.
#
# Android runs the app — and therefore the whole container — as a member of
# several groups (network access, external storage, the app's own id). Those
# ids exist in no Ubuntu /etc/group, so any tool that maps the process's
# groups to names prints "groups: cannot find name for group ID 3003" once per
# id, and a login shell greets the user with a wall of them.
#
# The ids are not fixed: two of them are derived from the app's uid, which
# differs per device and changes on reinstall. So they are named on the fly at
# first login rather than baked into the image.
set -u

GROUP_FILE=/etc/group
LOCK_FILE=/etc/.linux-on-dex-groups.lock
[ -w "$GROUP_FILE" ] || exit 0

(
    flock 9
    for group_id in $(id -G 2>/dev/null); do
        case "$group_id" in *[!0-9]*|'') continue ;; esac
        # Already named — by Ubuntu itself or by an earlier run.
        if cut -d: -f3 "$GROUP_FILE" | grep -qx "$group_id"; then
            continue
        fi
        printf 'android%s:x:%s:\n' "$group_id" "$group_id" >> "$GROUP_FILE"
    done
) 9>>"$LOCK_FILE"
"""

PROCESS_BUDGET_SCRIPT = r"""#!/bin/sh
# Reports how much of Android's process budget the container is using.
#
# Android 12+ kills an app's forked children as a group once they pass
# max_phantom_processes (32 by default), which takes the X server and the
# whole desktop with them. That makes the process count a hard budget rather
# than a curiosity: an `apt` run or a build inside a live desktop session is
# exactly what tips it over, and the failure looks like an unrelated crash.
set -u

BUDGET=32
# Subtract this tool's own pipeline (the shell, ps and wc), which would
# otherwise make the desktop look three processes heavier than it is.
MEASUREMENT_OVERHEAD=3
counted=$(ps -eo pid= 2>/dev/null | wc -l | tr -d ' ')
running=$((counted - MEASUREMENT_OVERHEAD))

echo "container processes: $running of about $BUDGET Android allows"
echo
ps -eo comm= 2>/dev/null | sort | uniq -c | sort -rn | head -15

echo
if [ "$running" -ge $((BUDGET - 6)) ]; then
    echo "WARNING: close to the limit. Starting anything heavy now (apt, a"
    echo "build, a browser) may make Android kill the whole desktop."
    echo "The limit can be lifted over adb, no root needed:"
    echo "  adb shell settings put global settings_enable_monitor_phantom_procs false"
else
    echo "Headroom looks fine."
fi
"""

FPS_BENCHMARK_SCRIPT = r"""#!/bin/sh
# Measures what this desktop can actually draw, from inside the container.
#
# Run it from a terminal on the desktop, or from the app's own terminal —
# it finds the running session's display and message bus itself, so it works
# either way. glxgears prints a frame rate every five seconds; the renderer
# line above it says what is doing the drawing (llvmpipe: the CPU, because
# no Android device gives an app access to the GPU from a container).
set -u

SECONDS_TO_RUN="${1:-20}"

SHELL_PID=$(pgrep -x gnome-shell | head -1)
if [ -n "$SHELL_PID" ] && [ -r "/proc/$SHELL_PID/environ" ]; then
    BUS_LINE=$(tr '\0' '\n' < "/proc/$SHELL_PID/environ" | grep '^DBUS_SESSION_BUS_ADDRESS=' || true)
    [ -n "$BUS_LINE" ] && export "$BUS_LINE"
fi
export DISPLAY="${DISPLAY:-:1}"

if ! command -v glxgears >/dev/null; then
    echo "glxgears is not installed (expected from mesa-utils)"
    exit 1
fi

echo "display:  $DISPLAY"
glxinfo -B 2>/dev/null | grep -E 'OpenGL renderer|OpenGL version' || \
    echo "renderer: unknown (glxinfo unavailable)"
echo "running glxgears for ${SECONDS_TO_RUN}s — each line is a measured frame rate"
echo
# vblank_mode=0 stops the driver capping at the display's refresh rate, so
# the number reflects what the machine can draw rather than what it waits for.
vblank_mode=0 timeout "$SECONDS_TO_RUN" glxgears -geometry 800x600 2>&1 |
    grep --line-buffered -E 'frames in|FPS'
echo
echo "done — the last figures are the steady-state frame rate"
"""

SSHD_CONFIG = """Port 8022
ListenAddress 127.0.0.1
PermitRootLogin yes
PasswordAuthentication yes
UsePAM no
"""

# The same per-frame savings the qcow2 GNOME image ships, plus the lockdown
# keys: without logind there is no way back from a locked screen.
DCONF_DEFAULTS = """[org/gnome/desktop/interface]
enable-animations=false
color-scheme='prefer-dark'
cursor-blink=false
[org/gnome/desktop/session]
idle-delay=uint32 0
[org/gnome/desktop/lockdown]
disable-lock-screen=true
[org/gnome/desktop/screensaver]
lock-enabled=false
[org/gnome/desktop/background]
picture-uri='file:///usr/share/backgrounds/warty-final-ubuntu.png'
picture-uri-dark='file:///usr/share/backgrounds/warty-final-ubuntu.png'
picture-options='zoom'
primary-color='#242430'
[org/gnome/desktop/search-providers]
disable-external=true
[org/gnome/mutter]
experimental-features=@as []
[org/gnome/shell]
disable-user-extensions=false
favorite-apps=['firefox.desktop', 'code.desktop', 'org.gnome.Terminal.desktop', 'org.gnome.Nautilus.desktop']
"""

# Keeps the shell's window size matching the app's terminal.
#
# A pty created inside the container starts at the default 80x24 and has no
# way to learn otherwise: nothing here delivers SIGWINCH, because the app is
# not a terminal emulator attached to a local tty — it reads the shell's
# output over a pipe. A shell that believes the window is 80 columns wide
# while it is really 110 wraps its own redraws at the wrong column, so
# editing a long command line overwrites earlier text and the screen fills
# with fragments of previous lines. That is the corruption users see.
#
# The size is asked for rather than guessed: printing a cursor-position
# request after moving the cursor as far right and down as it will go makes
# the terminal reply with the real dimensions, and the app's parser answers
# that request. `stty` then applies them.
#
# Run once at login and again before every prompt, because the window can be
# resized, popped into its own DeX window or rotated at any moment; the next
# prompt heals the size before the next command draws anything. The cost is
# one instant round trip on a connected console, and the probe is skipped
# entirely when the shell is not interactive.
CONSOLE_SIZE_PROFILE_SCRIPT = r"""[ -n "$BASH_VERSION" ] || return 0
case "$-" in *i*) ;; *) return 0 ;; esac

fix_console() {
    [ -t 0 ] && [ -t 1 ] || return 0
    local saved rows cols discard
    saved=$(stty -g 2>/dev/null) || return 0
    stty raw -echo min 0 time 5 2>/dev/null || return 0
    printf '\0337\033[999;999H\033[6n\0338' > /dev/tty
    IFS='[;R' read -r -t 1 -d R discard rows cols < /dev/tty 2>/dev/null
    stty "$saved" 2>/dev/null
    if [ -n "$rows" ] && [ -n "$cols" ] && [ "$rows" -gt 0 ] 2>/dev/null; then
        stty rows "$rows" cols "$cols" 2>/dev/null
    fi
}

fix_console
case "${PROMPT_COMMAND:-}" in
    *fix_console*) ;;
    *) PROMPT_COMMAND="fix_console${PROMPT_COMMAND:+;$PROMPT_COMMAND}" ;;
esac
"""

COLOUR_PROFILE_SCRIPT = """case "${TERM:-}" in
    ""|dumb|unknown|vt100|vt102|vt220|linux) TERM=xterm-256color ;;
esac
export TERM
export COLORTERM=truecolor
"""

DESKTOP_PROFILE_SCRIPT = """export DISPLAY="${DISPLAY:-:1}"
export LIBGL_ALWAYS_SOFTWARE=1
export GSK_RENDERER=cairo
"""

ARCHIVE_SIZE_MARKER = "DEX_ROOTFS_GZ_BYTES="
FAILED_STEP_MARKER = "DEX_BAKE_STEP_FAILED:"
STAGING_DISK_SIZE = "16G"
EXPORT_DISK_BYTES = 12 << 30

REQUIRED_ARCHIVE_ENTRIES = (
    "usr/local/bin/dex-desktop",
    "usr/local/bin/code",
    "usr/local/bin/firefox",
    "usr/local/bin/dex-fps",
    "usr/local/bin/dex-processes",
    "usr/bin/glxgears",
    "usr/bin/Xtigervnc",
    "usr/bin/gnome-shell",
    "usr/bin/git",
    "usr/sbin/sshd",
    "usr/share/code/code",
    "usr/lib/firefox/firefox",
    "etc/linux-on-dex-release",
)
