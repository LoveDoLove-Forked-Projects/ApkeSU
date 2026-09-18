#include <linux/capability.h>
#include <linux/atomic.h>
#include <linux/cred.h>
#include <linux/slab.h>
#include <linux/uaccess.h>
#include <linux/version.h>
#include <linux/thread_info.h>
#ifdef CONFIG_KSU_SUSFS
#include <linux/susfs.h>
#endif
#if IS_ENABLED(CONFIG_ABK_CONTROL)
#include <linux/abk_control.h>
#endif
#include "uapi/supercall.h"
#include "supercall/internal.h"
#include "arch.h" // IWYU pragma: keep
#include "policy/allowlist.h"
#include "policy/feature.h"
#include "klog.h" // IWYU pragma: keep
#include "ksu.h"
#include "runtime/ksud_boot.h"
#include "feature/dynamic_manager.h"
#include "feature/kernel_umount.h"
#include "manager/manager_identity.h"
#include "manager/throne_tracker.h"
#include "selinux/selinux.h"
#include "infra/file_wrapper.h"
#include "hook/tp_marker.h"
#include "policy/app_profile.h"
#include "sulog/event.h"
#include "sulog/fd.h"
#include "supercall/supercall.h"
#ifdef CONFIG_KPM
#include "kpm/kpm.h"
#endif

static int do_grant_root(void __user *arg)
{
    int ret;
    __u32 audit_uid = current_uid().val;
    __u32 audit_euid = current_euid().val;

    // we already check uid above on allowed_for_su()

    pr_info("allow root for: %d\n", audit_uid);
    ret = escape_with_root_profile();
    ksu_sulog_emit_grant_root(ret, audit_uid, audit_euid, GFP_KERNEL);

    return ret;
}

static int do_get_info(void __user *arg)
{
    struct ksu_get_info_cmd cmd = { .version = KERNEL_SU_VERSION, .flags = 0 };

#if IS_ENABLED(CONFIG_ABK_CONTROL)
    if (!is_manager())
        abk_try_register_manager();
#endif

#ifdef CONFIG_KPM
#ifndef MODULE
    cmd.flags |= KSU_GET_INFO_FLAG_NATIVE_KPM;
#endif
#endif

#ifdef MODULE
    cmd.flags |= KSU_GET_INFO_FLAG_LKM;
    if (ksu_bundled) {
        cmd.flags |= KSU_GET_INFO_FLAG_BUNDLED;
    }
#endif

    if (is_manager()) {
        cmd.flags |= KSU_GET_INFO_FLAG_MANAGER;
    }
    if (ksu_late_loaded) {
        cmd.flags |= KSU_GET_INFO_FLAG_LATE_LOAD;
    }
#ifdef EXPECTED_SIZE2
    cmd.flags |= KSU_GET_INFO_FLAG_PR_BUILD;
#endif
    cmd.features = KSU_FEATURE_MAX;
    cmd.uapi_version = KERNEL_SU_UAPI_VERSION;

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("get_version: copy_to_user failed\n");
        return -EFAULT;
    }

    return 0;
}

static int do_get_info_legacy(void __user *arg)
{
    struct ksu_get_info_legacy_cmd cmd = { .version = KERNEL_SU_VERSION, .flags = 0 };

#if IS_ENABLED(CONFIG_ABK_CONTROL)
    if (!is_manager())
        abk_try_register_manager();
#endif

#ifdef CONFIG_KPM
#ifndef MODULE
    cmd.flags |= KSU_GET_INFO_FLAG_NATIVE_KPM;
#endif
#endif

#ifdef MODULE
    cmd.flags |= KSU_GET_INFO_FLAG_LKM;
    if (ksu_bundled) {
        cmd.flags |= KSU_GET_INFO_FLAG_BUNDLED;
    }
#endif

    if (is_manager()) {
        cmd.flags |= KSU_GET_INFO_FLAG_MANAGER;
    }
    if (ksu_late_loaded) {
        cmd.flags |= KSU_GET_INFO_FLAG_LATE_LOAD;
    }
#ifdef EXPECTED_SIZE2
    cmd.flags |= KSU_GET_INFO_FLAG_PR_BUILD;
#endif
    cmd.features = KSU_FEATURE_MAX;

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("get_version: copy_to_user failed\n");
        return -EFAULT;
    }

    return 0;
}

