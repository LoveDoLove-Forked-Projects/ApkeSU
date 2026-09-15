/* SPDX-License-Identifier: GPL-2.0 */
#ifndef _UAPI_LINUX_ABK_CONTROL_H
#define _UAPI_LINUX_ABK_CONTROL_H

#include <linux/ioctl.h>
#include <linux/types.h>

#define ABK_CONTROL_MAX_COMMAND 160
#define ABK_CONTROL_IOCTL_MAGIC 0xa7

struct abk_control_status_cmd {
    __u64 data_len;
    __aligned_u64 data;
};

struct abk_control_command_cmd {
    __u64 command_len;
    __aligned_u64 command;
};

#define ABK_CONTROL_IOCTL_GET_STATUS \
    _IOWR(ABK_CONTROL_IOCTL_MAGIC, 0x41, struct abk_control_status_cmd)
#define ABK_CONTROL_IOCTL_RUN_COMMAND \
    _IOW(ABK_CONTROL_IOCTL_MAGIC, 0x42, struct abk_control_command_cmd)

#endif /* _UAPI_LINUX_ABK_CONTROL_H */
