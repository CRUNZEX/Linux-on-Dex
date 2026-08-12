"""Turns an Ubuntu cloud image into a ready-to-boot desktop image.

A cloud image ships without a graphical environment, and installing one
inside a software-emulated VM on the phone would take hours. Instead this
runs the install once on the build machine, where an ARM64 guest can use
hardware virtualization (HVF on Apple silicon, KVM on Linux) and finishes in
minutes. The phone then boots a disk that is already a desktop.

The install is driven entirely by cloud-init: the guest is booted headless
with a seed that installs the desktop and powers the machine off when it is
done, so "the build finished" and "QEMU exited" are the same event.
"""

from __future__ import annotations

import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path


RELEASE_VERSION = "1.3.0"


@dataclass(frozen=True)
class DesktopBuildRequest:
    """Everything the desktop build needs, resolved by the caller."""

    base_cloud_image: Path
    output_disk: Path
    work_directory: Path
    disk_size: str
    username: str
    password: str
    desktop_environment: str = "xfce"
    build_timeout_seconds: int = 3600

    @property
    def profile(self) -> "DesktopProfile":
        profile = DESKTOP_PROFILES.get(self.desktop_environment)
        if profile is None:
            raise DesktopBuildError(
                f"unknown desktop '{self.desktop_environment}'; "
                f"choose from {sorted(DESKTOP_PROFILES)}"
            )
        return profile


class DesktopBuildError(RuntimeError):
    """Raised with a message the caller can print verbatim."""


@dataclass(frozen=True)
class DesktopProfile:
    """A desktop environment and how to make it boot cleanly on a phone VM.

    Everything that differs between XFCE and GNOME lives here; the install
    machinery around it is shared.
    """

    key: str
    packages: tuple[str, ...]
    # Display-manager unit to enable (lightdm, gdm3, …).
    display_manager: str
    # Extra cloud-init write_files entries, already indented for the document.
    extra_write_files: str
    # Extra cloud-init runcmd steps, already indented for the document.
    extra_runcmd: str


# XFCE: light and responsive even under software emulation. The safe default.
XFCE_PROFILE = DesktopProfile(
    key="xfce",
    packages=(
        "xfce4",
        "xfce4-terminal",
        "lightdm",
        "dbus-x11",
        "xserver-xorg-video-modesetting",
        "xserver-xorg-input-libinput",
        "x11-utils",
        "xdotool",
        # glxgears and glxinfo, so desktop smoothness can be measured on the
        # actual phone instead of estimated (see /usr/local/bin/dex-fps).
        "mesa-utils",
        "network-manager",
    ),
    display_manager="lightdm",
    extra_write_files="""  - path: /etc/lightdm/lightdm.conf.d/50-autologin.conf
    permissions: "0644"
    content: |
      [Seat:*]
      autologin-user={username}
      autologin-user-timeout=0
      user-session=xfce
  # The cloud-init seed is attached as a disk on first boot; without this
  # XFCE puts a "CIDATA" icon on the desktop the user never asked for.
  - path: /home/{username}/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml
    permissions: "0644"
    content: |
      <?xml version="1.0" encoding="UTF-8"?>
      <channel name="xfce4-desktop" version="1.0">
        <property name="desktop-icons" type="empty">
          <property name="file-icons" type="empty">
            <property name="show-removable" type="bool" value="false"/>
            <property name="show-filesystem" type="bool" value="true"/>
            <property name="show-home" type="bool" value="true"/>
            <property name="show-trash" type="bool" value="true"/>
          </property>
        </property>
      </channel>
  # No GPU in the VM: xfwm4's GL compositor would drag every frame through
  # software GL. Without compositing, windows draw directly — the single
  # biggest smoothness win a software VM can get. The shadow and vblank pins
  # keep that choice intact if a user ever re-enables compositing.
  - path: /home/{username}/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml
    permissions: "0644"
    content: |
      <?xml version="1.0" encoding="UTF-8"?>
      <channel name="xfwm4" version="1.0">
        <property name="general" type="empty">
          <property name="use_compositing" type="bool" value="false"/>
          <property name="show_frame_shadow" type="bool" value="false"/>
          <property name="show_popup_shadow" type="bool" value="false"/>
          <property name="show_dock_shadow" type="bool" value="false"/>
          <property name="vblank_mode" type="string" value="off"/>
        </property>
      </channel>
  # A phone VM has no monitor to power down: DPMS just blanks the VNC view,
  # which looks exactly like a hang. Xorg-level blanking is disabled in the
  # shared no-blank drop-in; this stops xfce4-power-manager re-enabling it.
  - path: /home/{username}/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-power-manager.xml
    permissions: "0644"
    content: |
      <?xml version="1.0" encoding="UTF-8"?>
      <channel name="xfce4-power-manager" version="1.0">
        <property name="xfce4-power-manager" type="empty">
          <property name="dpms-enabled" type="bool" value="false"/>
          <property name="blank-on-ac" type="int" value="0"/>
          <property name="dpms-on-ac-sleep" type="uint" value="0"/>
          <property name="dpms-on-ac-off" type="uint" value="0"/>
        </property>
      </channel>
""",
    extra_runcmd="""  - |
    set -e
    # cloud-init's write_files module creates parent directories as root.
    # LightDM must be able to create .Xauthority in the account's home or it
    # silently falls back to the greeter instead of starting XFCE.
    chown -R "{username}:{username}" "/home/{username}"
    test "$(stat -c %U "/home/{username}")" = "{username}"
    runuser -u "{username}" -- touch "/home/{username}/.linux-on-dex-write-test"
    rm -f "/home/{username}/.linux-on-dex-write-test"
    test -f /usr/share/xsessions/xfce.desktop
    lightdm --show-config >/run/linux-on-dex-lightdm.conf
    printf 'DEX_DESKTOP_PROFILE_VALIDATED=xfce\\n' >/dev/ttyAMA0
""",
)

