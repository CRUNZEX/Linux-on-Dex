#!/usr/bin/env python3
"""Build a lightweight GNOME-like Ubuntu desktop for Linux on DeX.

GNOME Shell composites every frame and is too expensive over a phone-local VNC
display. This image uses XFCE components with a GNOME-like top bar and dock,
but deliberately omits the compositor, desktop manager, animations, and stock
session helpers. OpenGL applications still use the app's Android virgl bridge.
The artifact is a normal ``.rootfs.tar.gz`` consumed by the PRoot engine.

The build runs in an arm64 Docker container, installs only the desktop pieces
that are used, then exports and validates the filesystem.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import os
import shutil
import subprocess
import tarfile
import tempfile
import textwrap
import uuid
from pathlib import Path


UBUNTU_BASE_DIGEST = "sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90"
RELEASE_VERSION = "1.1.12"

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = (
    PROJECT_ROOT
    / "dist/ready-vm/linux-on-dex-ubuntu-24.04-proot-xfce-arm64.rootfs.tar.gz"
)

REQUIRED_PATHS = (
    "usr/local/bin/dex-desktop",
    "usr/local/bin/dex-xfce-session",
    "usr/local/bin/dex-name-groups",
    "usr/local/bin/code",
    "usr/share/code/code",
    "usr/bin/xfce4-panel",
    "usr/bin/xfsettingsd",
    "usr/bin/xfwm4",
    "usr/bin/Xtigervnc",
    "usr/sbin/sshd",
    "usr/bin/xdotool",
)


def log(message: str) -> None:
    print(f"[proot-xfce] {message}", flush=True)


def run(command: list[str], *, cwd: Path | None = None) -> None:
    """Run one build step and fail with its exact command on error."""
    log("running: " + " ".join(command))
    subprocess.run(command, cwd=cwd, check=True)


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        raise RuntimeError(f"required tool is not installed: {name}")


def write_build_context(directory: Path) -> None:
    """Create the disposable Docker context from reviewed, pinned inputs."""
    dockerfile = render_dockerfile()
    validate_dockerfile(dockerfile)
    (directory / "Dockerfile").write_text(dockerfile, encoding="utf-8")
    (directory / "dex-desktop").write_text(DESKTOP_SUPERVISOR, encoding="utf-8")
    (directory / "dex-xfce-session").write_text(DESKTOP_SESSION, encoding="utf-8")
    (directory / "dex-name-groups").write_text(GROUP_NAMER_SCRIPT, encoding="utf-8")
    (directory / "code-wrapper").write_text(VSCODE_WRAPPER_SCRIPT, encoding="utf-8")
    (directory / "apt-config").write_text(APT_CONFIG, encoding="utf-8")
    (directory / "sshd_config").write_text(SSHD_CONFIG, encoding="utf-8")
    (directory / "xfwm4.xml").write_text(XFWM_CONFIGURATION, encoding="utf-8")
    (directory / "xfce4-panel.xml").write_text(PANEL_CONFIGURATION, encoding="utf-8")
    (directory / "xsettings.xml").write_text(XSETTINGS_CONFIGURATION, encoding="utf-8")


def render_dockerfile() -> str:
    return textwrap.dedent(
        f"""\
        FROM ubuntu:24.04@{UBUNTU_BASE_DIGEST}

        ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8

        RUN apt-get update && apt-get install -y --no-install-recommends \\
              bash coreutils procps util-linux dbus dbus-x11 \\
              xfwm4 xfce4-panel xfce4-settings \\
              thunar xfce4-terminal tigervnc-standalone-server x11-xserver-utils \\
              x11-utils xdotool \\
              openssh-server openssh-client git curl ca-certificates \\
              mesa-utils fonts-ubuntu fonts-dejavu-core adwaita-icon-theme \\
            && curl -fsSL -o /tmp/code.deb \\
              'https://update.code.visualstudio.com/latest/linux-deb-arm64/stable' \\
            && apt-get install -y --no-install-recommends /tmp/code.deb \\
            && rm -f /tmp/code.deb \\
            && apt-get clean \\
            && rm -rf /var/lib/apt/lists/* /usr/share/doc/* /usr/share/man/*

        COPY dex-desktop /usr/local/bin/dex-desktop
        COPY dex-xfce-session /usr/local/bin/dex-xfce-session
        COPY dex-name-groups /usr/local/bin/dex-name-groups
        COPY code-wrapper /opt/linux-on-dex/code-wrapper
        COPY apt-config /etc/apt/apt.conf.d/99-linux-on-dex
        COPY sshd_config /etc/ssh/sshd_config.d/10-linux-on-dex.conf
        COPY xfwm4.xml /root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml
        COPY xfce4-panel.xml /root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml
        COPY xsettings.xml /root/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml

        RUN chmod 0755 /usr/local/bin/dex-desktop /usr/local/bin/dex-xfce-session \\
              /usr/local/bin/dex-name-groups /opt/linux-on-dex/code-wrapper \\
            && install -m 0755 /opt/linux-on-dex/code-wrapper /usr/local/bin/code \\
            && sed -i 's|Exec=/usr/share/code/code|Exec=/usr/local/bin/code|g' \\
              /usr/share/applications/code.desktop \\
              /usr/share/applications/code-url-handler.desktop \\
            && printf '%s\\n' \\
              '[ -x /usr/local/bin/dex-name-groups ] && /usr/local/bin/dex-name-groups 2>/dev/null' \\
              ':' > /etc/profile.d/05-linux-on-dex-groups.sh \\
            && mkdir -p /run/sshd /run/linux-on-dex /root/shared /var/log/apt \\
              /var/cache/apt/archives/partial /var/lib/apt/lists/partial \\
              /var/lib/dpkg/updates /var/lib/dpkg/info \\
            && printf 'root:linuxondex\\n' | chpasswd \\
            && printf 'Linux on DeX {RELEASE_VERSION} Ubuntu 24.04 GNOME-like XFCE\\n' \\
              > /etc/linux-on-dex-release \\
            && rm -f /etc/machine-id /var/lib/dbus/machine-id /etc/ssh/ssh_host_* \\
            && find /var/log -type f -delete \\
            && rm -rf /tmp/* /root/.cache

        WORKDIR /root
        CMD ["/usr/local/bin/dex-desktop"]
        """
    )


def validate_dockerfile(dockerfile: str) -> None:
    """Catch escaped-newline regressions before invoking a Docker build."""
    required_fragments = (
        r"printf 'root:linuxondex\n'",
        rf"printf 'Linux on DeX {RELEASE_VERSION} Ubuntu 24.04 GNOME-like XFCE\n'",
        "COPY dex-xfce-session /usr/local/bin/dex-xfce-session",
    )
    missing = [fragment for fragment in required_fragments if fragment not in dockerfile]
    if missing:
        raise RuntimeError("generated Dockerfile lost a shell escape: " + ", ".join(missing))


def export_image(image_tag: str, output: Path) -> None:
    container_name = f"linux-on-dex-rootfs-export-{uuid.uuid4().hex[:10]}"
    run(["docker", "create", "--name", container_name, image_tag])
    temporary_output = output.with_suffix(output.suffix + ".part")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_output.unlink(missing_ok=True)

    try:
        process = subprocess.Popen(["docker", "export", container_name], stdout=subprocess.PIPE)
        if process.stdout is None:
            raise RuntimeError("docker export did not provide stdout")
        with temporary_output.open("wb") as raw_output:
            with gzip.GzipFile(fileobj=raw_output, mode="wb", compresslevel=6, mtime=0) as archive:
                shutil.copyfileobj(process.stdout, archive, length=1 << 20)
        if process.wait() != 0:
            raise subprocess.CalledProcessError(process.returncode, process.args)
        temporary_output.replace(output)
    finally:
        subprocess.run(["docker", "rm", "-f", container_name], check=False)


def verify_archive(archive_path: Path) -> None:
    """Fully stream the gzip and ensure every runtime entry is present."""
    digest = hashlib.sha256()
    found: set[str] = set()
    with archive_path.open("rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)

    with tarfile.open(archive_path, mode="r:gz") as archive:
        for member in archive:
            normalized = member.name.removeprefix("./").rstrip("/")
            if normalized in REQUIRED_PATHS:
                found.add(normalized)

    missing = sorted(set(REQUIRED_PATHS) - found)
    if missing:
        raise RuntimeError("rootfs archive is missing: " + ", ".join(missing))
    log(
        f"verified {archive_path.name}: {archive_path.stat().st_size >> 20} MiB, "
        f"sha256 {digest.hexdigest()}"
    )


def build(output: Path) -> Path:
    require_tool("docker")
    image_tag = f"linux-on-dex-proot-xfce:{uuid.uuid4().hex[:12]}"
    with tempfile.TemporaryDirectory(prefix="linux-on-dex-xfce-") as temp:
        build_context = Path(temp)
        write_build_context(build_context)
        environment = os.environ.copy()
        environment["DOCKER_BUILDKIT"] = "1"
        log(f"building arm64 image {image_tag}")
        subprocess.run(
            ["docker", "build", "--platform", "linux/arm64", "-t", image_tag, "."],
            cwd=build_context,
            env=environment,
            check=True,
        )
        try:
            export_image(image_tag, output)
            verify_archive(output)
        finally:
            subprocess.run(["docker", "image", "rm", image_tag], check=False)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    built = build(args.output.resolve())
    print(built)


DESKTOP_SUPERVISOR = r"""#!/bin/sh
set -eu

resolution="${DEX_RESOLUTION:-1280x800}"
vnc_port="${DEX_VNC_PORT:-5901}"
max_frame_rate=240

log() { printf '[dex-xfce] %s\n' "$*"; }

export HOME=/root USER=root LOGNAME=root SHELL=/bin/bash LANG=C.UTF-8
/usr/local/bin/dex-name-groups 2>/dev/null || true
export DISPLAY=:1 XDG_RUNTIME_DIR=/run/user/0
export XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP=XFCE
if [ "${DEX_GPU_BRIDGE:-0}" = 1 ]; then
    export LIBGL_ALWAYS_SOFTWARE=1
    export GALLIUM_DRIVER=virpipe
    export MESA_GL_VERSION_OVERRIDE=3.3
    export MESA_GLES_VERSION_OVERRIDE=3.1
    log 'native Android virgl bridge enabled'
else
    export LIBGL_ALWAYS_SOFTWARE=1
    log 'native GPU bridge unavailable; using llvmpipe'
fi
export GSK_RENDERER=cairo
export NO_AT_BRIDGE=1 GTK_A11Y=none GVFS_DISABLE_FUSE=1

mkdir -p "$XDG_RUNTIME_DIR" /run/dbus /run/sshd /tmp/.X11-unix /var/lib/dbus
chmod 700 "$XDG_RUNTIME_DIR"
chmod 1777 /tmp /tmp/.X11-unix
rm -f /run/dbus/pid /tmp/.X1-lock /tmp/.X11-unix/X1

dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true
dbus-uuidgen --ensure 2>/dev/null || true
[ -f /etc/ssh/ssh_host_ed25519_key ] || ssh-keygen -A >/dev/null 2>&1 || true
dbus-daemon --system --fork 2>/dev/null || log 'system D-Bus unavailable'
/usr/sbin/sshd 2>/dev/null || log 'sshd unavailable'

log "starting Xvnc at ${resolution}, port ${vnc_port}"
Xtigervnc :1 -geometry "$resolution" -depth 24 -rfbport "$vnc_port" \
    -localhost -SecurityTypes None -AlwaysShared -FrameRate "$max_frame_rate" \
    -desktop 'Linux on DeX GNOME-like desktop' &
xvnc_pid=$!

attempt=0
while [ ! -S /tmp/.X11-unix/X1 ]; do
    attempt=$((attempt + 1))
    [ "$attempt" -le 100 ] || { log 'Xvnc socket timeout'; exit 1; }
    kill -0 "$xvnc_pid" 2>/dev/null || { log 'Xvnc exited early'; exit 1; }
    sleep 0.1
done

# Start only the settings daemon, window manager, and panel. The stock XFCE
# session also starts xfdesktop and helpers that add redraws and failing
# service probes under Android PRoot.
log 'starting GNOME-like XFCE session (compositor and animations disabled)'
exec dbus-run-session -- /usr/local/bin/dex-xfce-session
"""


DESKTOP_SESSION = r"""#!/bin/sh
set -eu

log() { printf '[dex-session] %s\n' "$*"; }

settings_pid=''
window_manager_pid=''
panel_pid=''
shutdown_requested=0

request_shutdown() { shutdown_requested=1; }

stop_process() {
    process_id="$1"
    [ -z "$process_id" ] || kill "$process_id" 2>/dev/null || true
}

cleanup() {
    trap - EXIT INT TERM
    stop_process "$panel_pid"
    stop_process "$window_manager_pid"
    stop_process "$settings_pid"
    wait 2>/dev/null || true
}

trap cleanup EXIT
trap request_shutdown INT TERM

# A solid background replaces xfdesktop and its thumbnail/mount monitors.
xsetroot -solid '#1e1e2e'

xfsettingsd --replace --no-daemon &
settings_pid=$!
xfwm4 --replace &
window_manager_pid=$!
xfce4-panel &
panel_pid=$!

log 'desktop ready: top bar, dock, settings, and non-compositing window manager'
while [ "$shutdown_requested" -eq 0 ]; do
    for service in \
        "settings:$settings_pid" \
        "window-manager:$window_manager_pid" \
        "panel:$panel_pid"
    do
        service_name=${service%%:*}
        process_id=${service#*:}
        if ! kill -0 "$process_id" 2>/dev/null; then
            log "$service_name exited unexpectedly"
            exit 1
        fi
    done
    sleep 1
done
"""


GROUP_NAMER_SCRIPT = r"""#!/bin/sh
# Android adds network, storage, and app-specific supplementary group IDs to
# every child process. Name unknown IDs before any login shell calls groups.
set -u

group_file=/etc/group
lock_file=/etc/.linux-on-dex-groups.lock
[ -w "$group_file" ] || exit 0

(
    flock 9
    for group_id in $(id -G 2>/dev/null); do
        case "$group_id" in *[!0-9]*|'') continue ;; esac
        if cut -d: -f3 "$group_file" | grep -qx "$group_id"; then
            continue
        fi
        printf 'android%s:x:%s:\n' "$group_id" "$group_id" >> "$group_file"
    done
) 9>>"$lock_file"
"""


VSCODE_WRAPPER_SCRIPT = r"""#!/bin/sh
# Chromium namespaces are unavailable in PRoot. Keep Electron's helper count
# low as well, because Android enforces a strict child-process budget.
exec /usr/share/code/code \
    --no-sandbox \
    --disable-gpu \
    --disable-dev-shm-usage \
    --disable-crash-reporter \
    --disable-features=CalculateNativeWinOcclusion,UseChromeOSDirectVideoDecoder \
    --password-store=basic \
    "$@"
"""


APT_CONFIG = """APT::Sandbox::User "root";
Acquire::Queue-Mode "access";
Acquire::http::Pipeline-Depth "0";
Acquire::Retries "3";
Acquire::http::Timeout "30";
Acquire::https::Timeout "30";
Acquire::Languages "none";
Dpkg::Use-Pty "0";
"""


SSHD_CONFIG = """Port 8022
ListenAddress 127.0.0.1
PermitRootLogin yes
PasswordAuthentication yes
UsePAM no
"""


XFWM_CONFIGURATION = """<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="use_compositing" type="bool" value="false"/>
    <property name="show_frame_shadow" type="bool" value="false"/>
    <property name="show_popup_shadow" type="bool" value="false"/>
    <property name="show_dock_shadow" type="bool" value="false"/>
    <property name="vblank_mode" type="string" value="off"/>
  </property>
</channel>
"""


PANEL_CONFIGURATION = """<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
    <value type="int" value="2"/>
    <property name="dark-mode" type="bool" value="true"/>
    <property name="panel-1" type="empty">
      <property name="position" type="string" value="p=6;x=0;y=0"/>
      <property name="length" type="uint" value="100"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="icon-size" type="uint" value="18"/>
      <property name="size" type="uint" value="30"/>
      <property name="plugin-ids" type="array">
        <value type="int" value="1"/>
        <value type="int" value="2"/>
        <value type="int" value="3"/>
        <value type="int" value="4"/>
        <value type="int" value="5"/>
        <value type="int" value="6"/>
      </property>
    </property>
    <property name="panel-2" type="empty">
      <property name="position" type="string" value="p=10;x=0;y=0"/>
      <property name="length" type="uint" value="1"/>
      <property name="length-adjust" type="bool" value="true"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="48"/>
      <property name="plugin-ids" type="array">
        <value type="int" value="7"/>
        <value type="int" value="8"/>
        <value type="int" value="9"/>
        <value type="int" value="10"/>
      </property>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-1" type="string" value="applicationsmenu"/>
    <property name="plugin-2" type="string" value="tasklist">
      <property name="grouping" type="uint" value="1"/>
      <property name="show-handle" type="bool" value="false"/>
    </property>
    <property name="plugin-3" type="string" value="separator">
      <property name="expand" type="bool" value="true"/>
      <property name="style" type="uint" value="0"/>
    </property>
    <property name="plugin-4" type="string" value="systray">
      <property name="square-icons" type="bool" value="true"/>
    </property>
    <property name="plugin-5" type="string" value="clock"/>
    <property name="plugin-6" type="string" value="actions"/>
    <property name="plugin-7" type="string" value="showdesktop"/>
    <property name="plugin-8" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="xfce4-terminal-emulator.desktop"/>
      </property>
    </property>
    <property name="plugin-9" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="xfce4-file-manager.desktop"/>
      </property>
    </property>
    <property name="plugin-10" type="string" value="directorymenu"/>
  </property>
</channel>
"""


XSETTINGS_CONFIGURATION = """<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="Adwaita-dark"/>
    <property name="IconThemeName" type="string" value="Adwaita"/>
    <property name="CursorBlink" type="bool" value="false"/>
    <property name="EnableEventSounds" type="bool" value="false"/>
    <property name="EnableInputFeedbackSounds" type="bool" value="false"/>
  </property>
  <property name="Xft" type="empty">
    <property name="Antialias" type="int" value="1"/>
    <property name="Hinting" type="int" value="1"/>
    <property name="HintStyle" type="string" value="hintslight"/>
    <property name="RGBA" type="string" value="rgb"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="FontName" type="string" value="Ubuntu 10"/>
    <property name="MonospaceFontName" type="string" value="Ubuntu Mono 10"/>
    <property name="EnableAnimations" type="bool" value="false"/>
    <property name="DecorationLayout" type="string" value="menu:minimize,maximize,close"/>
  </property>
</channel>
"""


if __name__ == "__main__":
    main()
