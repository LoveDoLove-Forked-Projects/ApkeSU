# ApkeSU Native KPM image path

ApkeSU Native KPM is a GKI early-boot path. It is separate from the existing
LKM and KPatch-Next path:

```text
boot.img kernel Image
    -> kptools -p appends kpimg and rewrites the kernel entry
    -> kpimg resolves early kernel symbols and hooks sukisu_kpm_*
    -> the original kernel entry continues
```

The hook bridge is the Native KPM ABI already exported by ApkeSU's built-in
kernel implementation. The early runtime is not loaded by `ksud` and must be
present before the kernel reaches the normal Android userspace.

The boot kernel must be AArch64 and built with `CONFIG_KSU=y` and
`CONFIG_KPM=y`. LKM (`CONFIG_KSU=m`) and late-load builds do not expose this
Native GKI path; they remain separate from KPatch-Next.

For upstream attribution, interface scope, licensing boundaries, and the
distinction between the Native GKI and LKM backends, see the
[SukiSU-compatible GKI KPM notice](SUKISU_KPM_NOTICE.md).

## Build

The source snapshot is recorded in `third_party/kernelpatch/SOURCE.json`.
The build is isolated under `out/` and installs two AArch64 Android assets:

```powershell
$env:ANDROID_NDK_HOME = 'F:\ApkeSU-Toolchain\AndroidNDK\29.0.14206865'
pwsh -File scripts/build_kernelpatch.ps1
```

On Linux:

```sh
export ANDROID_NDK_HOME=/opt/android-ndk
scripts/build_kernelpatch.sh
```

The scripts print SHA-256 values and install:

```text
userspace/ksud/bin/aarch64/kpimg
userspace/ksud/bin/aarch64/kptools
```

`kpimg` is a raw binary with the `KP1158` header. `kptools` is a static
AArch64 ELF executable and is only executable on an AArch64 Android device.
Both scripts set `SOURCE_DATE_EPOCH` to the vendored source commit time by
default, so identical source and toolchains produce stable assets. Set the
environment variable explicitly when a release requires another timestamp.

## Patch a boot image

The userspace CLI has an explicit command so the existing ramdisk/LKM patcher
cannot be selected accidentally:

```text
ksud boot-patch-kpimg --boot stock-boot.img --output apkesu-kpimg-boot.img
```

Use `--kpimg` and `--kptools` to override the embedded/device assets. On
Android the embedded defaults are extracted into a unique private temporary
directory beside the requested output and removed when the command exits. The
command:

1. parses an Android boot image and requires a kernel block;
2. decompresses the kernel and verifies an uncompressed ARM64 `Image` header;
3. validates both tools before invoking `kptools` with argument arrays;
4. verifies the patched kernel contains `KP1158`;
5. recompresses the kernel using the original boot-image compression;
6. reparses the output and atomically publishes it only after verification.

`init_boot.img` and `vendor_boot.img` are rejected because they do not carry
the kernel entry that this early takeover requires. The output is not AVB
signed and no flashing is performed. Always start from the device's original,
matching `boot.img`; an already injected image is rejected.

To inspect an image without modifying it:

```text
ksud boot-info-kpimg --boot apkesu-kpimg-boot.img
```

The command reports whether a kernel exists, its uncompressed size, ARM64
validity, and the `kpimg` marker offset. A successful host-side patch still
does not prove a device will boot: AVB signing, vendor boot layout, kernel
KMI, page size, and OEM boot-chain policy must be verified separately.

## Source provenance

The SukiSU-Ultra integration reference and the vendored KernelPatch source are
pinned rather than fetched during a release build:

```text
SukiSU-Ultra reference: 9fbe8fe8ca90c62c259c5894bf96d02ac31209b9
KernelPatch source:     c5f0785dc7fac22a846eecc6392f34198d8e18b5
```

`third_party/kernelpatch/SOURCE.json` records the source, license, excluded
files, and local hardening applied to the runtime and image patcher.
