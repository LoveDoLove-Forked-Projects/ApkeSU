#ifndef __KSU_H_SUCOMPAT
#define __KSU_H_SUCOMPAT
#include <asm/ptrace.h>
#include <linux/fs.h>
#include <linux/static_key.h>
#include <linux/types.h>

#ifdef CONFIG_KSU_SUSFS
extern struct static_key_true ksu_su_compat_enabled;
#else
extern bool ksu_su_compat_enabled;
#endif

void ksu_sucompat_init(void);
void ksu_sucompat_exit(void);

#ifdef CONFIG_KSU_SUSFS
int ksu_handle_execveat(int *fd, struct filename **filename_ptr, void *argv,
                        void *envp, int *flags);
int ksu_handle_execveat_sucompat(int *fd, struct filename **filename_ptr,
                                 void *argv, void *envp, int *flags);
int ksu_handle_post_execveat_sucompat(int *fd, struct filename **filename_ptr,
                                      void *argv, void *envp, int *flags,
                                      int *retval);
int ksu_handle_faccessat(int *dfd, struct filename **filename, int *mode,
                         int *flags);
int ksu_handle_stat(int *dfd, struct filename **filename, int *flags);
#else
// Handler functions exported for hook_manager
long ksu_handle_faccessat_sucompat(int orig_nr, struct pt_regs *regs);
long ksu_handle_stat_sucompat(int orig_nr, struct pt_regs *regs);
long ksu_handle_execve_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs);
long ksu_handle_execveat_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs);
#endif

#endif
