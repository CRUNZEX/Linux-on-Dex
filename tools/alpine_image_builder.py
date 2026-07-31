#!/usr/bin/env python3
"""Bake a ready-to-boot Alpine ARM64 image with containers preinstalled.

Alpine's official "nocloud" UEFI image ships cloud-init, so the recipe is
the same one the desktop flavours use: boot the image once on this machine
(hardware-accelerated), let cloud-init create the account, install podman,
docker and LXC, wire the consoles and DNS, then power off and compact the
disk. The phone then boots a fully-configured system in seconds — nothing
is downloaded or installed on the phone itself.

Alpine differs from the Ubuntu/Debian flavours in two ways this file owns:

  - OpenRC, not systemd: services are enabled with rc-update, and the
    autologin consoles are /etc/inittab lines instead of getty drop-ins.
  - No systemd-resolved: /etc/resolv.conf is written statically with public
    resolvers and udhcpc is told to leave it alone, because the DHCP-offered
    10.0.2.3 resolver cannot work on Android.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from cloud_image_bake import (
    BakeBootRequest,
    ImageBakeError,
    bake_cloud_image,
    compact_into,
    prepare_staging_disk,
)


@dataclass(frozen=True)
class AlpineBuildRequest:
    base_cloud_image: Path
    output_disk: Path
    work_directory: Path
    disk_size: str
    username: str
    password: str
    # False builds "alpine-light": the same base system without any
    # container tooling — the smallest and fastest-booting flavour.
    include_container_stack: bool = True
    build_timeout_seconds: int = 1800


BUILD_COMPLETE_MARKER = "Linux on DeX alpine build complete."

# The base every Alpine flavour needs. bash/shadow/sudo give the dex
# account the same shell experience as the other flavours; growpart and
# resize2fs let cloud-init grow the root filesystem to --disk-size.
ALPINE_BASE_PACKAGES = (
    "bash",
    "shadow",
    "sudo",
    "agetty",
    "tzdata",
    "cloud-utils-growpart",
    "e2fsprogs-extra",
)

# The container host on top of the base: podman, docker and LXC.
ALPINE_CONTAINER_PACKAGES = (
    "docker",
    "docker-cli-compose",
    "podman",
    "lxc",
    "lxcfs",
    "fuse-overlayfs",
)


def packages_for(request: AlpineBuildRequest) -> tuple[str, ...]:
    if request.include_container_stack:
        return ALPINE_BASE_PACKAGES + ALPINE_CONTAINER_PACKAGES
    return ALPINE_BASE_PACKAGES


def log(message: str) -> None:
    print(f"[alpine] {message}", flush=True)


def build_alpine_image(request: AlpineBuildRequest) -> Path:
    """Runs the whole bake and returns the finished disk."""
    request.work_directory.mkdir(parents=True, exist_ok=True)
    staging_disk = request.work_directory / "alpine-build.qcow2"
    seed_iso = request.work_directory / "alpine-build-seed.iso"

    log(f"preparing staging disk ({request.disk_size})")
    prepare_staging_disk(request.base_cloud_image, staging_disk, request.disk_size)

    _write_build_seed(seed_iso, request)
    bake_cloud_image(
        BakeBootRequest(
            staging_disk=staging_disk,
            seed_iso=seed_iso,
            work_directory=request.work_directory,
            success_marker=BUILD_COMPLETE_MARKER,
            timeout_seconds=request.build_timeout_seconds,
        ),
        log=log,
    )

    log("compacting the finished image")
    compact_into(staging_disk, request.output_disk)
    log(f"alpine image ready: {request.output_disk.name} "
        f"({request.output_disk.stat().st_size >> 20} MiB)")
    return request.output_disk


def _write_build_seed(seed_iso: Path, request: AlpineBuildRequest) -> None:
    from build_ready_vm import build_seed_iso_from_documents

    build_seed_iso_from_documents(
        output_iso=seed_iso,
        user_data=_render_build_user_data(request),
        meta_data="instance-id: linux-on-dex-001\nlocal-hostname: dex\n",
    )


def _render_build_user_data(request: AlpineBuildRequest) -> str:
    """cloud-config that turns the stock Alpine image into our flavour."""
    username = request.username
    password = request.password
    package_list = "\n".join(f"  - {name}" for name in packages_for(request))
    container_runcmd = _container_runcmd_lines(username) if request.include_container_stack else ""
    motd_text = _motd_text(request.include_container_stack)
    return f"""#cloud-config
hostname: dex
manage_etc_hosts: true