static int do_report_event(void __user *arg)
{
    struct ksu_report_event_cmd cmd;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        return -EFAULT;
    }

    switch (cmd.event) {
    case EVENT_POST_FS_DATA: {
        static atomic_t post_fs_data_lock = ATOMIC_INIT(0);
        if (atomic_cmpxchg(&post_fs_data_lock, 0, 1) == 0) {
            if (ksu_late_loaded) {
                pr_info("post-fs-data skipped (late load)\n");
            } else {
                pr_info("post-fs-data triggered\n");
                on_post_fs_data();
            }
        }
        break;
    }
    case EVENT_BOOT_COMPLETED: {
        static atomic_t boot_complete_lock = ATOMIC_INIT(0);
        if (atomic_cmpxchg(&boot_complete_lock, 0, 1) == 0) {
            if (ksu_late_loaded) {
                pr_info("boot_complete skipped (late load)\n");
            } else {
                pr_info("boot_complete triggered\n");
                on_boot_completed();
#ifdef CONFIG_KSU_SUSFS
                susfs_start_sdcard_monitor_fn();
#endif
            }
        }
        break;
    }
    case EVENT_MODULE_MOUNTED: {
        pr_info("module mounted!\n");
        on_module_mounted();
        break;
    }
    default:
        break;
    }

    return 0;
}

static int do_set_sepolicy(void __user *arg)
{
    struct ksu_set_sepolicy_cmd cmd;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        return -EFAULT;
    }

    return handle_sepolicy((void __user *)cmd.data, cmd.data_len);
}

static int do_check_safemode(void __user *arg)
{
    struct ksu_check_safemode_cmd cmd;

    cmd.in_safe_mode = ksu_is_safe_mode();

    if (cmd.in_safe_mode) {
        pr_warn("safemode enabled!\n");
    }

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("check_safemode: copy_to_user failed\n");
        return -EFAULT;
    }

    return 0;
}

static int do_new_get_allow_list_common(void __user *arg, bool allow)
{
    struct ksu_new_get_allow_list_cmd cmd;
    int *arr = NULL;
    int err = 0;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        return -EFAULT;
    }

    if (cmd.count) {
        arr = kmalloc(sizeof(int) * cmd.count, GFP_KERNEL);
        if (!arr) {
            return -ENOMEM;
        }
    }

    bool success = ksu_get_allow_list(arr, cmd.count, &cmd.count, &cmd.total_count, allow);

    if (!success) {
        err = -EFAULT;
        goto out;
    }

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("new_get_allow_list: copy_to_user count failed\n");
        err = -EFAULT;
        goto out;
    }

    if (cmd.count && copy_to_user(&((struct ksu_new_get_allow_list_cmd *)arg)->uids, arr, sizeof(int) * cmd.count)) {
        pr_err("new_get_allow_list: copy_to_user uids failed\n");
        err = -EFAULT;
    }

out:
    if (arr) {
        kfree(arr);
    }
    return err;
}

static int do_new_get_deny_list(void __user *arg)
{
    return do_new_get_allow_list_common(arg, false);
}

static int do_new_get_allow_list(void __user *arg)
{
    return do_new_get_allow_list_common(arg, true);
}

static int do_get_allow_list_common(void __user *arg, bool allow)
{
    int *arr = NULL;
    int err = 0;
    u16 count;
    u32 out_count;
    static const u16 kSize = 128;

    arr = kmalloc(sizeof(int) * kSize, GFP_KERNEL);
    if (!arr) {
        return -ENOMEM;
    }

    bool success = ksu_get_allow_list(arr, kSize, &count, NULL, allow);

    if (!success) {
        err = -EFAULT;
        goto out;
    }

    out_count = count;

    if (copy_to_user(arg + offsetof(struct ksu_get_allow_list_cmd, count), &out_count, sizeof(u32))) {
        pr_err("get_allow_list: copy_to_user count failed\n");
        err = -EFAULT;
        goto out;
    }

    if (copy_to_user(arg, arr, sizeof(u32) * count)) {
        pr_err("get_allow_list: copy_to_user uids failed\n");
        err = -EFAULT;
    }

out:
    if (arr) {
        kfree(arr);
    }
    return err;
}

static int do_get_deny_list(void __user *arg)
{
    return do_get_allow_list_common(arg, false);
}

static int do_get_allow_list(void __user *arg)
{
    return do_get_allow_list_common(arg, true);
}

static int do_uid_granted_root(void __user *arg)
{
    struct ksu_uid_granted_root_cmd cmd;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        return -EFAULT;
    }

    cmd.granted = ksu_is_allow_uid_for_current(cmd.uid);

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("uid_granted_root: copy_to_user failed\n");
        return -EFAULT;
    }

    return 0;
}

static int do_uid_should_umount(void __user *arg)
{
    struct ksu_uid_should_umount_cmd cmd;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        return -EFAULT;
    }

    cmd.should_umount = ksu_uid_should_umount(cmd.uid);

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("uid_should_umount: copy_to_user failed\n");
        return -EFAULT;
    }

    return 0;
}

