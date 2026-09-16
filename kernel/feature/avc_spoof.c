#include "feature/avc_spoof.h"

#include <linux/atomic.h>
#include <linux/compiler.h>
#include <linux/errno.h>
#include <linux/mutex.h>
#include <linux/security.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/version.h>

#include "arch.h"
#include "hook/patch_memory.h"
#include "infra/symbol_resolver.h"
#include "klog.h" // IWYU pragma: keep
#include "policy/feature.h"
#include "selinux/selinux.h"

#if defined(CONFIG_KPROBES)
#include <linux/kprobes.h>
#endif

/* A direct branch into this object is safe only while it is built in. */
#if defined(CONFIG_ARM64) && !defined(MODULE)
#define KSU_AVC_HAS_ARM64_BRANCH_BACKEND 1
#endif

#if defined(KSU_AVC_HAS_ARM64_BRANCH_BACKEND)
#include "avc.h"
#endif

#define KSU_AVC_SPOOF_CONTEXT "u:r:priv_app:s0:c512,c768"

/* The upstream ARM64 backend searches the same compact caller windows. */
#define KSU_AVC_CALLER_SCAN_BYTES (384U * sizeof(u32))
#define KSU_AVC_MAX_BRANCH_SITES 4

static DEFINE_MUTEX(avc_spoof_mutex);

static u32 su_sid __read_mostly;
static u32 ksu_sid __read_mostly;
static u32 priv_app_sid __read_mostly;

static atomic_t avc_spoof_disabled = ATOMIC_INIT(1);
static bool ksu_avc_spoof_enabled __read_mostly = true;
static bool ksu_avc_spoof_running __read_mostly;
static bool ksu_avc_spoof_boot_completed __read_mostly;

enum ksu_avc_spoof_backend {
    KSU_AVC_BACKEND_NONE,
    KSU_AVC_BACKEND_ARM64_BRANCH,
    KSU_AVC_BACKEND_KPROBE,
};

static enum ksu_avc_spoof_backend avc_spoof_backend;

static __always_inline void ksu_handle_slow_avc_audit(u32 *tsid)
{
    u32 current_sid;
    u32 cached_su_sid;
    u32 cached_ksu_sid;
    u32 replacement_sid;

    if (!tsid || atomic_read(&avc_spoof_disabled))
        return;

    current_sid = READ_ONCE(*tsid);
    cached_su_sid = READ_ONCE(su_sid);
    cached_ksu_sid = READ_ONCE(ksu_sid);
    replacement_sid = READ_ONCE(priv_app_sid);

    if (!replacement_sid)
        return;

    if ((cached_su_sid && current_sid == cached_su_sid) ||
        (cached_ksu_sid && current_sid == cached_ksu_sid)) {
        pr_debug("avc_spoof: replace tsid %u with %u\n", current_sid, replacement_sid);
        WRITE_ONCE(*tsid, replacement_sid);
    }
}

#if defined(KSU_AVC_HAS_ARM64_BRANCH_BACKEND)

/*
 * A direct ARM64 BL is used for GKI/built-in kernels. The overloads cover
 * the slow_avc_audit prototypes used by Android kernel generations and by
 * vendor backports. Each wrapper keeps the original ABI and only changes
 * the local tsid before calling the resolved kernel function.
 */
#ifndef __overloadable
#define __overloadable __attribute__((overloadable))
#endif

#ifndef __nocfi
#define __nocfi
#endif

struct selinux_state;
static void *avc_slow_audit_original __read_mostly;

static noinline int __nocfi __overloadable ksu_slow_avc_audit_hook(
    u32 ssid, u32 tsid, u16 tclass, u32 requested, u32 audited, u32 denied, int result,
    struct common_audit_data *a)
{
    int (*original)(u32, u32, u16, u32, u32, u32, int, struct common_audit_data *) =
        (int (*)(u32, u32, u16, u32, u32, u32, int, struct common_audit_data *))
            READ_ONCE(avc_slow_audit_original);