users:
  # The shell starts as ash; a runcmd switches it to bash once the bash
  # package is installed (packages install after user creation).
  - name: {username}
    gecos: Linux on DeX
    groups: [wheel]
    shell: /bin/ash
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
  # QEMU's user-mode network resolves DNS through the host's
  # /etc/resolv.conf, which does not exist on Android — the DHCP-provided
  # 10.0.2.3 resolver is dead on a phone. Alpine has no systemd-resolved;
  # the fix is a static resolv.conf, written at the END of runcmd because
  # the DHCP client rewrites it during this build boot. Both clients Alpine
  # images may use are told to keep their hands off it afterwards.
  - path: /etc/udhcpc/udhcpc.conf
    permissions: "0644"
    content: |
      RESOLV_CONF="no"
  - path: /etc/sysctl.d/99-dex.conf
    permissions: "0644"
    content: |
      # Emulated storage is slow; batch writeback instead of trickling it.
      vm.dirty_ratio = 30
      vm.dirty_background_ratio = 10
  # Serial lines cannot deliver SIGWINCH, so the guest never learns the
  # terminal size. This asks the terminal directly (cursor-position report)
  # at login and before every prompt; `fix_console` also works by hand.
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
          /dev/ttyAMA*|/dev/ttyS*|/dev/hvc*)
              fix_console
              PROMPT_COMMAND="fix_console${{PROMPT_COMMAND:+;$PROMPT_COMMAND}}"
              ;;
      esac

runcmd:
  # The image's DHCP client is dhcpcd; without this it regenerates
  # resolv.conf with the dead slirp resolver on every lease.
  - [sh, -c, "printf '\\nnohook resolv.conf\\n' >> /etc/dhcpcd.conf 2>/dev/null || true"]
  # bash exists now; give the account the same shell as every other flavour.
  - [usermod, -s, /bin/bash, {username}]
{container_runcmd}  # Autologin consoles: the app's Terminal is the PL011 serial port, and its
  # extra windows are the two virtio consoles. Replace any stock getty on
  # those lines, then append ours.
  - [sh, -c, "sed -i -e '/^ttyAMA0:/d' -e '/^console:/d' -e '/^hvc0:/d' -e '/^hvc1:/d' /etc/inittab"]
  - [sh, -c, "printf 'ttyAMA0::respawn:/sbin/agetty --autologin {username} --noclear ttyAMA0 115200 xterm-256color\\n' >> /etc/inittab"]
  - [sh, -c, "printf 'hvc0::respawn:/sbin/agetty --autologin {username} --noclear hvc0 115200 xterm-256color\\n' >> /etc/inittab"]
  - [sh, -c, "printf 'hvc1::respawn:/sbin/agetty --autologin {username} --noclear hvc1 115200 xterm-256color\\n' >> /etc/inittab"]
  # podman (crun) wants the unified cgroup hierarchy; docker is happy
  # there, and the plain flavour does not care either way.
  - [sh, -c, "sed -i 's/^#*rc_cgroup_mode=.*/rc_cgroup_mode=\\"unified\\"/' /etc/rc.conf"]
  - [sh, -c, "rc-update add cgroups sysinit 2>/dev/null || true"]
  # The image is fully baked: cloud-init must not spend seconds re-running
  # its stages on every phone boot.
  - [sh, -c, "for svc in cloud-init-local cloud-init cloud-init-network cloud-config cloud-final cloud-init-hotplugd; do rc-update del $svc boot 2>/dev/null; rc-update del $svc default 2>/dev/null; done; true"]
  - [touch, /etc/cloud/cloud-init.disabled]
  # Public resolvers, written after everything that could rewrite the file
  # has run for the last time.
  - [sh, -c, "printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > /etc/resolv.conf"]
  # A message of the day that says what this image is for.
  - [sh, -c, "printf '{motd_text}' > /etc/motd"]
  - [sh, -c, "fstrim -v / || true"]
  # The success marker the build harness looks for on the serial console.
  - [sh, -c, "echo '{BUILD_COMPLETE_MARKER}' > /dev/console 2>/dev/null || echo '{BUILD_COMPLETE_MARKER}'"]

power_state:
  mode: poweroff
  timeout: 120
  condition: true

final_message: "{BUILD_COMPLETE_MARKER}"
"""


def _container_runcmd_lines(username: str) -> str:
    """The runcmd lines only the container flavour needs, YAML-indented."""
    return (
        "  # Container tools work without sudo for the (single) user.\n"
        f'  - [sh, -c, "addgroup {username} docker 2>/dev/null || true"]\n'
        '  - [sh, -c, "rc-update add docker default"]\n'
        '  - [sh, -c, "rc-update add lxcfs default 2>/dev/null || true"]\n'
    )


def _motd_text(include_container_stack: bool) -> str:
    """printf-ready MOTD (\\n escapes) describing what the image is for."""
    if include_container_stack:
        return (
            "Linux on DeX — Alpine container host\\n\\n"
            "  docker  run --rm hello-world\\n"
            "  sudo podman run --rm docker.io/library/alpine echo hi\\n"
            "  lxc-checkconfig\\n\\n"
        )
    return (
        "Linux on DeX — Alpine (light)\\n\\n"
        "  The smallest, fastest-booting flavour. Install anything with:\\n"
        "  sudo apk add <package>\\n\\n"
    )