static int do_get_manager_appid(void __user *arg)
{
    struct ksu_get_manager_appid_cmd cmd;

    cmd.appid = ksu_get_manager_appid();

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("get_manager_appid: copy_to_user failed\n");
        return -EFAULT;
    }

    return 0;
}

static int do_set_manager_appid(void __user *arg)
{
    struct ksu_set_manager_appid_cmd cmd;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        pr_err("set_manager_appid: copy_from_user failed\n");
        return -EFAULT;
    }

    if (!ksu_is_normal_appid(cmd.appid)) {
        pr_err("set_manager_appid: invalid appid %u\n", cmd.appid);
        return -EINVAL;
    }

    if (ksu_is_manager_appid_valid()) {
        if (cmd.appid == ksu_get_manager_appid()) {
            return 0;
        }

        pr_warn("set_manager_appid: replacing manager appid %u with %u\n",
                ksu_get_manager_appid(), cmd.appid);
    }

    ksu_set_manager_appid(cmd.appid);
    pr_info("manager appid set by root: %u\n", cmd.appid);

    return 0;
}

static int do_dynamic_manager(void __user *arg)
{
    struct ksu_dynamic_manager_cmd cmd;
    int ret;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    ret = ksu_handle_dynamic_manager(&cmd);
    if (ret)
        return ret;

    if (cmd.operation == DYNAMIC_MANAGER_OP_SET ||
        cmd.operation == DYNAMIC_MANAGER_OP_SET_SYNCHRONOUS) {
        unsigned int flags = TRACK_THRONE_FORCE_SEARCH_MGR;

        if (cmd.operation == DYNAMIC_MANAGER_OP_SET_SYNCHRONOUS)
            flags |= TRACK_THRONE_FORCE_SYNCHRONOUS;
        track_throne(flags);
    }

    if (cmd.operation == DYNAMIC_MANAGER_OP_GET &&
        copy_to_user(arg, &cmd, sizeof(cmd)))
        return -EFAULT;
    return 0;
}

static int do_get_managers(void __user *arg)
{
    struct ksu_get_managers_cmd cmd;
    int ret;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    ret = ksu_handle_get_managers_cmd(arg, &cmd);
    if (ret)
        return ret;
    if (copy_to_user(arg, &cmd, sizeof(cmd)))
        return -EFAULT;
    return 0;
}

static int do_get_app_profile(void __user *arg)
{
#ifdef CONFIG_KSU_DISABLE_POLICY
    return -EOPNOTSUPP;
#endif
    uid_t uid;
    struct app_profile *profile;
    int ret = 0;

    if (copy_from_user(&uid, (char __user *)arg + offsetof(struct ksu_get_app_profile_cmd, profile.curr_uid),
                       sizeof(uid_t))) {
        pr_err("get_app_profile: copy_from_user failed\n");
        return -EFAULT;
    }

    rcu_read_lock();
    profile = ksu_get_app_profile(uid);
    rcu_read_unlock();
    if (!profile) {
        ret = -ENOENT;
    } else {
        if (copy_to_user((char __user *)arg + offsetof(struct ksu_get_app_profile_cmd, profile), profile,
                         sizeof(struct app_profile))) {
            pr_err("get_app_profile: copy_to_user failed\n");
            ret = -EFAULT;
        }
        ksu_put_app_profile(profile);
    }
    return ret;
}

static int do_set_app_profile(void __user *arg)
{
#ifdef CONFIG_KSU_DISABLE_POLICY
    return -EOPNOTSUPP;
#endif

    struct ksu_set_app_profile_cmd cmd;
    int ret;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        pr_err("set_app_profile: copy_from_user failed\n");
        return -EFAULT;
    }

    ret = ksu_set_app_profile(&cmd.profile);
    if (!ret) {
        ksu_persistent_allow_list();
#ifndef CONFIG_KSU_SUSFS
        ksu_mark_running_process();
#endif
    }
    return ret;
}

static int do_get_feature(void __user *arg)
{
    struct ksu_get_feature_cmd cmd;
    bool supported;
    int ret;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        pr_err("get_feature: copy_from_user failed\n");
        return -EFAULT;
    }

    ret = ksu_get_feature(cmd.feature_id, &cmd.value, &supported);
    cmd.supported = supported ? 1 : 0;

    if (ret && supported) {
        pr_err("get_feature: failed for feature %u: %d\n", cmd.feature_id, ret);
        return ret;
    }

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("get_feature: copy_to_user failed\n");
        return -EFAULT;
    }

    return 0;
}