# GNOME Flashback: GNOME Panel and settings with Metacity instead of Mutter.
# QEMU's Android headless build exposes a 2D virtio framebuffer but no usable
# EGL display backend, so GNOME Shell would composite every frame in llvmpipe.
# Flashback remains a GNOME desktop and is substantially more responsive.
GNOME_PROFILE = DesktopProfile(
    key="gnome",
    packages=(
        "gnome-session-flashback",
        "gnome-flashback",
        "gnome-panel",
        "metacity",
        "gdm3",
        "gnome-terminal",
        "nautilus",
        "gnome-control-center",
        "xserver-xorg",
        "x11-utils",
        "xdotool",
        "dbus-x11",
        # glxgears and glxinfo, so desktop smoothness can be measured on the
        # actual phone instead of estimated (see /usr/local/bin/dex-fps).
        "mesa-utils",
        "network-manager",
        "fonts-ubuntu",
        # Ubuntu's default-settings package. Without it GNOME points at a
        # gnome-backgrounds file that is not installed, and a missing
        # wallpaper makes GNOME paint the whole desktop in its fallback
        # primary-color — a solid #023c88 blue.
        "ubuntu-settings",
        "ubuntu-wallpapers",
    ),
    display_manager="gdm3",
    extra_write_files="""  - path: /etc/gdm3/custom.conf
    permissions: "0644"
    content: |
      [daemon]
      # QEMU VNC captures Xorg's framebuffer directly.
      WaylandEnable=false
      AutomaticLoginEnable=true
      AutomaticLogin={username}
  - path: /var/lib/AccountsService/users/{username}
    permissions: "0600"
    content: |
      [User]
      # This session is shipped by gnome-session-flashback and uses Metacity,
      # avoiding both the black Mutter scanout and GNOME Shell's fail-whale.
      Session=gnome-flashback-metacity
      XSession=gnome-flashback-metacity
      SystemAccount=false
  # System-wide dconf defaults: no animations, no first-run wizard, and the
  # dark theme most people expect from Ubuntu.
  - path: /etc/dconf/profile/user
    permissions: "0644"
    content: |
      user-db:user
      system-db:local
  - path: /etc/dconf/db/local.d/00-linux-on-dex
    permissions: "0644"
    content: |
      [org/gnome/desktop/interface]
      enable-animations=false
      color-scheme='prefer-dark'
      # A blinking cursor forces a display update every half second even on
      # an idle desktop — pure waste over VNC.
      cursor-blink=false
      [org/gnome/desktop/session]
      # Never blank the screen: a blanked VNC display looks like a hang.
      idle-delay=uint32 0
      [org/gnome/desktop/background]
      # Pin the wallpaper to a file ubuntu-wallpapers really ships. If the
      # configured picture is missing, GNOME paints solid #023c88 blue.
      picture-uri='file:///usr/share/backgrounds/warty-final-ubuntu.png'
      picture-uri-dark='file:///usr/share/backgrounds/warty-final-ubuntu.png'
      picture-options='zoom'
      primary-color='#242430'
      [org/gnome/desktop/search-providers]
      disable-external=true
      # Same lever as XFCE's use_compositing=false, and it was measured:
      # window drags under a software compositor paint ~0.1 updates/s over
      # VNC (freeze-then-jump), without one ~22/s. Metacity draws windows
      # directly when its compositor is off.
      [org/gnome/metacity]
      compositing-manager=false
""",
    extra_runcmd="""  - [dconf, update]
  # File-indexing miners are pure overhead on a phone VM.
  - [systemctl, "--global", mask, "tracker-miner-fs-3.service", "tracker-extract-3.service", "tracker-miner-rss-3.service", "tracker-miner-fs-control-3.service"]
  # The autologin account should never see the first-run setup wizard.
  - [apt-get, purge, "-y", gnome-initial-setup]
  - |
    set -e
    test -f /usr/share/xsessions/gnome-flashback-metacity.desktop
    test -x /usr/bin/gnome-flashback
    test -x /usr/bin/gnome-panel
    test -x /usr/bin/metacity
    printf 'DEX_DESKTOP_PROFILE_VALIDATED=gnome\\n' >/dev/ttyAMA0
""",
)

