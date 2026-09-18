#ifndef __KSU_SECCOMP_HOOK_H
#define __KSU_SECCOMP_HOOK_H

#include <linux/init.h>

int ksu_disable_current_seccomp(void);
void __init ksu_seccomp_hook_init(void);

#endif