static int do_set_feature(void __user *arg)
{
    struct ksu_set_feature_cmd cmd;
    int ret;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        pr_err("set_feature: copy_from_user failed\n");
        return -EFAULT;
    }

    ret = ksu_set_feature(cmd.feature_id, cmd.value);
    if (ret) {
        pr_err("set_feature: failed for feature %u: %d\n", cmd.feature_id, ret);
        return ret;
    }

    return 0;
}

static int do_get_wrapper_fd(void __user *arg)
{
    if (!ksu_file_sid) {
        return -EINVAL;
    }

    struct ksu_get_wrapper_fd_cmd cmd;
    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        pr_err("get_wrapper_fd: copy_from_user failed\n");
        return -EFAULT;
    }

    return ksu_install_file_wrapper(cmd.fd);
}

static int do_manage_mark(void __user *arg)
{
    struct ksu_manage_mark_cmd cmd;
#ifndef CONFIG_KSU_SUSFS
    int ret = 0;
#endif

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        pr_err("manage_mark: copy_from_user failed\n");
        return -EFAULT;
    }

    switch (cmd.operation) {
#ifdef CONFIG_KSU_SUSFS
    case KSU_MARK_GET:
        if (cmd.pid != 0 && cmd.pid != current->pid)
            return -EOPNOTSUPP;
        cmd.result = susfs_is_current_proc_no_su() ? 0 : 1;
        break;
    case KSU_MARK_MARK:
    case KSU_MARK_UNMARK:
    case KSU_MARK_REFRESH:
        if (cmd.pid != 0 && cmd.pid != current->pid)
            return -EOPNOTSUPP;
        break;
#else
    case KSU_MARK_GET: {
        // Get task mark status
        ret = ksu_get_task_mark(cmd.pid);
        if (ret < 0) {
            pr_err("manage_mark: get failed for pid %d: %d\n", cmd.pid, ret);
            return ret;
        }
        cmd.result = (u32)ret;
        break;
    }
    case KSU_MARK_MARK: {
        if (cmd.pid == 0) {
            ksu_mark_all_process();
        } else {
            ret = ksu_set_task_mark(cmd.pid, true);
            if (ret < 0) {
                pr_err("manage_mark: set_mark failed for pid %d: %d\n", cmd.pid, ret);
                return ret;
            }
        }
        break;
    }
    case KSU_MARK_UNMARK: {
        if (cmd.pid == 0) {
            ksu_unmark_all_process();
        } else {
            ret = ksu_set_task_mark(cmd.pid, false);
            if (ret < 0) {
                pr_err("manage_mark: set_unmark failed for pid %d: %d\n", cmd.pid, ret);
                return ret;
            }
        }
        break;
    }
    case KSU_MARK_REFRESH: {
        ksu_mark_running_process();
        pr_info("manage_mark: refreshed running processes\n");
        break;
    }
#endif
    default: {
        pr_err("manage_mark: invalid operation %u\n", cmd.operation);
        return -EINVAL;
    }
    }
    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("manage_mark: copy_to_user failed\n");
        return -EFAULT;
    }

    return 0;
}

