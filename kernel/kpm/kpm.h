#ifndef __APKESU_NATIVE_KPM_H
#define __APKESU_NATIVE_KPM_H

#include <linux/types.h>

#include "uapi/supercall.h"

/* Keep the validation window compatible with SukiSU's reserved ABI slots.
 * Only operations 1..7 are implemented here; 8..10 are rejected by the
 * operation dispatcher with an operation result of -EINVAL. */
#define CMD_KPM_CONTROL 1
#define CMD_KPM_CONTROL_MAX 10

/*
 * These symbols are the stable SukiSU/KPM integration points.  The GKI
 * kernel intentionally provides the ABI and safe stubs; a compatible KPM
 * loader (for example a boot-integrated KernelPatch loader) may attach to
 * these symbols later.
 */
int sukisu_handle_kpm(unsigned long control_code, unsigned long arg1,
                      unsigned long arg2, unsigned long result_code);
int sukisu_is_kpm_control_code(unsigned long control_code);
int do_kpm(void __user *arg);
bool sukisu_kpm_loader_ready(void);

#endif /* __APKESU_NATIVE_KPM_H */
