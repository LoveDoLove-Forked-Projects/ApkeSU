#include <linux/errno.h>
#include <linux/kallsyms.h>
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

#if IS_ENABLED(CONFIG_SECCOMP)

void seccomp_filter_release(struct task_struct *tsk);

// Some Android 15 6.6 kernels backported the 6.11 release locking change.
#define KSU_SECCOMP_NEEDS_RELEASE_PROBE                                                                                \
    LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0) && LINUX_VERSION_CODE < KERNEL_VERSION(6, 11, 0)

#if KSU_SECCOMP_NEEDS_RELEASE_PROBE
// PF_EXITING is safe for both implementations and avoids dereferencing a
// copied sighand when symbol inspection is unavailable.
static bool seccomp_release_uses_siglock = true;
#endif

int ksu_disable_current_seccomp(void)
{
    struct task_struct *fake;
    unsigned long flags;

    if (!READ_ONCE(current->seccomp.mode))
        return 0;

    fake = kmalloc(sizeof(*fake), GFP_KERNEL);
    if (!fake)
        return -ENOMEM;

    spin_lock_irqsave(&current->sighand->siglock, flags);
    if (!current->seccomp.mode) {
        spin_unlock_irqrestore(&current->sighand->siglock, flags);
        kfree(fake);
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
        return;
    }

    ret = kallsyms_lookup_size_offset(release_addr, &size, NULL);
    if (!ret || !size) {
        pr_warn("seccomp hook: release size unavailable (%d), using safe locked-release mode\n", ret);
        return;
    }

    seccomp_release_uses_siglock = scan_call_to((void *)release_addr, size, (void *)spin_lock_addr) != NULL;
    pr_info("seccomp hook: release uses siglock=%d\n", seccomp_release_uses_siglock);
#endif
}

#else

int ksu_disable_current_seccomp(void)
{
    return -EOPNOTSUPP;
}

void __init ksu_seccomp_hook_init(void)
{
    pr_info("seccomp hook: CONFIG_SECCOMP is disabled\n");
}

#endif
