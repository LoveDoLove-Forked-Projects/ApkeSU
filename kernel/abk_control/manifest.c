// SPDX-License-Identifier: GPL-2.0
#include <linux/kernel.h>

#include <linux/abk_control.h>

#define ABK_STRINGIFY_INNER(value) #value
#define ABK_STRINGIFY(value) ABK_STRINGIFY_INNER(value)

#ifdef KSU_VERSION
#define ABK_KERNELSU_VERSION ABK_STRINGIFY(KSU_VERSION)
#else
#define ABK_KERNELSU_VERSION "unknown"
#endif

#ifdef MODULE
#define ABK_WORK_MODE "lkm"
#else
#define ABK_WORK_MODE "built-in"
#endif

#ifdef CONFIG_KPM
#define ABK_USE_KPM true
#else
#define ABK_USE_KPM false
#endif

const struct abk_control_manifest_entry abk_control_manifest[] = {
    {
        .id = "abk_control",
        .name = "ABK Control",
        .version = "1.0.0",
        .description = "ABK metadata and runtime control bridge",
        .repo_url = "https://github.com/xingguangcuican6666/ABK_control_module",
        .stage = "builtin",
        .entry_kind = "builtin",
        .extension_id = "",
        .companion_package = "",
        .companion_display_name = "",
        .companion_asset_name = "",
        .companion_download_url = "",
        .service_activity = "",
        .group_id = "",
        .group_name = "",
        .group_role = "",
        .group_description = "",
        .group_repo_url = "",
        .requires_companion_app = false,
        .settings_supported = false,
        .per_app_supported = false,
        .oobe_priority = 0,
    },
};

const size_t abk_control_manifest_count = ARRAY_SIZE(abk_control_manifest);

const struct abk_control_build_info abk_control_build = {
    .abk_version = "1.0.0",
    .abk_commit = "unknown",
    .work_mode = ABK_WORK_MODE,
    .android_version = "unknown",
    .kernel_version = "unknown",
    .sub_level = "",
    .os_patch_level = "",
    .revision = "",
    .kernelsu_variant = "ApkeSU",
    .kernelsu_branch = "ApkeSU",
    .version = ABK_KERNELSU_VERSION,
    .build_time = "",
    .virtualization_support = "",
    .zram_extra_algos = "",
    .features = {
        .use_zram = false,
        .use_bbg = false,
        .use_ddk = false,
        .use_ntsync = false,
        .use_networking = false,
        .use_kpm = ABK_USE_KPM,
        .use_rekernel = false,
        .enable_susfs = false,
        .supp_op = false,
        .zram_full_algo = false,
    },
};
