#include <compiler.h>
#include <kpmodule.h>
#include <linux/printk.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/kernel.h>
#include <hook.h>
#include <symbol.h>
#include <kconfig.h>
#include <common.h>
#include <module.h>
#include <predata.h>

static int bridge_ready;

static long call_kpm_control(const char *name, const char * args, long arg_len, void *__user out_msg, int outlen)
{
    return module_control0(name, arg_len <= 0 ? 0 : args, out_msg, outlen);
}

static long call_kpm_list(char * buf, int len)
{
    int sz = list_modules(buf, len);
    return sz;
}

// =====================================================================================

void before_sukisu_load_module_path(hook_fargs4_t* args, void* udata) {
    if (!bridge_ready) return;
    const char* path = (const char*) args->arg0;
    const char* arg = (const char*) args->arg1;
    void* ptr = (void*) args->arg2;
    int* result = (void*) args->arg3;

    logkfi("Load KPM: %s", path);

    *result = (int) load_module_path(path, arg, ptr);
    args->skip_origin = 1;
}

void before_sukisu_unload_module(hook_fargs3_t* args,void* udata) {
    if (!bridge_ready) return;
    const char* name = (const char*)args->arg0;
    void* ptr = (void*) args->arg1;
    int* result = (void*) args->arg2;
    *result = (int) unload_module(name, ptr);
    args->skip_origin = 1;
}

void before_sukisu_kpm_num(hook_fargs1_t* args, void* udata) {
    if (!bridge_ready) return;
    int* result = (void*) args->arg0;

    *result = (int) get_module_nums();
    args->skip_origin = 1;
}

void before_sukisu_kpm_list(hook_fargs3_t* args, void* udata) {
    if (!bridge_ready) return;
    char* out = (char* __user) args->arg0;
    int len = (int) args->arg1;
    int * result = (void*) args->arg2;

    int res = (int) call_kpm_list(out, len);
    
    *result = res;
    args->skip_origin = 1;
}

void before_sukisu_kpm_info(hook_fargs4_t* args, void* udata) {
    if (!bridge_ready) return;
    char* name = (char*) args->arg0;
    char* buf = (char*) args->arg1;
    int buf_size = (int) args->arg2;
    int* size = (void*) args->arg3;
    *size = get_module_info(name, buf, buf_size);
    args->skip_origin = 1;
}

void before_sukisu_kpm_version(hook_fargs2_t* args, void* udata) {
    if (!bridge_ready) return;
    char * buf = (char *) args->arg0;
    int buf_size =  (int) args->arg1;
    const char *buildtime = get_build_time();

    if (buf && buf_size > 0) {
        snprintf(buf, buf_size, "%d (%s)", kpver, buildtime);
        buf[buf_size - 1] = '\0';
    }
    args->skip_origin = 1;
}

void before_sukisu_kpm_control(hook_fargs4_t* args, void* udata) {
    if (!bridge_ready) return;
    const char * name = (const char *) args->arg0;
    const char * arg = (const char *) args->arg1;
    long arg_len = (long) args->arg2;
    int * result = (void*) args->arg3;
    int res = (int) call_kpm_control(name, arg, arg_len, NULL, 0);

    *result = res;
    args->skip_origin = 1;
}

void init_sukisu_ultra(void) {
    static struct {
        const char *name;
        int argc;
        void *callback;
        unsigned long address;
    } hooks[] = {
        { "sukisu_kpm_load_module_path", 4, before_sukisu_load_module_path, 0 },
        { "sukisu_kpm_unload_module", 3, before_sukisu_unload_module, 0 },
        { "sukisu_kpm_num", 1, before_sukisu_kpm_num, 0 },
        { "sukisu_kpm_list", 3, before_sukisu_kpm_list, 0 },
        { "sukisu_kpm_info", 4, before_sukisu_kpm_info, 0 },
        { "sukisu_kpm_control", 4, before_sukisu_kpm_control, 0 },
        { "sukisu_kpm_version", 2, before_sukisu_kpm_version, 0 },
    };
    int count = sizeof(hooks) / sizeof(hooks[0]);
    int installed = 0;

    if (bridge_ready) return;
    for (int i = 0; i < count; ++i) {
        hooks[i].address = kallsyms_lookup_name(hooks[i].name);
        if (!hooks[i].address) {
            log_boot("Native KPM missing ABI: %s\n", hooks[i].name);
            return;
        }
    }
    for (; installed < count; ++installed) {
        int rc = hook_wrap((void *)hooks[installed].address, hooks[installed].argc,
                           hooks[installed].callback, NULL, NULL);
        if (rc) {
            log_boot("Native KPM hook failed: %s rc=%d; rolling back\n", hooks[installed].name, rc);
            while (installed > 0) {
                --installed;
                hook_unwrap((void *)hooks[installed].address, hooks[installed].callback, NULL);
            }
            return;
        }
    }
    bridge_ready = 1;
    log_boot("Native GKI KPM bridge ready\n");
}
