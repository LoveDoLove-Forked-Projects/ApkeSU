// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * ApkeSU Native-GKI KPM ABI bridge.
 *
 * This file deliberately does not contain an ELF executable loader.  GKI
 * KPMs are loaded by a boot-integrated compatible runtime which hooks the
 * exported SukiSU ABI.  Keeping the kernel bridge small makes a kernel that
 * has no attached loader fail closed instead of executing an unverified ELF.
 */

#include <linux/errno.h>
#include <linux/export.h>
#include <linux/kernel.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/version.h>

#include "kpm.h"
#include "ksu.h"

#define APKESU_KPM_NAME_LEN 32
#define APKESU_KPM_ARGS_LEN 1024
#define APKESU_KPM_INFO_LEN 256
#define APKESU_KPM_LIST_MAX 4096
#define APKESU_KPM_VERSION_MAX 256

static DEFINE_MUTEX(apkesu_kpm_operation_lock);

#ifndef NO_OPTIMIZE
#if defined(__GNUC__) && !defined(__clang__)
#define NO_OPTIMIZE __attribute__((optimize("O0")))
#elif defined(__clang__)
#define NO_OPTIMIZE __attribute__((optnone))
#else
#define NO_OPTIMIZE
#endif
#endif

noinline NO_OPTIMIZE void sukisu_kpm_version(char *buf, int buffer_size);

#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
#define APKESU_KPM_ACCESS_OK_READ(ptr, size) \
    access_ok(VERIFY_READ, (ptr), (size))
#define APKESU_KPM_ACCESS_OK_WRITE(ptr, size) \
    access_ok(VERIFY_WRITE, (ptr), (size))
#else
#define APKESU_KPM_ACCESS_OK_READ(ptr, size) access_ok((ptr), (size))
#define APKESU_KPM_ACCESS_OK_WRITE(ptr, size) access_ok((ptr), (size))
#endif

bool sukisu_kpm_loader_ready(void)
{
    char version[APKESU_KPM_VERSION_MAX] = { 0 };

    /* KernelPatch hooks this exported ABI function in a GKI boot image. */
    sukisu_kpm_version(version, sizeof(version));
    return version[0] != '\0';
}
EXPORT_SYMBOL(sukisu_kpm_loader_ready);

/*
 * The following functions are intentionally no-op stubs.  KernelPatch's
 * SukiSU bridge replaces them with the real loader operations.  Returning a
 * real error here is important: callers cannot mistake an ABI-only kernel
 * for a working executable loader.
 */
noinline NO_OPTIMIZE void sukisu_kpm_load_module_path(const char *path,
                                                      const char *args,
                                                      void *reserved,
                                                      int *result)
{
    (void)path;
    (void)args;
    (void)reserved;
    if (result)
        *result = -EOPNOTSUPP;
}
EXPORT_SYMBOL(sukisu_kpm_load_module_path);

noinline NO_OPTIMIZE void sukisu_kpm_unload_module(const char *name,
                                                   void *reserved, int *result)
{
    (void)name;
    (void)reserved;
    if (result)
        *result = -EOPNOTSUPP;
}
EXPORT_SYMBOL(sukisu_kpm_unload_module);

noinline NO_OPTIMIZE void sukisu_kpm_num(int *result)
{
    if (result)
        *result = -EOPNOTSUPP;
}
EXPORT_SYMBOL(sukisu_kpm_num);

noinline NO_OPTIMIZE void sukisu_kpm_info(const char *name, char *buf,
                                          int buffer_size, int *result)
{
    (void)name;
    if (buf && buffer_size > 0)
        buf[0] = '\0';
    if (result)
        *result = -EOPNOTSUPP;
}
EXPORT_SYMBOL(sukisu_kpm_info);

noinline NO_OPTIMIZE void sukisu_kpm_list(void *out, int buffer_size, int *result)
{
    (void)out;
    (void)buffer_size;
    if (result)
        *result = -EOPNOTSUPP;
}
EXPORT_SYMBOL(sukisu_kpm_list);

