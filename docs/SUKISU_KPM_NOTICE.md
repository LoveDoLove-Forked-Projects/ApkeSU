# SukiSU-compatible GKI KPM notice

This file is the source and compatibility notice for ApkeSU's Native GKI KPM
interface. It makes the upstream reference, implementation scope, licensing
boundary, and compatibility limits explicit.

## 中文说明

ApkeSU 的 Native GKI KPM 接口是参考
[SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) 的 GKI KPM 接口
和 KernelPatch/KPIMG 启动接管方式实现的兼容接口。ApkeSU 不是 SukiSU-Ultra
的官方发行版，也不代表 SukiSU-Ultra 作者背书或维护本项目。

本项目审计时使用的 SukiSU-Ultra 接口参考提交为
[`9fbe8fe8ca90c62c259c5894bf96d02ac31209b9`](https://github.com/SukiSU-Ultra/SukiSU-Ultra/commit/9fbe8fe8ca90c62c259c5894bf96d02ac31209b9)。
早期启动运行时和镜像注入工具使用固定的
[SukiSU_KernelPatch_patch](https://github.com/ShirkNeko/SukiSU_KernelPatch_patch)
源码快照，提交为
[`c5f0785dc7fac22a846eecc6392f34198d8e18b5`](https://github.com/ShirkNeko/SukiSU_KernelPatch_patch/commit/c5f0785dc7fac22a846eecc6392f34198d8e18b5)。
来源、许可证和本地修改记录见
[`third_party/kernelpatch/SOURCE.json`](../third_party/kernelpatch/SOURCE.json)。

### 接口范围

- `CONFIG_KPM` 仅支持 AArch64、内置 `CONFIG_KSU=y` 的 GKI 内核；
  `CONFIG_KSU=m` 的 LKM 模式不启用这条 Native GKI KPM 链路。
- `uapi/supercall.h` 声明了能力查询、后端类型、KPM 操作号和扩展 ioctl。
- `kernel/kpm/` 提供 `sukisu_kpm_*` ABI 桥接、参数边界检查、失败关闭的
  能力探测和安全占位实现。
- `kpimg` 在早期启动阶段解析内核符号并接管兼容 ABI；`kptools` 只负责向
  `boot.img` 的 kernel Image 注入运行时并重写入口。
- `init_boot.img`、`vendor_boot.img`、LKM 和晚加载模式不属于这条 Native
  GKI KPM 镜像链路。LKM 的 KPM 页面和加载后端仍由 KPatch-Next 单独提供。

### ApkeSU 特有部分

SukiSU 兼容的是 ABI 和加载思路，不等于复制整个 Manager 或运行时。以下
部分属于 ApkeSU 自有集成：

- 通过 ApkeSU supercall/JNI/ksud 进行能力查询和权限校验；
- Native GKI 与 KPatch-Next 使用互斥的后端识别和独立的管理路径；
- KPM 导入、启用、加载、卸载、控制和恢复标记由 ApkeSU 的状态机、日志和
  救砖恢复流程管理；
- 用户空间对路径、参数、名称、列表长度、镜像大小和操作状态进行边界校验；
- `boot-patch-kpimg` 只生成已校验的输出镜像，不负责 AVB 签名或刷写。

Native KPM 可执行内核代码，只有在确认来源可信、设备型号和 KMI 匹配并准备
好原始 boot 镜像和恢复方案后才应使用。任何兼容性、启动失败、无限重启或
数据损坏风险由使用者自行承担。

## English declaration

ApkeSU's Native GKI KPM interface is a compatibility implementation designed
with reference to the GKI KPM interface in
[SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) and the
KernelPatch/KPIMG early-boot takeover model. ApkeSU is not an official
SukiSU-Ultra distribution and this project is not endorsed or maintained by
the SukiSU-Ultra authors.

The audited SukiSU-Ultra interface reference is commit
[`9fbe8fe8ca90c62c259c5894bf96d02ac31209b9`](https://github.com/SukiSU-Ultra/SukiSU-Ultra/commit/9fbe8fe8ca90c62c259c5894bf96d02ac31209b9).
The early-boot runtime and image tools use the pinned
[SukiSU_KernelPatch_patch](https://github.com/ShirkNeko/SukiSU_KernelPatch_patch)
snapshot at commit
[`c5f0785dc7fac22a846eecc6392f34198d8e18b5`](https://github.com/ShirkNeko/SukiSU_KernelPatch_patch/commit/c5f0785dc7fac22a846eecc6392f34198d8e18b5).
See [`third_party/kernelpatch/SOURCE.json`](../third_party/kernelpatch/SOURCE.json)
for provenance, license data, excluded artifacts, and local hardening.

The compatibility surface is limited to the documented KPM capability query,
operation numbers, extension ioctls, and `sukisu_kpm_*` bridge symbols. Native
GKI requires an AArch64 kernel with built-in `CONFIG_KSU=y` and `CONFIG_KPM=y`.
LKM and late-load builds remain on the separate KPatch-Next backend. ApkeSU's
supercall permissions, ksud state machine, import validation, rescue markers,
logging, and UI integration are ApkeSU-specific and are not claimed as
SukiSU-Ultra code.

This notice does not relicense any upstream or vendored source. Every source
file remains subject to its own SPDX header, upstream license, and the notices
in [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) and
[`GPL-COMPLIANCE.md`](../GPL-COMPLIANCE.md).
