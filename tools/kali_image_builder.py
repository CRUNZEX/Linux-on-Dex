#!/usr/bin/env python3
"""Bake a ready-to-boot Kali Linux ARM64 server image for Linux on DeX.

Kali publishes a `cloud-genericcloud-arm64` image: the same distribution
already installed into a disk, meant for exactly this kind of virtual
machine, and shipping cloud-init. That makes it a drop-in for the recipe the
other flavours use.

Kali is Debian-based and runs systemd, so it reuses the server cloud-config
verbatim (account, autologin consoles, public DNS, console size sync,
service trimming). Everything specific to Kali lives in this file:

  - the console-only package set is left exactly as published, because the
    point of this flavour is a *light* image that boots fast; tools are one
    `sudo apt install` away once it runs,
  - a few desktop/laptop services that a phone VM never needs are masked,
  - the image is baked here rather than configured on the phone, so the
    first boot on the device is as quick as every later one.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from cloud_image_bake import (
    BAKE_COMPLETE_MARKER,
    BakeBootRequest,
    bake_cloud_image,
    compact_into,
    prepare_staging_disk,
)


@dataclass(frozen=True)
class KaliBuildRequest:
    base_cloud_image: Path
    output_disk: Path
    work_directory: Path
    disk_size: str
    username: str
    password: str
    build_timeout_seconds: int = 2400


# Hardware a phone VM does not have, and chores it should not run. Each
# line is guarded, so a service missing from a future Kali release logs and
# moves on instead of failing the bake.
KALI_EXTRA_RUNCMD = """  # Kali ships desktop/laptop plumbing this VM has no use for.
  - [sh, -c, "systemctl disable --now NetworkManager-wait-online.service 2>/dev/null || true"]
  - [sh, -c, "systemctl mask ModemManager.service wpa_supplicant.service bluetooth.service 2>/dev/null || true"]
  - [sh, -c, "systemctl disable --now e2scrub_all.timer man-db.timer 2>/dev/null || true"]
  # Report what the finished image actually carries, so the build log shows
  # whether the published base drifted.
  - [sh, -c, "echo \\"kali packages installed: $(dpkg --get-selections | wc -l)\\" > /dev/console"]
  # Shrink what the image has to carry onto the phone.
  - [sh, -c, "apt-get clean"]
  - [sh, -c, "rm -rf /var/lib/apt/lists/*"]
  - [sh, -c, "printf 'Linux on DeX — Kali Linux (server)\\\\n\\\\n  Console-only and deliberately light. Add tools with:\\\\n  sudo apt update && sudo apt install <tool>\\\\n  sudo apt install kali-linux-headless   # the usual toolset\\\\n\\\\n' > /etc/motd"]
  - [sh, -c, "fstrim -av || true"]
"""


def log(message: str) -> None:
    print(f"[kali] {message}", flush=True)


def build_kali_image(request: KaliBuildRequest) -> Path:
    """Runs the whole bake and returns the finished disk."""
    from build_ready_vm import build_seed_iso_from_documents, render_meta_data, render_user_data

    request.work_directory.mkdir(parents=True, exist_ok=True)
    staging_disk = request.work_directory / "kali-build.qcow2"
    seed_iso = request.work_directory / "kali-build-seed.iso"

    log(f"preparing staging disk ({request.disk_size})")
    prepare_staging_disk(request.base_cloud_image, staging_disk, request.disk_size)

    build_seed_iso_from_documents(
        output_iso=seed_iso,
        user_data=render_user_data(
            request.username,
            request.password,
            bake_mode=True,
            extra_runcmd=KALI_EXTRA_RUNCMD,
        ),
        meta_data=render_meta_data(),
    )

    bake_cloud_image(
        BakeBootRequest(
            staging_disk=staging_disk,
            seed_iso=seed_iso,
            work_directory=request.work_directory,
            success_marker=BAKE_COMPLETE_MARKER,
            # Kali's first-boot work (account, growpart, apt cleanup) fits
            # comfortably in 2 GB; the phone can give the VM whatever it likes.
            memory_mb=2048,
            timeout_seconds=request.build_timeout_seconds,
        ),
        log=log,
    )

    log("compacting the finished image")
    compact_into(staging_disk, request.output_disk)
    log(f"kali image ready: {request.output_disk.name} "
        f"({request.output_disk.stat().st_size >> 20} MiB)")
    return request.output_disk