    ksu_handle_slow_avc_audit(&tsid);
    return original ? original(ssid, tsid, tclass, requested, audited, denied, result, a) : -ENOSYS;
}

static noinline int __nocfi __overloadable ksu_slow_avc_audit_hook(
    struct selinux_state *state, u32 ssid, u32 tsid, u16 tclass, u32 requested, u32 audited,
    u32 denied, int result, struct common_audit_data *a)
{
    int (*original)(struct selinux_state *, u32, u32, u16, u32, u32, u32, int,
                    struct common_audit_data *) =
        (int (*)(struct selinux_state *, u32, u32, u16, u32, u32, u32, int,
                  struct common_audit_data *))READ_ONCE(avc_slow_audit_original);

    ksu_handle_slow_avc_audit(&tsid);
    return original ? original(state, ssid, tsid, tclass, requested, audited, denied, result, a)
                    : -ENOSYS;
}

static noinline int __nocfi __overloadable ksu_slow_avc_audit_hook(
    struct selinux_state *state, u32 ssid, u32 tsid, u16 tclass, u32 requested, u32 audited,
    u32 denied, int result, struct common_audit_data *a, unsigned int flags)
{
    int (*original)(struct selinux_state *, u32, u32, u16, u32, u32, u32, int,
                    struct common_audit_data *, unsigned int) =
        (int (*)(struct selinux_state *, u32, u32, u16, u32, u32, u32, int,
                  struct common_audit_data *, unsigned int))READ_ONCE(avc_slow_audit_original);

    ksu_handle_slow_avc_audit(&tsid);
    return original ? original(state, ssid, tsid, tclass, requested, audited, denied, result, a, flags)
                    : -ENOSYS;
}

static noinline int __nocfi __overloadable ksu_slow_avc_audit_hook(
    u32 ssid, u32 tsid, u16 tclass, u32 requested, u32 audited, u32 denied, int result,
    struct common_audit_data *a, unsigned int flags)
{
    int (*original)(u32, u32, u16, u32, u32, u32, int, struct common_audit_data *, unsigned int) =
        (int (*)(u32, u32, u16, u32, u32, u32, int, struct common_audit_data *, unsigned int))
            READ_ONCE(avc_slow_audit_original);

    ksu_handle_slow_avc_audit(&tsid);
    return original ? original(ssid, tsid, tclass, requested, audited, denied, result, a, flags)
                    : -ENOSYS;
}

static typeof(slow_avc_audit) *ksu_slow_avc_audit_hook_fn = ksu_slow_avc_audit_hook;

struct ksu_avc_branch_site {
    void *address;
    u32 original;
    u32 patched;
};

static struct ksu_avc_branch_site avc_branch_sites[KSU_AVC_MAX_BRANCH_SITES];
static unsigned int avc_branch_site_count;

/* Caller scans must start at the real function entry, not a CFI jump table. */
static void *ksu_avc_lookup_caller(const char *name)
{
    unsigned long exact = find_kernel_symbol_exact(name);

    return exact ? (void *)exact : NULL;
}

/* Calls may target the ABI-compatible CFI entry on older Android kernels. */
static void *ksu_avc_lookup_target(const char *name)
{
    return ksu_resolve_symbol_for_functable_hook(name);
}

static int ksu_avc_make_bl(void *from, void *to, u32 *instruction)
{
    uintptr_t from_addr = (uintptr_t)from;
    uintptr_t to_addr = (uintptr_t)to;
    u64 distance;
    s64 delta;
    s64 immediate;

    if (!from || !to || !instruction)
        return -EINVAL;

    if (((uintptr_t)from | (uintptr_t)to) & (sizeof(u32) - 1))
        return -EINVAL;

    if (to_addr >= from_addr) {
        distance = to_addr - from_addr;
        if (distance > ((1ULL << 27) - sizeof(u32)))
            return -ERANGE;
        delta = (s64)distance;
    } else {
        distance = from_addr - to_addr;
        if (distance > (1ULL << 27))
            return -ERANGE;
        delta = -(s64)distance;
    }

    if (delta & (sizeof(u32) - 1))
        return -EINVAL;

    immediate = delta >> 2;
    *instruction = 0x94000000U | ((u32)immediate & 0x03FFFFFFU);
    return 0;
}