noinline NO_OPTIMIZE void sukisu_kpm_control(const char *name, const char *args,
                                             long arg_len, int *result)
{
    (void)name;
    (void)args;
    (void)arg_len;
    if (result)
        *result = -EOPNOTSUPP;
}
EXPORT_SYMBOL(sukisu_kpm_control);

noinline NO_OPTIMIZE void sukisu_kpm_version(char *buf, int buffer_size)
{
    if (buf && buffer_size > 0)
        buf[0] = '\0';
}
EXPORT_SYMBOL(sukisu_kpm_version);

static int copy_kpm_string(char *dst, size_t dst_size, unsigned long user_ptr,
                           bool optional)
{
    long copied;

    if (!user_ptr) {
        if (optional) {
            dst[0] = '\0';
            return 0;
        }
        return -EINVAL;
    }

    if (!APKESU_KPM_ACCESS_OK_READ((const void __user *)user_ptr, dst_size))
        return -EFAULT;

    copied = strncpy_from_user(dst, (const char __user *)user_ptr, dst_size);
    if (copied < 0)
        return -EFAULT;
    if (copied == 0 && !optional)
        return -EINVAL;
    if (copied >= dst_size)
        return -ENAMETOOLONG;
    return 0;
}

static int write_kpm_result(unsigned long user_ptr, int result)
{
    if (!user_ptr ||
        !APKESU_KPM_ACCESS_OK_WRITE((void __user *)user_ptr, sizeof(result)) ||
        copy_to_user((void __user *)user_ptr, &result, sizeof(result)))
        return -EFAULT;
    return 0;
}

static int handle_kpm_info(unsigned long arg1, unsigned long arg2, int *result)
{
    char name[APKESU_KPM_NAME_LEN] = { 0 };
    char info[APKESU_KPM_INFO_LEN] = { 0 };
    int ret;

    ret = copy_kpm_string(name, sizeof(name), arg1, false);
    if (ret)
        return ret;
    if (!arg2 ||
        !APKESU_KPM_ACCESS_OK_WRITE((void __user *)arg2, sizeof(info)))
        return -EFAULT;

    sukisu_kpm_info(name, info, sizeof(info), result);
    if (*result >= 0) {
        size_t length = strnlen(info, sizeof(info));

        /* The hooked loader reports the payload length here, but SukiSU's
         * userspace ABI exposes only copy_to_user's zero-on-success result.
         * Derive the copy length from the bounded buffer and preserve that
         * externally visible result contract. */
        if (length == sizeof(info) ||
            (*result > 0 && (size_t)*result > sizeof(info))) {
            *result = -EOVERFLOW;
            return 0;
        }
        if (*result > 0)
            length = min_t(size_t, length, (size_t)*result);
        if (copy_to_user((void __user *)arg2, info, length + 1))
            return -EFAULT;
        *result = 0;
    }
    return 0;
}

static int handle_kpm_list(unsigned long arg1, unsigned long arg2, int *result)
{
    void *buffer;
    size_t buffer_size;
    int ret = 0;

    if (!arg1 || arg2 == 0 || arg2 > APKESU_KPM_LIST_MAX)
        return -EINVAL;
    buffer_size = (size_t)arg2;
    if (!APKESU_KPM_ACCESS_OK_WRITE((void __user *)arg1, buffer_size))
        return -EFAULT;

    buffer = kzalloc(buffer_size, GFP_KERNEL);
    if (!buffer)
        return -ENOMEM;

    sukisu_kpm_list(buffer, (int)buffer_size, result);
    if (*result >= 0 && (size_t)*result > buffer_size) {
        *result = -ENOBUFS;
    } else if (*result > 0 && copy_to_user((void __user *)arg1, buffer,
                                           (size_t)*result)) {
        ret = -EFAULT;
    }
    kfree(buffer);
    return ret;
}