#ifdef CONFIG_KSU_SUSFS
int ksu_handle_sys_reboot(int magic1, int magic2, unsigned int cmd,
                          void __user **arg)
{
    if (magic1 != KSU_INSTALL_MAGIC1)
        return -EINVAL;

    if (magic2 == SUSFS_MAGIC && current_uid().val == 0) {
        switch (cmd) {
#ifdef CONFIG_KSU_SUSFS_SUS_PATH
        case CMD_SUSFS_ADD_SUS_PATH:
            susfs_add_sus_path(arg);
            return 0;
        case CMD_SUSFS_ADD_SUS_PATH_LOOP:
            susfs_add_sus_path_loop(arg);
            return 0;
#endif
#ifdef CONFIG_KSU_SUSFS_SUS_MOUNT
        case CMD_SUSFS_HIDE_SUS_MNTS_FOR_NON_SU_PROCS:
            susfs_set_hide_sus_mnts_for_non_su_procs(arg);
            return 0;
#endif
#ifdef CONFIG_KSU_SUSFS_SUS_KSTAT
        case CMD_SUSFS_ADD_SUS_KSTAT:
            susfs_add_sus_kstat(arg);
            return 0;
        case CMD_SUSFS_UPDATE_SUS_KSTAT:
            susfs_update_sus_kstat(arg);
            return 0;
        case CMD_SUSFS_ADD_SUS_KSTAT_STATICALLY:
            susfs_add_sus_kstat(arg);
            return 0;
#endif
#ifdef CONFIG_KSU_SUSFS_SPOOF_UNAME
        case CMD_SUSFS_SET_UNAME:
            susfs_set_uname(arg);
            return 0;
#endif
#ifdef CONFIG_KSU_SUSFS_ENABLE_LOG
        case CMD_SUSFS_ENABLE_LOG:
            susfs_enable_log(arg);
            return 0;
#endif
#ifdef CONFIG_KSU_SUSFS_SPOOF_CMDLINE_OR_BOOTCONFIG
        case CMD_SUSFS_SET_CMDLINE_OR_BOOTCONFIG:
            susfs_set_cmdline_or_bootconfig(arg);
            return 0;
#endif
#ifdef CONFIG_KSU_SUSFS_OPEN_REDIRECT
        case CMD_SUSFS_ADD_OPEN_REDIRECT:
            susfs_add_open_redirect(arg);
            return 0;
#endif
#ifdef CONFIG_KSU_SUSFS_SUS_MAP
        case CMD_SUSFS_ADD_SUS_MAP:
            susfs_add_sus_map(arg);
            return 0;
#endif
        case CMD_SUSFS_ENABLE_AVC_LOG_SPOOFING:
            susfs_set_avc_log_spoofing(arg);
            return 0;
        case CMD_SUSFS_SHOW_ENABLED_FEATURES:
            susfs_get_enabled_features(arg);
            return 0;
        case CMD_SUSFS_SHOW_VARIANT:
            susfs_show_variant(arg);
            return 0;
        case CMD_SUSFS_SHOW_VERSION:
            susfs_show_version(arg);
            return 0;
        default:
            return -EINVAL;
        }
    }

    if (magic2 == KSU_INSTALL_MAGIC2)
        return ksu_supercall_reboot_handler(arg);

    return -EINVAL;
}
#endif

static int do_nuke_ext4_sysfs(void __user *arg)
{
    struct ksu_nuke_ext4_sysfs_cmd cmd;
    char mnt[256];
    long ret;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    if (!cmd.arg)
        return -EINVAL;

    memset(mnt, 0, sizeof(mnt));

    ret = strncpy_from_user(mnt, cmd.arg, sizeof(mnt));
    if (ret < 0) {
        pr_err("nuke ext4 copy mnt failed: %ld\n", ret);
        return -EFAULT;
    }

    if (ret == sizeof(mnt)) {
        pr_err("nuke ext4 mnt path too long\n");
        return -ENAMETOOLONG;
    }

    pr_info("do_nuke_ext4_sysfs: %s\n", mnt);

    return nuke_ext4_sysfs(mnt);
}

struct list_head mount_list = LIST_HEAD_INIT(mount_list);
DECLARE_RWSEM(mount_list_lock);

static int add_try_umount(void __user *arg)
{
    struct mount_entry *new_entry, *entry, *tmp;
    struct ksu_add_try_umount_cmd cmd;
    char buf[256] = { 0 };

    if (copy_from_user(&cmd, arg, sizeof cmd))
        return -EFAULT;

    switch (cmd.mode) {
    case KSU_UMOUNT_WIPE: {
        struct mount_entry *entry, *tmp;
        down_write(&mount_list_lock);
        list_for_each_entry_safe (entry, tmp, &mount_list, list) {
            pr_info("wipe_umount_list: removing entry: %s\n", entry->umountable);
            list_del(&entry->list);
            kfree(entry->umountable);
            kfree(entry);
        }
        up_write(&mount_list_lock);

        return 0;
    }

    case KSU_UMOUNT_ADD: {
        long len = strncpy_from_user(buf, (const char __user *)cmd.arg, 256);
        if (len <= 0)
            return -EFAULT;

        if (len >= sizeof(buf))
            return -ENAMETOOLONG;

        buf[sizeof(buf) - 1] = '\0';

        new_entry = kzalloc(sizeof(*new_entry), GFP_KERNEL);
        if (!new_entry)
            return -ENOMEM;

        new_entry->umountable = kstrdup(buf, GFP_KERNEL);
        if (!new_entry->umountable) {
            kfree(new_entry);
            return -ENOMEM;
        }

        down_write(&mount_list_lock);

        // disallow dupes
        // if this gets too many, we can consider moving this whole task to a kthread
        list_for_each_entry (entry, &mount_list, list) {
            if (!strcmp(entry->umountable, buf)) {
                pr_info("cmd_add_try_umount: %s is already here!\n", buf);
                up_write(&mount_list_lock);
                kfree(new_entry->umountable);
                kfree(new_entry);
                return -EEXIST;
            }
        }

        // now check flags and add
        // this also serves as a null check
        if (cmd.flags)
            new_entry->flags = cmd.flags;
        else
            new_entry->flags = 0;

        // debug
        list_add(&new_entry->list, &mount_list);
        up_write(&mount_list_lock);
        pr_info("cmd_add_try_umount: %s added!\n", buf);

        return 0;
    }

    // this is just strcmp'd wipe anyway
    case KSU_UMOUNT_DEL: {
        long len = strncpy_from_user(buf, (const char __user *)cmd.arg, sizeof(buf));
        if (len <= 0)
            return -EFAULT;

        if (len >= sizeof(buf))
            return -ENAMETOOLONG;

        buf[sizeof(buf) - 1] = '\0';

        down_write(&mount_list_lock);
        list_for_each_entry_safe (entry, tmp, &mount_list, list) {
            if (!strcmp(entry->umountable, buf)) {
                pr_info("cmd_add_try_umount: entry removed: %s\n", entry->umountable);
                list_del(&entry->list);
                kfree(entry->umountable);
                kfree(entry);
            }
        }
        up_write(&mount_list_lock);

        return 0;
    }

    default: {
        pr_err("cmd_add_try_umount: invalid operation %u\n", cmd.mode);
        return -EINVAL;
    }

    } // switch(cmd.mode)

    return 0;
}