static int ksu_avc_restore_branch_sites_locked(void)
{
    struct ksu_avc_branch_site failed_sites[KSU_AVC_MAX_BRANCH_SITES];
    unsigned int failed_count = 0;
    unsigned int i;
    int first_error = 0;

    for (i = avc_branch_site_count; i > 0; i--) {
        struct ksu_avc_branch_site *site = &avc_branch_sites[i - 1];
        u32 current_instruction;
        int ret;

        if (copy_from_kernel_nofault(&current_instruction, site->address, sizeof(current_instruction))) {
            pr_warn("avc_spoof: cannot read branch site %p during restore\n", site->address);
            failed_sites[failed_count++] = *site;
            if (!first_error)
                first_error = -EFAULT;
            continue;
        }

        if (current_instruction != site->patched) {
            pr_warn("avc_spoof: branch site %p changed after patch, leave it untouched\n", site->address);
            failed_sites[failed_count++] = *site;
            if (!first_error)
                first_error = -EAGAIN;
            continue;
        }

        ret = ksu_patch_text(site->address, &site->original, sizeof(site->original),
                             KSU_PATCH_TEXT_FLUSH_DCACHE | KSU_PATCH_TEXT_FLUSH_ICACHE);
        if (ret) {
            pr_warn("avc_spoof: restore branch site %p failed: %d\n", site->address, ret);
            failed_sites[failed_count++] = *site;
            if (!first_error)
                first_error = ret;
        }
    }

    for (i = 0; i < failed_count; i++)
        avc_branch_sites[i] = failed_sites[failed_count - i - 1];
    avc_branch_site_count = failed_count;

    if (!avc_branch_site_count)
        avc_slow_audit_original = NULL;

    return first_error;
}

static int ksu_avc_patch_branch_site(void *address)
{
    struct ksu_avc_branch_site site = {
        .address = address,
    };
    int ret;

    if (avc_branch_site_count >= ARRAY_SIZE(avc_branch_sites))
        return -ENOSPC;

    if ((uintptr_t)address & (sizeof(u32) - 1))
        return -EINVAL;

    if (copy_from_kernel_nofault(&site.original, address, sizeof(site.original)))
        return -EFAULT;

    if ((site.original & 0xFC000000U) != 0x94000000U)
        return -EINVAL;

    ret = ksu_avc_make_bl(address, (void *)ksu_slow_avc_audit_hook_fn, &site.patched);
    if (ret)
        return ret;

    ret = ksu_patch_text(address, &site.patched, sizeof(site.patched),
                         KSU_PATCH_TEXT_FLUSH_DCACHE | KSU_PATCH_TEXT_FLUSH_ICACHE);
    if (ret)
        return ret;

    avc_branch_sites[avc_branch_site_count++] = site;
    pr_info("avc_spoof: patched slow_avc_audit call at %p\n", address);
    return 0;
}

static int ksu_avc_install_branch_backend_locked(void)
{
    static const char *const callers[] = {
        "avc_has_extended_perms",
        "avc_has_perm_flags",
        "avc_has_perm",
        "audit_inode_permission",
    };
    void *original;
    size_t i;

    if (avc_branch_site_count)
        return 0;

    original = ksu_avc_lookup_target("slow_avc_audit");
    if (!original) {
        pr_info("avc_spoof: slow_avc_audit symbol not found\n");
        return -ENOENT;
    }

    avc_slow_audit_original = original;

    for (i = 0; i < ARRAY_SIZE(callers); i++) {
        void *caller = ksu_avc_lookup_caller(callers[i]);
        void *callsite;
        int ret;

        if (!caller) {
            pr_debug("avc_spoof: caller %s not found\n", callers[i]);
            continue;
        }

        callsite = scan_call_to(caller, KSU_AVC_CALLER_SCAN_BYTES, original);
        if (!callsite) {
            pr_debug("avc_spoof: no slow_avc_audit call in %s\n", callers[i]);
            continue;
        }

        ret = ksu_avc_patch_branch_site(callsite);
        if (ret) {
            int restore_ret;

            pr_warn("avc_spoof: patching %s failed: %d\n", callers[i], ret);
            restore_ret = ksu_avc_restore_branch_sites_locked();
            return restore_ret ? restore_ret : ret;
        }
    }

    if (!avc_branch_site_count) {
        avc_slow_audit_original = NULL;
        return -ENOENT;
    }

    return 0;
}

