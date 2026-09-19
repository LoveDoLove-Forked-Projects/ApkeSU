#include <linux/atomic.h>
#include <linux/errno.h>
#include <linux/kallsyms.h>
#include <linux/kernel.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/seccomp.h>
#include <linux/slab.h>
#include <linux/thread_info.h>
#include <linux/version.h>

#include "feature/seccomp_hook.h"
#include "hook/patch_memory.h"
#include "infra/symbol_resolver.h"
#include "klog.h" // IWYU pragma: keep
#include "policy/feature.h"
#include "uapi/feature.h"

// Some Android 15 6.6 kernels backported the 6.11 release locking change.
#define KSU_SECCOMP_NEEDS_RELEASE_PROBE                                                                                \
    LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0) && LINUX_VERSION_CODE < KERNEL_VERSION(6, 11, 0)

static bool seccomp_hook_initialized;
#if KSU_SECCOMP_NEEDS_RELEASE_PROBE
// PF_EXITING is safe for both implementations and avoids dereferencing a
// copied sighand when symbol inspection is unavailable.
static bool seccomp_release_probe_complete;
static bool seccomp_release_probe_fallback;
static bool seccomp_release_uses_siglock = true;
#endif
static atomic_t seccomp_hook_last_error = ATOMIC_INIT(0);
static atomic_t seccomp_hook_call_count = ATOMIC_INIT(0);
static atomic_t seccomp_hook_release_count = ATOMIC_INIT(0);
static atomic_t seccomp_hook_failure_count = ATOMIC_INIT(0);

static void seccomp_hook_record_result(int ret, bool released)
{
    atomic_inc(&seccomp_hook_call_count);
    atomic_set(&seccomp_hook_last_error, ret);
    if (ret) {
        atomic_inc(&seccomp_hook_failure_count);
    } else if (released) {
        atomic_inc(&seccomp_hook_release_count);
    }
}

static int seccomp_hook_status_feature_get(u64 *value)
{
    u64 status = 0;
    int calls = atomic_read(&seccomp_hook_call_count);
    int last_error = atomic_read(&seccomp_hook_last_error);

    if (IS_ENABLED(CONFIG_SECCOMP))
        status |= KSU_SECCOMP_HOOK_STATUS_KERNEL_SUPPORTED;
    if (IS_ENABLED(CONFIG_KSU_GKI_SECCOMP_HOOK))
        status |= KSU_SECCOMP_HOOK_STATUS_CONFIG_ENABLED;
    if (READ_ONCE(seccomp_hook_initialized))
        status |= KSU_SECCOMP_HOOK_STATUS_INITIALIZED;
    if (IS_ENABLED(CONFIG_SECCOMP) && IS_ENABLED(CONFIG_KSU_GKI_SECCOMP_HOOK) && READ_ONCE(seccomp_hook_initialized))
        status |= KSU_SECCOMP_HOOK_STATUS_READY;
#if KSU_SECCOMP_NEEDS_RELEASE_PROBE
    if (IS_ENABLED(CONFIG_SECCOMP)) {
        status |= KSU_SECCOMP_HOOK_STATUS_RELEASE_PROBE_REQUIRED;
        if (READ_ONCE(seccomp_release_probe_complete))
            status |= KSU_SECCOMP_HOOK_STATUS_RELEASE_PROBE_COMPLETE;
        if (READ_ONCE(seccomp_release_probe_fallback))
            status |= KSU_SECCOMP_HOOK_STATUS_RELEASE_PROBE_FALLBACK;
        if (READ_ONCE(seccomp_release_uses_siglock))
            status |= KSU_SECCOMP_HOOK_STATUS_RELEASE_USES_SIGLOCK;
    }
#endif
    if (atomic_read(&seccomp_hook_release_count) > 0)
        status |= KSU_SECCOMP_HOOK_STATUS_RUNTIME_VERIFIED;
    if (calls > 0) {
        status |= last_error ? KSU_SECCOMP_HOOK_STATUS_LAST_CALL_FAILED : KSU_SECCOMP_HOOK_STATUS_LAST_CALL_SUCCEEDED;
    }

    *value = status;
    return 0;
}

static int seccomp_hook_last_error_feature_get(u64 *value)
{
    *value = (u64)(s64)atomic_read(&seccomp_hook_last_error);
    return 0;
}

static int seccomp_hook_call_count_feature_get(u64 *value)
{
    *value = (u32)atomic_read(&seccomp_hook_call_count);
    return 0;
}

static int seccomp_hook_release_count_feature_get(u64 *value)
{
    *value = (u32)atomic_read(&seccomp_hook_release_count);
    return 0;
}

static int seccomp_hook_failure_count_feature_get(u64 *value)
{
    *value = (u32)atomic_read(&seccomp_hook_failure_count);
    return 0;
}