DESKTOP_PROFILES = {profile.key: profile for profile in (XFCE_PROFILE, GNOME_PROFILE)}

# Same purpose as the PRoot images' dex-fps, adapted to a QEMU desktop: the
# session lives on DISPLAY :0 under a display manager, so the script borrows
# the running session's environment instead of assuming the app's DISPLAY=:1.
FPS_BENCHMARK_SCRIPT = r"""#!/bin/sh
# Measures what this desktop can actually draw, on this device.
#
# Run it from a terminal on the desktop, or from the app's own Terminal —
# it borrows the running session's display and X authority, so it works
# either way. glxgears prints a frame rate every five seconds; the renderer
# line above it says what is doing the drawing (llvmpipe: the CPU, because
# a stock phone gives the VM no GPU).
set -u

SECONDS_TO_RUN="${1:-20}"

# -f matches the full command line: a process's short name is truncated to
# 15 characters by the kernel, so "gnome-session-binary" is unmatchable by
# name alone.
for SESSION_PROCESS in xfce4-session gnome-session-binary; do
    SESSION_PID=$(pgrep -f "$SESSION_PROCESS" | head -1)
    if [ -n "$SESSION_PID" ] && [ -r "/proc/$SESSION_PID/environ" ]; then
        for NAME in DISPLAY XAUTHORITY DBUS_SESSION_BUS_ADDRESS; do
            LINE=$(tr '\0' '\n' < "/proc/$SESSION_PID/environ" | grep "^$NAME=" || true)
            [ -n "$LINE" ] && export "$LINE"
        done
        break
    fi
done
export DISPLAY="${DISPLAY:-:0}"

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


def _indent_for_write_files(body: str) -> str:
    """Indents a file body to sit under a write_files `content: |` block."""
    return "\n".join(f"      {line}".rstrip() for line in body.strip("\n").splitlines())

# A bake seed must never reuse an instance id: cloud-image caches can retain
# cloud-init state, and a repeated id makes a rebuild silently skip its whole
# package/runcmd payload. The finished guest disables cloud-init before export,
# so the app's later seed identity cannot rerun this one-shot configuration.
BUILD_INSTANCE_ID_PREFIX = "linux-on-dex-desktop-build"


def log(message: str) -> None:
    print(f"[desktop] {message}", flush=True)


def build_desktop_image(request: DesktopBuildRequest) -> Path:
    """Runs the whole build and returns the finished disk."""
    qemu_system = _require_tool("qemu-system-aarch64")
    qemu_img = _require_tool("qemu-img")
    firmware = _locate_uefi_firmware()

    request.work_directory.mkdir(parents=True, exist_ok=True)
    staging_disk = request.work_directory / "desktop-build.qcow2"
    seed_iso = request.work_directory / "build-seed.iso"

    _prepare_staging_disk(qemu_img, request.base_cloud_image, staging_disk, request.disk_size)
    _write_build_seed(seed_iso, request)
    _run_install_boot(qemu_system, firmware, staging_disk, seed_iso, request)
    _compact_into_output(qemu_img, staging_disk, request.output_disk)

    log(f"desktop image ready: {request.output_disk.name} "
        f"({request.output_disk.stat().st_size >> 20} MiB)")
    return request.output_disk


# ---- Steps -----------------------------------------------------------------


def _prepare_staging_disk(
    qemu_img: str,
    base_image: Path,
    staging_disk: Path,
    disk_size: str,
) -> None:
    log(f"preparing staging disk ({disk_size})")
    shutil.copyfile(base_image, staging_disk)
    _run_checked(
        [qemu_img, "resize", str(staging_disk), disk_size],
        failure_message="resizing the staging disk",
    )


def _write_build_seed(seed_iso: Path, request: DesktopBuildRequest) -> None:
    """Writes the one-shot cloud-init seed that performs the install."""
    from build_ready_vm import build_seed_iso_from_documents  # local import: same tools dir

    build_instance_id = (
        f"{BUILD_INSTANCE_ID_PREFIX}-{request.profile.key}-{time.time_ns()}"
    )
    build_seed_iso_from_documents(
        output_iso=seed_iso,
        user_data=_render_build_user_data(request),
        meta_data=f"instance-id: {build_instance_id}\nlocal-hostname: dex\n",
    )


def _render_build_user_data(request: DesktopBuildRequest) -> str:
    """cloud-config that installs the chosen desktop and then powers off."""
    profile = request.profile
    username = request.username
    password = request.password
    package_list = "\n".join(f"  - {name}" for name in profile.packages)
    # Profile fragments carry {username} placeholders; fill them before use.
    profile_write_files = profile.extra_write_files.format(username=username)
    profile_runcmd = profile.extra_runcmd.format(username=username)
    # Kept out of the f-string below: the script's ${…} shell expansions would
    # otherwise all need doubled braces, which is exactly how builder scripts
    # get corrupted during later edits.
    fps_benchmark = _indent_for_write_files(FPS_BENCHMARK_SCRIPT)
    return f"""#cloud-config