static int do_set_init_pgrp(void __user *arg)
{
    int err;
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 15, 0)
    struct pid *pids[PIDTYPE_MAX] = { 0 };
#endif

    write_lock_irq(&tasklist_lock);
    struct task_struct *p = current->group_leader;
    struct pid *init_group = task_pgrp(&init_task);

    err = -EPERM;
    if (task_session(p) != task_session(&init_task))
        goto out;

    err = 0;
    if (task_pgrp(p) != init_group) {
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 15, 0)
        change_pid(pids, p, PIDTYPE_PGID, init_group);
#else
        change_pid(p, PIDTYPE_PGID, init_group);
#endif
    }

out:
    write_unlock_irq(&tasklist_lock);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 15, 0)
    free_pids(pids);
#endif

    return err;
}

static int do_get_sulog_fd(void __user *arg)
{
    struct ksu_get_sulog_fd_cmd cmd;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        pr_err("get_sulog_fd: copy_from_user failed\n");
        return -EFAULT;
    }

    if (cmd.flags) {
        pr_err("get_sulog_fd: unsupported flags 0x%x\n", cmd.flags);
        return -EINVAL;
    }

    return ksu_install_sulog_fd();
}

static int do_disable_escape_to_root(void __user *arg)
{
    set_thread_flag(TIF_KSU_DISABLE_ESCAPE_WITH_ROOT);
    return 0;
}

static int do_enable_kpm(void __user *arg)
{
    struct ksu_enable_kpm_cmd cmd = {
        .enabled = IS_ENABLED(CONFIG_KPM) && !ksu_late_loaded,
    };

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("enable_kpm: copy_to_user failed\n");
        return -EFAULT;
    }

    return 0;
}

static int do_get_kpm_caps(void __user *arg)
{
    struct ksu_kpm_caps_cmd cmd = {
        .abi_version = 0,
        .backend = KSU_KPM_BACKEND_NONE,
        .capabilities = 0,
        .max_image_size = 0,
        .max_loaded = 0,
        .max_name_len = 0,
        .max_args_len = 0,
        .probe_error = -EOPNOTSUPP,
        .loader_ready = 0,
        .late_load = ksu_late_loaded ? 1 : 0,
    };

#if defined(CONFIG_KPM) && !defined(MODULE)
    if (!ksu_late_loaded) {
        cmd.abi_version = 1;
        cmd.backend = KSU_KPM_BACKEND_NATIVE_GKI;
        cmd.capabilities = KSU_KPM_CAP_ABI;
        cmd.max_image_size = 4 * 1024 * 1024;
        cmd.max_loaded = 64;
        cmd.max_name_len = 31;
        cmd.max_args_len = 1023;
        cmd.loader_ready = sukisu_kpm_loader_ready() ? 1 : 0;
        if (cmd.loader_ready) {
            cmd.capabilities |= KSU_KPM_CAP_LOAD | KSU_KPM_CAP_UNLOAD |
                                KSU_KPM_CAP_LIST | KSU_KPM_CAP_CONTROL |
                                KSU_KPM_CAP_INFO | KSU_KPM_CAP_VERSION;
            cmd.probe_error = 0;
        }
    }
#endif

    if (copy_to_user(arg, &cmd, sizeof(cmd))) {
        pr_err("get_kpm_caps: copy_to_user failed\n");
        return -EFAULT;
    }
    return 0;
}