static const struct ksu_feature_handler seccomp_hook_feature_handlers[] = {
    {
        .feature_id = KSU_FEATURE_SECCOMP_HOOK_STATUS,
        .name = "seccomp_hook_status",
        .get_handler = seccomp_hook_status_feature_get,
    },
    {
        .feature_id = KSU_FEATURE_SECCOMP_HOOK_LAST_ERROR,
        .name = "seccomp_hook_last_error",
        .get_handler = seccomp_hook_last_error_feature_get,
    },
    {
        .feature_id = KSU_FEATURE_SECCOMP_HOOK_CALL_COUNT,
        .name = "seccomp_hook_call_count",
        .get_handler = seccomp_hook_call_count_feature_get,
    },
    {
        .feature_id = KSU_FEATURE_SECCOMP_HOOK_RELEASE_COUNT,
        .name = "seccomp_hook_release_count",
        .get_handler = seccomp_hook_release_count_feature_get,
    },
    {
        .feature_id = KSU_FEATURE_SECCOMP_HOOK_FAILURE_COUNT,
        .name = "seccomp_hook_failure_count",
        .get_handler = seccomp_hook_failure_count_feature_get,
    },
};

static void __init register_seccomp_hook_features(void)
{
    size_t i;

    for (i = 0; i < ARRAY_SIZE(seccomp_hook_feature_handlers); i++) {
        if (ksu_register_feature_handler(&seccomp_hook_feature_handlers[i]))
            pr_err("seccomp hook: failed to register feature %u\n", seccomp_hook_feature_handlers[i].feature_id);
    }
}

#if IS_ENABLED(CONFIG_SECCOMP)

void seccomp_filter_release(struct task_struct *tsk);

int ksu_disable_current_seccomp(void)
{
    struct task_struct *fake;
    unsigned long flags;

    if (!READ_ONCE(current->seccomp.mode)) {
        seccomp_hook_record_result(0, false);
        return 0;
    }

    fake = kmalloc(sizeof(*fake), GFP_KERNEL);
    if (!fake) {
        seccomp_hook_record_result(-ENOMEM, false);
        return -ENOMEM;
    }

    spin_lock_irqsave(&current->sighand->siglock, flags);
    if (!current->seccomp.mode) {
        spin_unlock_irqrestore(&current->sighand->siglock, flags);
        kfree(fake);
        seccomp_hook_record_result(0, false);
        return 0;
    }

    memcpy(fake, current, sizeof(*fake));

#if defined(CONFIG_GENERIC_ENTRY) && LINUX_VERSION_CODE >= KERNEL_VERSION(5, 11, 0)
    clear_syscall_work(SECCOMP);
#else
    clear_thread_flag(TIF_SECCOMP);
#endif

    current->seccomp.mode = SECCOMP_MODE_DISABLED;
    current->seccomp.filter = NULL;
    atomic_set(&current->seccomp.filter_count, 0);
    spin_unlock_irqrestore(&current->sighand->siglock, flags);

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 11, 0)
    fake->flags |= PF_EXITING;
#elif KSU_SECCOMP_NEEDS_RELEASE_PROBE
    if (seccomp_release_uses_siglock)
        fake->flags |= PF_EXITING;
    else
        fake->sighand = NULL;
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5, 11, 0)
    fake->sighand = NULL;
#endif

    seccomp_filter_release(fake);
    kfree(fake);
    seccomp_hook_record_result(0, true);
    return 0;
}

void __init ksu_seccomp_hook_init(void)
{
#if KSU_SECCOMP_NEEDS_RELEASE_PROBE
    unsigned long release_addr;
    unsigned long spin_lock_addr;
    unsigned long size = 0;
    int ret;

    release_addr = find_kernel_symbol_exact("seccomp_filter_release");
    spin_lock_addr = find_kernel_symbol_exact("_raw_spin_lock_irq");
    if (!release_addr || !spin_lock_addr) {
        pr_warn("seccomp hook: release probe symbols unavailable, using safe locked-release mode\n");
        seccomp_release_probe_fallback = true;
        goto probe_done;
    }

    ret = kallsyms_lookup_size_offset(release_addr, &size, NULL);
    if (!ret || !size) {
        pr_warn("seccomp hook: release size unavailable (%d), using safe locked-release mode\n", ret);
        seccomp_release_probe_fallback = true;
        goto probe_done;
    }

    seccomp_release_uses_siglock = scan_call_to((void *)release_addr, size, (void *)spin_lock_addr) != NULL;
    seccomp_release_probe_complete = true;
    pr_info("seccomp hook: release uses siglock=%d\n", seccomp_release_uses_siglock);
probe_done:
#endif

    WRITE_ONCE(seccomp_hook_initialized, true);
    register_seccomp_hook_features();
}

#else

int ksu_disable_current_seccomp(void)
{
    seccomp_hook_record_result(-EOPNOTSUPP, false);
    return -EOPNOTSUPP;
}

void __init ksu_seccomp_hook_init(void)
{
    pr_info("seccomp hook: CONFIG_SECCOMP is disabled\n");
    register_seccomp_hook_features();
}

#endif

void ksu_seccomp_hook_exit(void)
{
    size_t i;

    for (i = 0; i < ARRAY_SIZE(seccomp_hook_feature_handlers); i++)
        ksu_unregister_feature_handler(seccomp_hook_feature_handlers[i].feature_id);
    WRITE_ONCE(seccomp_hook_initialized, false);
}