hostname: dex
manage_etc_hosts: true

users:
  - name: {username}
    gecos: Linux on DeX
    groups: [adm, sudo, users, video, audio, plugdev, netdev]
    shell: /bin/bash
    sudo: "ALL=(ALL) NOPASSWD:ALL"
    lock_passwd: false
    plain_text_passwd: {password}

ssh_pwauth: true
disable_root: true
datasource_list: [NoCloud, None]

growpart:
  mode: auto
  devices: ["/"]
resize_rootfs: true

package_update: true
packages:
{package_list}

write_files:
{profile_write_files}  # The app's Terminal screen reads this serial port.
  - path: /etc/linux-on-dex-release
    permissions: "0644"
    content: |
      Linux on DeX {RELEASE_VERSION} Ubuntu 24.04 {profile.key}
  # The Android QEMU payload is headless and has no EGL display backend for
  # virtio-gpu-gl. Keep 2D drawing predictable and bound llvmpipe to the four
  # vCPUs the app configures by default.
  - path: /etc/environment.d/90-linux-on-dex-render.conf
    permissions: "0644"
    content: |
      LIBGL_ALWAYS_SOFTWARE=1
      GALLIUM_DRIVER=llvmpipe
      LP_NUM_THREADS=4
      GSK_RENDERER=cairo
      QT_XCB_GL_INTEGRATION=none
  # An X server blanks its screen after ~10 idle minutes. There is no real
  # monitor to save — only the VNC view, and a blanked VNC view looks like a
  # hang. GNOME's own idle-delay and XFCE's power manager are pinned off in
  # their profiles; this covers the X server itself for both.
  - path: /etc/X11/xorg.conf.d/10-linux-on-dex-noblank.conf
    permissions: "0644"
    content: |
      Section "ServerFlags"
          Option "BlankTime" "0"
          Option "StandbyTime" "0"
          Option "SuspendTime" "0"
          Option "OffTime" "0"
      EndSection
  # Measures what the desktop can actually draw, on this device.
  - path: /usr/local/bin/dex-fps
    permissions: "0755"
    content: |
{fps_benchmark}
  # Keep package downloads bounded and avoid translated indexes that are not
  # used by this English-only appliance image.
  - path: /etc/apt/apt.conf.d/99-linux-on-dex
    permissions: "0644"
    content: |
      Acquire::Queue-Mode "access";
      Acquire::http::Pipeline-Depth "0";
      Acquire::Retries "3";
      Acquire::http::Timeout "30";
      Acquire::https::Timeout "30";
      Acquire::Languages "none";
      Dpkg::Use-Pty "0";
  # TERM=xterm-256color: the app ships a real terminal emulator, and vt220
  # (systemd's serial default) would strip it down to monochrome.
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
  # QEMU's user-mode network resolves DNS through the host's /etc/resolv.conf,
  # which does not exist on Android — so the DHCP-provided 10.0.2.3 resolver
  # is dead on a phone. Public resolvers, reached as ordinary UDP traffic,
  # work everywhere.
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
      case "$(tty 2>/dev/null)" in
          /dev/ttyAMA*|/dev/ttyS*|/dev/hvc*) fix_console ;;
      esac