#if IS_ENABLED(CONFIG_ABK_CONTROL)
static int do_abk_control_get_status(void __user *arg)
{
    struct abk_control_status_cmd cmd;
    char *json = NULL;
    size_t json_len = 0;
    u64 user_capacity;
    int ret;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    user_capacity = cmd.data_len;
    ret = abk_control_get_status_json(&json, &json_len);
    if (ret)
        return ret;

    cmd.data_len = json_len;
    ret = -ENOSPC;
    if (cmd.data && user_capacity >= json_len) {
        if (copy_to_user((void __user *)(unsigned long)cmd.data,
                         json, json_len))
            ret = -EFAULT;
        else
            ret = 0;
    }

    if (copy_to_user(arg, &cmd, sizeof(cmd)))
        ret = -EFAULT;
    kfree(json);
    return ret;
}

static int do_abk_control_run_command(void __user *arg)
{
    struct abk_control_command_cmd cmd;
    char command[ABK_CONTROL_MAX_COMMAND + 1];

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;
    if (!cmd.command || !cmd.command_len ||
        cmd.command_len > ABK_CONTROL_MAX_COMMAND)
        return -EINVAL;
    if (copy_from_user(command, (void __user *)(unsigned long)cmd.command,
                       cmd.command_len))
        return -EFAULT;
    command[cmd.command_len] = '\0';

    return abk_control_run_command(command, cmd.command_len);
}
#endif

// IOCTL handlers mapping table
// clang-format off
static const struct ksu_ioctl_cmd_map ksu_ioctl_handlers[] = {
    { 
        .cmd = KSU_IOCTL_GRANT_ROOT,
        .name = "GRANT_ROOT",
        .handler = do_grant_root,
        .perm_check = allowed_for_su 
    },
    {
        .cmd = KSU_IOCTL_GET_INFO,
        .name = "GET_INFO",
        .handler = do_get_info,
        .perm_check = always_allow
    },
    {
        .cmd = KSU_IOCTL_GET_INFO_LEGACY,
        .name = "GET_INFO_LEGACY",
        .handler = do_get_info_legacy,
        .perm_check = always_allow
    },
    {
        .cmd = KSU_IOCTL_REPORT_EVENT,
        .name = "REPORT_EVENT",
        .handler = do_report_event,
        .perm_check = only_root
    },
    {
        .cmd = KSU_IOCTL_SET_SEPOLICY,
        .name = "SET_SEPOLICY",
        .handler = do_set_sepolicy,
        .perm_check = only_root
    },
    {
        .cmd = KSU_IOCTL_CHECK_SAFEMODE,
        .name = "CHECK_SAFEMODE",
        .handler = do_check_safemode,
        .perm_check = always_allow
    },
    {
        .cmd = KSU_IOCTL_GET_ALLOW_LIST,
        .name = "GET_ALLOW_LIST",
        .handler = do_get_allow_list,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_GET_DENY_LIST,
        .name = "GET_DENY_LIST",
        .handler = do_get_deny_list,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_NEW_GET_ALLOW_LIST,
        .name = "NEW_GET_ALLOW_LIST",
        .handler = do_new_get_allow_list,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_NEW_GET_DENY_LIST,
        .name = "NEW_GET_DENY_LIST",
        .handler = do_new_get_deny_list,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_UID_GRANTED_ROOT,
        .name = "UID_GRANTED_ROOT",
        .handler = do_uid_granted_root,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_UID_SHOULD_UMOUNT,
        .name = "UID_SHOULD_UMOUNT",
        .handler = do_uid_should_umount,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_GET_MANAGER_APPID,
        .name = "GET_MANAGER_APPID",
        .handler = do_get_manager_appid,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_GET_APP_PROFILE,
        .name = "GET_APP_PROFILE",
        .handler = do_get_app_profile,
        .perm_check = only_manager
    },
    {
        .cmd = KSU_IOCTL_SET_APP_PROFILE,
        .name = "SET_APP_PROFILE",
        .handler = do_set_app_profile,
        .perm_check = only_manager
    },
    {
        .cmd = KSU_IOCTL_GET_FEATURE,
        .name = "GET_FEATURE",
        .handler = do_get_feature,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_SET_FEATURE,
        .name = "SET_FEATURE",
        .handler = do_set_feature,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_GET_WRAPPER_FD,
        .name = "GET_WRAPPER_FD",
        .handler = do_get_wrapper_fd,
        .perm_check = manager_or_root,
        .allow_su_session = true
    },
    {
        .cmd = KSU_IOCTL_MANAGE_MARK,
        .name = "MANAGE_MARK",
        .handler = do_manage_mark,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_NUKE_EXT4_SYSFS,
        .name = "NUKE_EXT4_SYSFS",
        .handler = do_nuke_ext4_sysfs,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_ADD_TRY_UMOUNT,
        .name = "ADD_TRY_UMOUNT",
        .handler = add_try_umount,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_SET_INIT_PGRP,
        .name = "SET_INIT_PGRP",
        .handler = do_set_init_pgrp,
        .perm_check = only_root
    },
    {
        .cmd = KSU_IOCTL_GET_SULOG_FD,
        .name = "GET_SULOG_FD",
        .handler = do_get_sulog_fd,
        .perm_check = only_root
    },
    { 
        .cmd = KSU_IOCTL_DISABLE_ESCAPE_TO_ROOT, 
        .name = "DISABLE_ESCAPE_TO_ROOT", 
        .handler = do_disable_escape_to_root, 
        .perm_check = only_root,
        .allow_su_session = true
    },
    {
        .cmd = KSU_IOCTL_SET_MANAGER_APPID,
        .name = "SET_MANAGER_APPID",
        .handler = do_set_manager_appid,
        .perm_check = only_root
    },
    {
        .cmd = KSU_IOCTL_DYNAMIC_MANAGER,
        .name = "DYNAMIC_MANAGER",
        .handler = do_dynamic_manager,
        .perm_check = only_root
    },
    {
        .cmd = KSU_IOCTL_GET_MANAGERS,
        .name = "GET_MANAGERS",
        .handler = do_get_managers,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_ENABLE_KPM,
        .name = "GET_ENABLE_KPM",
        .handler = do_enable_kpm,
        .perm_check = manager_or_root
    },
    {
        .cmd = KSU_IOCTL_GET_KPM_CAPS,
        .name = "GET_KPM_CAPS",
        .handler = do_get_kpm_caps,
        .perm_check = manager_or_root
    },
#ifdef CONFIG_KPM
    {
        .cmd = KSU_IOCTL_KPM,
        .name = "KPM_OPERATION",
        .handler = do_kpm,
        .perm_check = manager_or_root
    },
#endif
#if IS_ENABLED(CONFIG_ABK_CONTROL)
    {
        .cmd = ABK_CONTROL_IOCTL_GET_STATUS,
        .name = "ABK_CONTROL_GET_STATUS",
        .handler = do_abk_control_get_status,
        .perm_check = manager_or_root
    },
    {
        .cmd = ABK_CONTROL_IOCTL_RUN_COMMAND,
        .name = "ABK_CONTROL_RUN_COMMAND",
        .handler = do_abk_control_run_command,
        .perm_check = manager_or_root
    },
#endif
    {
        .cmd = 0,
        .name = NULL,
        .handler = NULL,
        .perm_check = NULL
    } // Sentinel
};
// clang-format on

