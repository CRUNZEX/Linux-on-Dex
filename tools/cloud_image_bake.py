#!/usr/bin/env python3
"""Boot a cloud image once on this machine so the phone gets a finished system.

Every baked flavour follows the same recipe: copy the base cloud image to a
staging disk, hand it a one-shot cloud-init seed, boot it under hardware
acceleration until cloud-init powers the guest off, then compact the result.
This module owns that boot; each flavour owns its own cloud-config.

Baking matters because the alternative, configuring on the phone at first
boot, costs minutes of software-emulated CPU for work a laptop finishes in
seconds.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from desktop_image_builder import (
    _build_cpu_count,
    _create_efi_variables_store,
    _locate_uefi_firmware,
    _pick_accelerator,
    _read_console,
    _require_tool,
    _run_checked,
)


class ImageBakeError(RuntimeError):
    """A bake failed; the message is meant for the person running the build."""


# Printed by cloud-init as its final message; the harness waits for it and
# treats its absence as a failed bake.
BAKE_COMPLETE_MARKER = "Linux on DeX bake complete."


@dataclass(frozen=True)
class BakeBootRequest:
    """Everything the one-shot build boot needs."""

    staging_disk: Path
    seed_iso: Path
    work_directory: Path
    """Console text that proves cloud-init reached the end of its work."""
    success_marker: str
    memory_mb: int = 2048
    timeout_seconds: int = 1800
    """Raw scratch disks attached after the seed (guest: /dev/vdc, /dev/vdd…).

    Used by builds whose product is not the boot disk itself — the PRoot
    rootfs bake streams a tar archive onto one of these.
    """
    extra_raw_disks: tuple[Path, ...] = ()


def prepare_staging_disk(
    base_image: Path,
    staging_disk: Path,
    disk_size: str,
    log: Callable[[str], None] = lambda message: None,
) -> None:
    """Copies the base cloud image aside and grows it to the target size.

    Growing only. Base images differ widely in how much room they declare
    (Alpine ships 1 GiB, Kali 25 GiB), and shrinking one would cut off the
    filesystem inside it. A request smaller than the base is honoured as
    "leave it alone": qcow2 is sparse, so unused room costs nothing.
    """
    qemu_img = _require_tool("qemu-img")
    staging_disk.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(base_image, staging_disk)

    requested_bytes = parse_disk_size(disk_size)
    current_bytes = _virtual_size_bytes(qemu_img, staging_disk)
    if requested_bytes <= current_bytes:
        log(f"base image already offers {current_bytes >> 30} GiB; keeping it")
        return
    _run_checked(
        [qemu_img, "resize", str(staging_disk), disk_size],
        failure_message="resizing the staging disk",
    )


def parse_disk_size(disk_size: str) -> int:
    """Turns a qemu-img size such as "12G" or "512M" into bytes."""
    text = disk_size.strip().upper().rstrip("B")
    multipliers = {"K": 1 << 10, "M": 1 << 20, "G": 1 << 30, "T": 1 << 40}
    if text and text[-1] in multipliers:
        number, multiplier = text[:-1], multipliers[text[-1]]
    else:
        number, multiplier = text, 1
    try:
        return int(float(number) * multiplier)
    except ValueError as unparsable:
        raise ImageBakeError(f"cannot understand disk size '{disk_size}'") from unparsable


def _virtual_size_bytes(qemu_img: str, disk: Path) -> int:
    result = subprocess.run(
        [qemu_img, "info", "--output=json", str(disk)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ImageBakeError(f"reading the staging disk failed: {result.stderr.strip()}")
    return int(json.loads(result.stdout)["virtual-size"])


def compact_into(staging_disk: Path, output_disk: Path) -> None:
    """Rewrites the baked disk so trimmed blocks stop taking space."""
    qemu_img = _require_tool("qemu-img")
    output_disk.parent.mkdir(parents=True, exist_ok=True)
    output_disk.unlink(missing_ok=True)
    _run_checked(
        [qemu_img, "convert", "-O", "qcow2", str(staging_disk), str(output_disk)],
        failure_message="compacting the finished image",
    )


def bake_cloud_image(request: BakeBootRequest, log: Callable[[str], None]) -> None:
    """Boots the guest until cloud-init powers it off, then checks it worked."""
    qemu_system = _require_tool("qemu-system-aarch64")
    firmware = _locate_uefi_firmware()

    request.work_directory.mkdir(parents=True, exist_ok=True)
    variables_store = request.work_directory / "bake-efi-vars.fd"
    _create_efi_variables_store(variables_store)
    console_log = request.work_directory / "bake-console.log"
    console_log.unlink(missing_ok=True)

    accelerator = _pick_accelerator()
    command = _build_qemu_command(
        qemu_system=qemu_system,
        firmware=firmware,
        variables_store=variables_store,
        console_log=console_log,
        accelerator=accelerator,
        request=request,
    )

    log(f"baking in a {accelerator}-accelerated guest (a few minutes)")
    started_at = time.monotonic()
    process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        _wait_for_poweroff(process, request, console_log, started_at, log)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=30)

    _require_marker_on_console(console_log, request.success_marker)
    log(f"bake finished in {int(time.monotonic() - started_at)}s")


def _build_qemu_command(
    qemu_system: str,
    firmware: Path,
    variables_store: Path,
    console_log: Path,
    accelerator: str,
    request: BakeBootRequest,
) -> list[str]:
    command = [
        qemu_system,
        "-machine", "virt",
        "-accel", accelerator,
        # "host" is only meaningful with a hardware accelerator.
        "-cpu", "host" if accelerator in ("hvf", "kvm") else "max",
        "-smp", str(_build_cpu_count()),
        "-m", str(request.memory_mb),
        "-nodefaults",
        "-drive", f"if=pflash,format=raw,readonly=on,file={firmware}",
        "-drive", f"if=pflash,format=raw,file={variables_store}",
        "-drive", f"if=none,id=root,format=qcow2,discard=unmap,file={request.staging_disk}",
        "-device", "virtio-blk-pci,drive=root,bootindex=0",
        "-drive", f"if=none,id=seed,format=raw,readonly=on,file={request.seed_iso}",
        "-device", "virtio-blk-pci,drive=seed",
    ]
    for index, extra_disk in enumerate(request.extra_raw_disks):
        command += [
            "-drive", f"if=none,id=extra{index},format=raw,file={extra_disk}",
            "-device", f"virtio-blk-pci,drive=extra{index}",
        ]
    command += [
        "-netdev", "user,id=net0",
        "-device", "virtio-net-pci,netdev=net0",
        "-device", "virtio-rng-pci",
        "-display", "none",
        "-serial", f"file:{console_log}",
    ]
    return command


def _wait_for_poweroff(
    process: subprocess.Popen,
    request: BakeBootRequest,
    console_log: Path,
    started_at: float,
    log: Callable[[str], None],
) -> None:
    """Waits for the guest to power itself off, reporting progress."""
    next_report_at = PROGRESS_REPORT_INTERVAL_SECONDS
    while process.poll() is None:
        elapsed = time.monotonic() - started_at
        if elapsed > request.timeout_seconds:
            raise ImageBakeError(
                f"the build guest did not finish within {request.timeout_seconds}s. "
                f"Console log: {console_log}"
            )
        if elapsed >= next_report_at:
            next_report_at += PROGRESS_REPORT_INTERVAL_SECONDS
            log(f"  still baking… {int(elapsed)}s — {_summarise_console(console_log)}")
        time.sleep(POLL_INTERVAL_SECONDS)


def _require_marker_on_console(console_log: Path, success_marker: str) -> None:
    console_text = _read_console(console_log)
    if success_marker in console_text:
        return
    tail = console_text[-1500:] if console_text else "(console log empty)"
    raise ImageBakeError(
        f"cloud-init never reported success ('{success_marker}').\n"
        f"Console tail:\n{tail}"
    )


def _summarise_console(console_log: Path) -> str:
    """Last meaningful console line, for a progress hint."""
    text = _read_console(console_log)
    for line in reversed(text.splitlines()):
        stripped = "".join(character for character in line if character.isprintable()).strip()
        if len(stripped) > 12:
            return stripped[:90]
    return "booting"


POLL_INTERVAL_SECONDS = 5
PROGRESS_REPORT_INTERVAL_SECONDS = 60