runcmd:
{profile_runcmd}  # Display managers only honour autologin for these groups.
  - [groupadd, -f, autologin]
  - [groupadd, -f, nopasswdlogin]
  - [usermod, -aG, "autologin,nopasswdlogin", {username}]
  # `write_files` runs before the final package/user setup on some cloud-init
  # versions. Never export an image when one failed write silently prevented
  # all later runtime configuration from being created.
  - |
    set -e
    test -f /etc/linux-on-dex-release
    test -f /etc/environment.d/90-linux-on-dex-render.conf
    test -f /etc/apt/apt.conf.d/99-linux-on-dex
    test -f /etc/X11/xorg.conf.d/10-linux-on-dex-noblank.conf
    test -x /usr/local/bin/dex-fps
    test -x /usr/bin/glxgears
    test -f /etc/systemd/system/serial-getty@ttyAMA0.service.d/autologin.conf
    test -f /etc/systemd/system/serial-getty@hvc0.service.d/autologin.conf
    test -f /etc/systemd/system/serial-getty@hvc1.service.d/autologin.conf
    echo DEX_DESKTOP_COMMON_VALIDATED=1 >/dev/ttyAMA0
  # Reload the drop-ins before touching any getty. ttyAMA0 is the built-in
  # Terminal and hvc0/hvc1 back additional terminal windows.
  - [systemctl, daemon-reload]
  - [sh, -c, "systemctl enable serial-getty@ttyAMA0.service 2>/dev/null || true"]
  - [sh, -c, "systemctl enable serial-getty@hvc0.service 2>/dev/null || true"]
  - [sh, -c, "systemctl enable serial-getty@hvc1.service 2>/dev/null || true"]
  - [sh, -c, "systemctl restart serial-getty@ttyAMA0.service 2>/dev/null || true"]
  - [sh, -c, "systemctl restart serial-getty@hvc0.service 2>/dev/null || true"]
  - [sh, -c, "systemctl restart serial-getty@hvc1.service 2>/dev/null || true"]
  # Pick up the public-resolver drop-in written above.
  - [systemctl, restart, systemd-resolved.service]
  # Nothing below earns its keep on a phone, and each costs real time when
  # the guest is software-emulated.
  - [systemctl, disable, --now, snapd.service, snapd.socket, snapd.seeded.service]
  - [systemctl, disable, --now, unattended-upgrades.service]
  - [systemctl, disable, --now, apt-daily.timer, apt-daily-upgrade.timer]
  - [systemctl, disable, --now, apport.service]
  - [systemctl, mask, systemd-networkd-wait-online.service]
  - [systemctl, mask, NetworkManager-wait-online.service]
  # A VM has no modem and no wifi radio; these probe hardware that is not
  # there and slow every boot down.
  - [systemctl, mask, ModemManager.service, wpa_supplicant.service]
  - [systemctl, mask, pd-mapper.service, qrtr-ns.service]
  - [systemctl, set-default, graphical.target]
  - [systemctl, enable, {profile.display_manager}.service]
  # Shrink what the finished image has to carry.
  - [apt-get, clean]
  # Keep current package lists: the first `apt update` can then use conditional
  # requests instead of downloading every index from scratch.
  - [sh, -c, "fstrim -av || true"]
  # Everything cloud-init had to do is baked in now. Without this flag it
  # would still run all its stages on every phone boot — pure start-up cost.
  - [touch, /etc/cloud/cloud-init.disabled]

