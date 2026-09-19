//
// Created by weishu on 2022/12/9.
//

#ifndef KERNELSU_KSU_H
#define KERNELSU_KSU_H

#include <cstdint>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <utility>

#include "uapi/ksu.h"

uint32_t get_kernel_uapi_version();

uint32_t get_manager_uapi_version();

uint32_t get_version();

void refresh_info();

bool uid_should_umount(int uid);

bool is_safe_mode();

bool is_lkm_mode();

bool is_lkm_bundled();

bool is_late_load_mode();

bool is_manager();

bool is_pr_build();

// Release the inherited Seccomp filter from the current Manager task.
bool disable_current_seccomp();

using p_key_t = char[KSU_MAX_PACKAGE_NAME];

bool set_app_profile(const app_profile *profile);

int get_app_profile(app_profile *profile);

// Su compat
bool set_su_enabled(bool enabled);

bool is_su_enabled();

// Kernel umount
bool set_kernel_umount_enabled(bool enabled);

bool is_kernel_umount_enabled();

// WebView zygote umount
bool set_webview_zygote_umount_enabled(bool enabled);

bool is_webview_zygote_umount_enabled();

// SELinux hide
int set_selinux_hide_enabled(bool enabled);

bool is_selinux_hide_enabled();

bool is_selinux_hide_supported();

// AVC spoof
bool set_avc_spoof_enabled(bool enabled);

bool is_avc_spoof_enabled();

// Read-only kernel Hook status. UINT64_MAX means unsupported.
uint64_t get_kernel_hook_status();

// Read-only GKI Seccomp hook diagnostics.
uint64_t get_gki_seccomp_hook_status();
int32_t get_gki_seccomp_hook_last_error();
uint64_t get_gki_seccomp_hook_call_count();
uint64_t get_gki_seccomp_hook_release_count();
uint64_t get_gki_seccomp_hook_failure_count();

bool get_allow_list(struct ksu_new_get_allow_list_cmd *);

inline std::pair<int, int> legacy_get_info() {
    int32_t version = -1;
    int32_t flags = 0;
    int32_t result = 0;
    prctl(0xDEADBEEF, 2, &version, &flags, &result);
    return {version, flags};
}

#endif //KERNELSU_KSU_H