static int handle_kpm_version(unsigned long arg1, unsigned long arg2,
                              int *result)
{
    char version[APKESU_KPM_VERSION_MAX] = { 0 };
    size_t output_size;

    if (!arg1 || arg2 == 0)
        return -EINVAL;
    output_size = min_t(size_t, arg2, sizeof(version));
    if (!APKESU_KPM_ACCESS_OK_WRITE((void __user *)arg1, output_size))
        return -EFAULT;

    sukisu_kpm_version(version, (int)output_size);
    if (version[0] == '\0') {
        *result = -EOPNOTSUPP;
        return 0;
    }

    version[output_size - 1] = '\0';
    if (copy_to_user((void __user *)arg1, version,
                     strnlen(version, output_size) + 1))
        return -EFAULT;
    *result = 0;
    return 0;
}

/*
 * Keep the SukiSU ABI's result-in-user-memory contract.  The ioctl itself
 * returns transport errors only; operation errors are written to result_code
 * so existing KernelPatch bridges remain compatible.
 */
int sukisu_handle_kpm(unsigned long control_code, unsigned long arg1,
                      unsigned long arg2, unsigned long result_code)
{
    char path[256] = { 0 };
    char args[APKESU_KPM_ARGS_LEN] = { 0 };
    char name[APKESU_KPM_NAME_LEN] = { 0 };
    int result = -EINVAL;
    int ret;

    if (ksu_late_loaded)
        return -EOPNOTSUPP;
    if (!result_code)
        return -EFAULT;

    mutex_lock(&apkesu_kpm_operation_lock);

    switch (control_code) {
    case SUKISU_KPM_LOAD:
        ret = copy_kpm_string(path, sizeof(path), arg1, false);
        if (ret)
            result = ret;
        else if ((ret = copy_kpm_string(args, sizeof(args), arg2, true)) != 0)
            result = ret;
        else
            sukisu_kpm_load_module_path(path, args, NULL, &result);
        break;
    case SUKISU_KPM_UNLOAD:
        ret = copy_kpm_string(name, sizeof(name), arg1, false);
        if (ret)
            result = ret;
        else
            sukisu_kpm_unload_module(name, NULL, &result);
        break;
    case SUKISU_KPM_NUM:
        sukisu_kpm_num(&result);
        break;
    case SUKISU_KPM_LIST:
        ret = handle_kpm_list(arg1, arg2, &result);
        if (ret)
            result = ret;
        break;
    case SUKISU_KPM_INFO:
        ret = handle_kpm_info(arg1, arg2, &result);
        if (ret)
            result = ret;
        break;
    case SUKISU_KPM_CONTROL:
        ret = copy_kpm_string(name, sizeof(name), arg1, false);
        if (ret)
            result = ret;
        else if ((ret = copy_kpm_string(args, sizeof(args), arg2, true)) != 0)
            result = ret;
        else
            sukisu_kpm_control(name, args, strnlen(args, sizeof(args)), &result);
        break;
    case SUKISU_KPM_VERSION:
        ret = handle_kpm_version(arg1, arg2, &result);
        if (ret)
            result = ret;
        break;
    default:
        result = -EINVAL;
        break;
    }

    mutex_unlock(&apkesu_kpm_operation_lock);

    return write_kpm_result(result_code, result);
}
EXPORT_SYMBOL(sukisu_handle_kpm);

int sukisu_is_kpm_control_code(unsigned long control_code)
{
    return control_code >= CMD_KPM_CONTROL &&
           control_code <= CMD_KPM_CONTROL_MAX;
}
EXPORT_SYMBOL(sukisu_is_kpm_control_code);

int do_kpm(void __user *arg)
{
    struct ksu_kpm_cmd cmd;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;
    if (!sukisu_is_kpm_control_code((unsigned long)cmd.control_code))
        return -EINVAL;
    if (!cmd.result_code ||
        !APKESU_KPM_ACCESS_OK_WRITE((void __user *)cmd.result_code,
                                    sizeof(int)))
        return -EFAULT;

    return sukisu_handle_kpm((unsigned long)cmd.control_code,
                             (unsigned long)cmd.arg1,
                             (unsigned long)cmd.arg2,
                             (unsigned long)cmd.result_code);
}