long ksu_supercall_handle_ioctl(const struct file *filp, unsigned int cmd, void __user *argp)
{
    int i;

#ifdef CONFIG_KSU_DEBUG
    pr_info("ksu ioctl: cmd=0x%x from uid=%d\n", cmd, current_uid().val);
#endif

    for (i = 0; ksu_ioctl_handlers[i].handler; i++) {
        if (cmd == ksu_ioctl_handlers[i].cmd) {
            // Check permission first
            if (ksu_ioctl_handlers[i].perm_check && !ksu_ioctl_handlers[i].perm_check() &&
                !(ksu_ioctl_handlers[i].allow_su_session && ksu_is_su_session_fd(filp))) {
                pr_warn("ksu ioctl: permission denied for cmd=0x%x uid=%d\n", cmd, current_uid().val);
                return -EPERM;
            }
            // Execute handler
            return ksu_ioctl_handlers[i].handler(argp);
        }
    }

    pr_warn("ksu ioctl: unsupported command 0x%x\n", cmd);
    return -ENOTTY;
}

void __init ksu_supercall_dump_commands(void)
{
    int i;

    pr_info("KernelSU IOCTL Commands:\n");
    for (i = 0; ksu_ioctl_handlers[i].handler; i++) {
        pr_info("  %-18s = 0x%08x\n", ksu_ioctl_handlers[i].name, ksu_ioctl_handlers[i].cmd);
    }
}

void ksu_supercall_cleanup_state(void)
{
    struct mount_entry *entry, *tmp;

    down_write(&mount_list_lock);
    list_for_each_entry_safe (entry, tmp, &mount_list, list) {
        list_del(&entry->list);
        kfree(entry->umountable);
        kfree(entry);
    }
    up_write(&mount_list_lock);
}