power_state:
  mode: poweroff
  timeout: 60
  condition: true

final_message: "Linux on DeX desktop build complete."
"""


def _run_install_boot(
    qemu_system: str,
    firmware: Path,
    staging_disk: Path,
    seed_iso: Path,
    request: DesktopBuildRequest,
) -> None:
    """Boots the guest until cloud-init finishes and powers it off."""
    variables_store = request.work_directory / "build-efi-vars.fd"
    _create_efi_variables_store(variables_store)
    console_log = request.work_directory / "desktop-build-console.log"

    command = [
        qemu_system,
        "-machine", "virt",
        "-accel", _pick_accelerator(),
        "-cpu", "host" if _pick_accelerator() == "hvf" else "max",
        "-smp", str(_build_cpu_count()),
        "-m", "4096",
        "-nodefaults",
        "-drive", f"if=pflash,format=raw,readonly=on,file={firmware}",
        "-drive", f"if=pflash,format=raw,file={variables_store}",
        "-drive", f"if=none,id=root,format=qcow2,discard=unmap,file={staging_disk}",
        "-device", "virtio-blk-pci,drive=root,bootindex=0",
        "-drive", f"if=none,id=seed,format=raw,readonly=on,file={seed_iso}",
        "-device", "virtio-blk-pci,drive=seed",
        "-netdev", "user,id=net0",
        "-device", "virtio-net-pci,netdev=net0",
        "-device", "virtio-rng-pci",
        "-display", "none",
        "-serial", f"file:{console_log}",
    ]

    log(f"installing {len(request.profile.packages)} {request.profile.key} packages in a "
        f"{_pick_accelerator()}-accelerated guest (this takes a few minutes)")
    started_at = time.monotonic()
    process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    try:
        _wait_for_install(process, console_log, request.build_timeout_seconds, started_at)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=30)

    _require_install_succeeded(process, console_log)
    log(f"install finished in {int(time.monotonic() - started_at)}s")


def _wait_for_install(
    process: subprocess.Popen,
    console_log: Path,
    timeout_seconds: int,
    started_at: float,
) -> None:
    """Waits for the guest to power itself off, reporting progress."""
    next_report_at = PROGRESS_REPORT_INTERVAL_SECONDS
    while process.poll() is None:
        elapsed = time.monotonic() - started_at
        if elapsed > timeout_seconds:
            raise DesktopBuildError(
                f"the build guest did not finish within {timeout_seconds}s. "
                f"Console log: {console_log}"
            )
        if elapsed >= next_report_at:
            next_report_at += PROGRESS_REPORT_INTERVAL_SECONDS
            log(f"  still installing… {int(elapsed)}s — {_summarise_console(console_log)}")
        time.sleep(POLL_INTERVAL_SECONDS)


def _require_install_succeeded(process: subprocess.Popen, console_log: Path) -> None:
    console_text = _read_console(console_log)
    if "Linux on DeX desktop build complete" not in console_text:
        tail = console_text[-1500:] if console_text else "(console log empty)"
        raise DesktopBuildError(
            "cloud-init did not report a successful desktop build.\n"
            f"Console tail:\n{tail}"
        )
    if "DEX_DESKTOP_PROFILE_VALIDATED=" not in console_text:
        tail = console_text[-1500:] if console_text else "(console log empty)"
        raise DesktopBuildError(
            "the selected desktop session or autologin configuration failed validation.\n"
            f"Console tail:\n{tail}"
        )
    if "DEX_DESKTOP_COMMON_VALIDATED=1" not in console_text:
        tail = console_text[-1500:] if console_text else "(console log empty)"
        raise DesktopBuildError(
            "shared desktop runtime files failed validation.\n"
            f"Console tail:\n{tail}"
        )
    if process.returncode not in (0, None):
        raise DesktopBuildError(f"QEMU exited with code {process.returncode}")


def _compact_into_output(qemu_img: str, staging_disk: Path, output_disk: Path) -> None:
    """Rewrites the image so trimmed blocks stop taking space."""
    log("compacting the finished image")
    output_disk.parent.mkdir(parents=True, exist_ok=True)
    output_disk.unlink(missing_ok=True)
    _run_checked(
        [
            qemu_img,
            "convert",
            "-p",
            "-c",
            "-O",
            "qcow2",
            "-o",
            "compat=1.1,compression_type=zlib",
            str(staging_disk),
            str(output_disk),
        ],
        failure_message="compacting the finished image",
    )


# ---- Helpers ---------------------------------------------------------------


def _create_efi_variables_store(variables_store: Path) -> None:
    """UEFI needs a writable 64 MiB variable store next to the code flash."""
    if variables_store.exists() and variables_store.stat().st_size == EFI_VARS_BYTES:
        return
    variables_store.write_bytes(b"\x00" * EFI_VARS_BYTES)


def _locate_uefi_firmware() -> Path:
    for candidate in UEFI_FIRMWARE_CANDIDATES:
        path = Path(candidate)
        if path.exists():
            return path
    raise DesktopBuildError(
        "could not find edk2-aarch64-code.fd. Install QEMU's ARM firmware "
        "(brew install qemu, or apt install qemu-efi-aarch64)."
    )


def _pick_accelerator() -> str:
    """Hardware virtualization when the build machine offers it."""
    if Path("/dev/kvm").exists():
        return "kvm"
    import platform
    if platform.system() == "Darwin" and platform.machine() == "arm64":
        return "hvf"
    return "tcg"


def _build_cpu_count() -> int:
    import os
    return max(2, min(8, (os.cpu_count() or 4) - 2))


def _summarise_console(console_log: Path) -> str:
    """Last meaningful console line, for a progress hint."""
    text = _read_console(console_log)
    for line in reversed(text.splitlines()):
        stripped = "".join(ch for ch in line if ch.isprintable()).strip()
        if len(stripped) > 12:
            return stripped[:90]
    return "booting"


def _read_console(console_log: Path) -> str:
    if not console_log.exists():
        return ""
    return console_log.read_text(errors="replace")


def _require_tool(name: str) -> str:
    path = shutil.which(name)
    if path is None:
        raise DesktopBuildError(f"'{name}' is required but not installed")
    return path


def _run_checked(command: list[str], failure_message: str) -> None:
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise DesktopBuildError(f"{failure_message}: {result.stderr.strip()}")


EFI_VARS_BYTES = 64 * 1024 * 1024
POLL_INTERVAL_SECONDS = 5
PROGRESS_REPORT_INTERVAL_SECONDS = 60

UEFI_FIRMWARE_CANDIDATES = (
    "/opt/homebrew/share/qemu/edk2-aarch64-code.fd",
    "/usr/local/share/qemu/edk2-aarch64-code.fd",
    "/usr/share/AAVMF/AAVMF_CODE.fd",
    "/usr/share/qemu-efi-aarch64/QEMU_EFI.fd",
)
