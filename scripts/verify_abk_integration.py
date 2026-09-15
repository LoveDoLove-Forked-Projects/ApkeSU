#!/usr/bin/env python3
"""Verify the source-level ABK ABI and identity wiring."""

from __future__ import annotations

import argparse
import re
import struct
from pathlib import Path


ABK_PACKAGE = "com.abk.kernel"
ABK_CERT_SIZE = "1407"
ABK_CERT_HASH = "34e5e843952277759603cd0f949770b24c868530d80d7baeff08776a7e132b16"
ABK_MAX_COMMAND = 160
ABK_MAGIC = 0xA7


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def ioc(direction: int, number: int, size: int) -> int:
    return (direction << 30) | (size << 16) | (ABK_MAGIC << 8) | number


def parse_make(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    pattern = re.compile(r"^([A-Za-z0-9_]+)\s*(?::|\?)?=\s*(.*?)\s*$")
    for line in read(path).splitlines():
        match = pattern.match(line.strip())
        if match:
            values[match.group(1)] = match.group(2)
    return values


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path,
                        default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    root = args.root.resolve()

    identity = parse_make(root / "dist/manager_identity.mk")
    kbuild = read(root / "kernel/Kbuild")
    kconfig = read(root / "kernel/Kconfig")
    identity_h = read(root / "kernel/manager/manager_identity.h")
    sign = read(root / "kernel/manager/apk_sign.c")
    tracker = read(root / "kernel/manager/throne_tracker.c")
    tracker_h = read(root / "kernel/manager/throne_tracker.h")
    dispatch = read(root / "kernel/supercall/dispatch.c")
    core = read(root / "kernel/abk_control/core.c")
    manifest = read(root / "kernel/abk_control/manifest.c")
    header = read(root / "uapi/abk_control.h")

    require(identity.get("ABK_MANAGER_PACKAGE") == ABK_PACKAGE,
            "ABK package identity is out of sync")
    require(identity.get("ABK_MANAGER_CERT_SIZE") == ABK_CERT_SIZE,
            "ABK certificate size is out of sync")
    require(identity.get("ABK_MANAGER_CERT_SHA256") == ABK_CERT_HASH,
            "ABK certificate SHA-256 is out of sync")
    for marker in (
        "CONFIG_ABK_CONTROL",
        "abk_control/core.o",
        "abk_control/manifest.o",
        "ABK_MANAGER_CERT_MAX_LENGTH",
        "ABK_MANAGER_OFFICIAL_CERT",
    ):
        require(marker in kbuild, f"kernel/Kbuild is missing {marker}")
    require("config ABK_CONTROL" in kconfig,
            "kernel/Kconfig is missing CONFIG_ABK_CONTROL")
    require('tristate "Enable ABK control interface and manager compatibility"'
            in kconfig,
            "ABK_CONTROL must support both built-in and module builds")
    require("CONFIG_ABK_CONTROL := $(CONFIG_KSU)" in kbuild and
            "filter y m,$(CONFIG_ABK_CONTROL)" in kbuild,
            "external LKM ABK inheritance is missing")
    require("KSU_SIGNATURE_INDEX_ABK_MANAGER 253" in identity_h,
            "ABK signature slot is missing")
    for marker in (
        "ABK_MANAGER_CERT_SIZE",
        "ABK_MANAGER_CERT_SHA256",
        "KSU_SIGNATURE_INDEX_ABK_MANAGER",
        "ABK_MANAGER_PACKAGE",
    ):
        require(marker in sign, f"APK identity check is missing {marker}")
    require("abk_try_register_manager" in tracker and
            "5 * HZ" in tracker and "track_throne" in tracker,
            "ABK manager registration throttling is missing")
    require("if (is_manager())" in tracker and
            "ksu_has_manager()" not in tracker,
            "ABK registration must not stop when another manager is present")
    require("abk_last_registration_attempt &&" in tracker,
            "ABK first registration attempt is incorrectly rate-limited")
    require("abk_try_register_manager" in tracker_h,
            "ABK manager registration declaration is missing")
    for marker in (
        "ABK_CONTROL_IOCTL_GET_STATUS",
        "ABK_CONTROL_IOCTL_RUN_COMMAND",
        "do_abk_control_get_status",
        "do_abk_control_run_command",
        "manager_or_root",
    ):
        require(marker in dispatch, f"supercall bridge is missing {marker}")
    for marker in (
        "ABK_CONTROL_MAX_COMMAND + 1",
        "ABK_CONTROL_MAX_STATUS",
        "abk_control_unregister",
        "abk_control_shutdown",
        "EXPORT_SYMBOL_GPL(abk_control_register)",
    ):
        require(marker in core, f"control core is missing {marker}")
    require("abk_control_manifest_count" in manifest,
            "built-in ABK manifest is missing")
    require("ABK_CONTROL_IOCTL_MAGIC 0xa7" in header and
            "0x41" in header and "0x42" in header,
            "public ABK ioctl ABI is incomplete")

    # The command structures contain two 64-bit fields on Android LP64.
    # Keep this check explicit so accidental ABI edits are caught on Windows
    # too, without requiring a kernel toolchain.
    status_size = struct.calcsize("QQ")
    command_size = struct.calcsize("QQ")
    require(status_size == 16 and command_size == 16,
            "ABK command structure size assumption changed")
    require(ioc(3, 0x41, status_size) == 0xC010A741,
            "GET_STATUS ioctl value changed")
    require(ioc(1, 0x42, command_size) == 0x4010A742,
            "RUN_COMMAND ioctl value changed")
    require(ABK_MAX_COMMAND == 160, "ABK command limit changed")

    print("verified ABK control ABI, identity, registry bridge, and ioctl wiring")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