#endif /* KSU_AVC_HAS_ARM64_BRANCH_BACKEND */

#if defined(CONFIG_KPROBES)
static struct kprobe *slow_avc_audit_kp;

static int slow_avc_audit_pre_handler(struct kprobe *p, struct pt_regs *regs)
{
    (void)p;

#if LINUX_VERSION_CODE < KERNEL_VERSION(4, 17, 0) || LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
    ksu_handle_slow_avc_audit((u32 *)&PT_REGS_PARM2(regs));
#else
    ksu_handle_slow_avc_audit((u32 *)&PT_REGS_PARM3(regs));
#endif

    return 0;
}

static struct kprobe *ksu_init_kprobe(const char *name, kprobe_pre_handler_t handler)
{
    struct kprobe *kp;
    int ret;

    kp = kzalloc(sizeof(*kp), GFP_KERNEL);
    if (!kp)
        return NULL;

    kp->symbol_name = name;
    kp->pre_handler = handler;

    ret = register_kprobe(kp);
    pr_info("avc_spoof: register_%s kprobe: %d\n", name, ret);
    if (ret) {
        kfree(kp);
        return NULL;
    }

    return kp;
}

static void ksu_destroy_kprobe(struct kprobe **kp_ptr)
{
    struct kprobe *kp = *kp_ptr;

    if (!kp)
        return;

    unregister_kprobe(kp);
    synchronize_rcu();
    kfree(kp);
    *kp_ptr = NULL;
}
#endif /* CONFIG_KPROBES */

static int ksu_avc_spoof_cache_sids(void)
{
    int err;

    su_sid = 0;
    ksu_sid = 0;
    priv_app_sid = 0;

    err = security_secctx_to_secid("u:r:su:s0", strlen("u:r:su:s0"), &su_sid);
    if (err)
        pr_info("avc_spoof: su sid not found: %d\n", err);

    err = security_secctx_to_secid(KERNEL_SU_CONTEXT, strlen(KERNEL_SU_CONTEXT), &ksu_sid);
    if (err || !ksu_sid) {
        if (!err)
            err = -EINVAL;
        pr_info("avc_spoof: invalid ksu sid: %d\n", err);
        return err;
    }

    err = security_secctx_to_secid(KSU_AVC_SPOOF_CONTEXT, strlen(KSU_AVC_SPOOF_CONTEXT), &priv_app_sid);
    if (err || !priv_app_sid) {
        if (!err)
            err = -EINVAL;
        pr_info("avc_spoof: invalid priv_app sid: %d\n", err);
        return err;
    }

    pr_info("avc_spoof: cached su=%u ksu=%u priv_app=%u\n", su_sid, ksu_sid, priv_app_sid);
    return 0;
}

static int ksu_avc_spoof_enable_locked(void)
{
    int ret;

    if (ksu_avc_spoof_running)
        return 0;

    ret = ksu_avc_spoof_cache_sids();
    if (ret)
        return ret;

#if defined(KSU_AVC_HAS_ARM64_BRANCH_BACKEND)
    ret = ksu_avc_install_branch_backend_locked();
    if (!ret) {
        avc_spoof_backend = KSU_AVC_BACKEND_ARM64_BRANCH;
        atomic_set(&avc_spoof_disabled, 0);
        ksu_avc_spoof_running = true;
        pr_info("avc_spoof: enabled with ARM64 branch backend\n");
        return 0;
    }

    if (avc_branch_site_count) {
        pr_err("avc_spoof: ARM64 branch rollback incomplete: %d\n", ret);
        return ret;
    }

    pr_info("avc_spoof: ARM64 branch backend unavailable: %d, trying kprobe\n", ret);
#endif

#if defined(CONFIG_KPROBES)
    slow_avc_audit_kp = ksu_init_kprobe("slow_avc_audit", slow_avc_audit_pre_handler);
    if (!slow_avc_audit_kp)
        return -ENOENT;

    avc_spoof_backend = KSU_AVC_BACKEND_KPROBE;
    atomic_set(&avc_spoof_disabled, 0);
    ksu_avc_spoof_running = true;
    pr_info("avc_spoof: enabled with kprobe backend\n");
    return 0;
#else
    pr_info("avc_spoof: no supported hook backend is available\n");
    return -EOPNOTSUPP;
#endif
}

