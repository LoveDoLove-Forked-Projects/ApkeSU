# ABK control integration

ApkeSU includes an optional in-kernel compatibility bridge for the public ABK
Control ABI. It is enabled with `CONFIG_ABK_CONTROL` (enabled by default in
this tree) and is exposed through the existing KernelSU supercall file
descriptor. The bridge does not create a new misc device or a discoverable
world-readable control node. The symbol is tristate: built-in GKI builds use
`y`, and external LKM builds inherit the `CONFIG_KSU=m` setting unless the
caller explicitly passes `CONFIG_ABK_CONTROL=n`.

## Manager identity

The ABK manager is accepted only when all of these values match the kernel
build configuration:

- package: `com.abk.kernel`
- APK v2 certificate size: `1407` bytes
- certificate SHA-256:
  `34e5e843952277759603cd0f949770b24c868530d80d7baeff08776a7e132b16`

The identity is assigned signature slot `253`. ApkeSU remains the primary
manager in slot `0`; the manager registry can hold both identities. If no
primary manager is present, the first registered manager is used as the
fallback app ID for APIs that require one. The package name and certificate
digest are checked together.

The certificate parser limit is raised to `2048` bytes when this bridge is
enabled so the official 1407-byte certificate can be checked. A release APK
must still be signed with the certificate configured in
`dist/manager_identity.mk`.

## Supercall ABI

The public ABI is defined in `uapi/abk_control.h` and uses the following
commands:

```c
#define ABK_CONTROL_IOCTL_MAGIC 0xa7
#define ABK_CONTROL_IOCTL_GET_STATUS _IOWR(0xa7, 0x41, struct abk_control_status_cmd)
#define ABK_CONTROL_IOCTL_RUN_COMMAND _IOW(0xa7, 0x42, struct abk_control_command_cmd)
```

The status request uses a two-pass buffer protocol. Set `data` to a userspace
buffer and `data_len` to its capacity. On success, `data_len` is the number of
JSON bytes written. If the buffer is missing or too small, the ioctl returns
`-ENOSPC` and writes the required length back when the command structure itself
is writable. The status document is capped at 64 KiB.

The command request accepts at most 160 bytes and supports:

```text
status <id>
enable <id>
disable <id>
command <id> <payload>
```

Both commands use the existing `manager_or_root` permission check. There is
no public `0x64` ABK ioctl in the referenced ABK ABI; this implementation does
not claim compatibility with an undocumented command number.

## Kernel provider API

An in-kernel feature or dynamically loaded module can expose runtime state by
including `<linux/abk_control.h>` and registering a stable, unique
`struct abk_control_ops` with `abk_control_register()`. The callback table can
report enabled state, toggle the feature, and handle a command payload. A
provider must call `abk_control_unregister()` before its code or data is
unloaded. The bridge holds an active reference while invoking a callback and
waits for active callbacks during unregister. A provider must not call
`abk_control_unregister()` from one of its own callbacks: unregister waits for
that callback's active reference and would deadlock. Schedule teardown from a
separate context after the callback returns instead.

The built-in manifest contains a read-only `abk_control` entry. Additional
runtime providers are merged into the schema-6 status JSON. The returned JSON
buffer belongs to the caller and must be released with `kfree()`.

## Build and safety notes

`CONFIG_ABK_CONTROL` can be disabled for kernels that must not trust the ABK
manager. The configuration values can be overridden by the usual Kbuild
variables, but package, certificate size, and SHA-256 must be changed as a
complete set. The bridge is not a replacement for the existing ApkeSU manager
authentication and does not weaken root permission checks for unrelated
supercalls.

This integration has been statically audited against the public ABK Control
reference at commit `35afa589fcdce23dd2e17d3d1c610b035043cac7`. A matching
kernel source tree and toolchain are required to produce a real KO or boot
image; this repository change alone is not evidence that a device has been
booted with the feature.