static void ksu_avc_spoof_disable_locked(void)
{
    atomic_set(&avc_spoof_disabled, 1);

    if (!ksu_avc_spoof_running)
        return;

#if defined(CONFIG_KPROBES)
    if (avc_spoof_backend == KSU_AVC_BACKEND_KPROBE)
        ksu_destroy_kprobe(&slow_avc_audit_kp);
#endif
#if defined(KSU_AVC_HAS_ARM64_BRANCH_BACKEND)
    if (avc_spoof_backend == KSU_AVC_BACKEND_ARM64_BRANCH &&
        ksu_avc_restore_branch_sites_locked())
        pr_err("avc_spoof: failed to restore all ARM64 branch sites\n");
#endif

    avc_spoof_backend = KSU_AVC_BACKEND_NONE;
    ksu_avc_spoof_running = false;
    pr_info("avc_spoof: disabled\n");
}

static int avc_spoof_feature_get(u64 *value)
{
    mutex_lock(&avc_spoof_mutex);
    *value = (ksu_avc_spoof_boot_completed ? ksu_avc_spoof_running : ksu_avc_spoof_enabled) ? 1 : 0;
    mutex_unlock(&avc_spoof_mutex);
    return 0;
}

static int avc_spoof_feature_set(u64 value)
{
    bool enable = value != 0;
    int ret = 0;

    mutex_lock(&avc_spoof_mutex);

    if (ksu_avc_spoof_boot_completed) {
        if (enable)
            ret = ksu_avc_spoof_enable_locked();
        else
            ksu_avc_spoof_disable_locked();
    }

    if (!ret)
        ksu_avc_spoof_enabled = enable;

    mutex_unlock(&avc_spoof_mutex);

    pr_info("avc_spoof: set to %d\n", enable);
    return ret;
}

static const struct ksu_feature_handler avc_spoof_handler = {
    .feature_id = KSU_FEATURE_AVC_SPOOF,
    .name = "avc_spoof",
    .get_handler = avc_spoof_feature_get,
    .set_handler = avc_spoof_feature_set,
};

void ksu_avc_spoof_handle_boot_completed(void)
{
    int ret = 0;

    mutex_lock(&avc_spoof_mutex);
    ksu_avc_spoof_boot_completed = true;
    if (ksu_avc_spoof_enabled)
        ret = ksu_avc_spoof_enable_locked();
    mutex_unlock(&avc_spoof_mutex);

    if (ret)
        pr_warn("avc_spoof: boot-time enable failed: %d\n", ret);
}

void __init ksu_avc_spoof_init(void)
{
    if (ksu_register_feature_handler(&avc_spoof_handler))
        pr_err("Failed to register avc_spoof feature handler\n");
}

void __exit ksu_avc_spoof_exit(void)
{
    mutex_lock(&avc_spoof_mutex);
    ksu_avc_spoof_disable_locked();
#if defined(KSU_AVC_HAS_ARM64_BRANCH_BACKEND)
    if (ksu_avc_restore_branch_sites_locked())
        pr_err("avc_spoof: failed to restore all ARM64 branch sites\n");
#endif
    mutex_unlock(&avc_spoof_mutex);

    ksu_unregister_feature_handler(KSU_FEATURE_AVC_SPOOF);
}
