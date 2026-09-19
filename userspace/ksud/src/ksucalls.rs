#![allow(clippy::unreadable_literal)]
use anyhow::{Result, bail};

use crate::ksu_uapi;
use std::cell::Cell;
use std::ffi::CString;
use std::fs;
use std::io;
use std::mem;
use std::os::fd::RawFd;
use std::ptr;
use std::sync::{Mutex, OnceLock};

// sigsys handler
std::thread_local! {
    #[allow(clippy::missing_const_for_thread_local)]
    static SVC_IN_FLIGHT: Cell<bool> = const { Cell::new(false) };
    #[allow(clippy::missing_const_for_thread_local)]
    static SIGSYS_OCCURRED: Cell<bool> = const { Cell::new(false) };
}

const SYS_SECCOMP: libc::c_int = 1;

fn with_svc_call<F, R>(call: F) -> R
where
    F: FnOnce() -> R,
{
    SVC_IN_FLIGHT.with(|in_flight| in_flight.set(true));
    let result = call();
    SVC_IN_FLIGHT.with(|in_flight| in_flight.set(false));
    result
}

fn take_sigsys_occurred() -> bool {
    SIGSYS_OCCURRED.with(|occurred| occurred.replace(false))
}

extern "C" fn sigsys_handler(
    _sig: libc::c_int,
    info: *mut libc::siginfo_t,
    ctx: *mut libc::c_void,
) {
    unsafe {
        if info.is_null() || ctx.is_null() || (*info).si_code != SYS_SECCOMP {
            return;
        }
        if SVC_IN_FLIGHT.with(Cell::get) {
            SIGSYS_OCCURRED.with(|occurred| occurred.set(true));
        }

        let ucontext = ctx.cast::<libc::ucontext_t>();
        #[cfg(target_arch = "aarch64")]
        {
            (*ucontext).uc_mcontext.regs[0] = (-libc::EPERM) as u64;
        }
        #[cfg(target_arch = "x86_64")]
        {
            let rax = libc::REG_RAX as usize;
            (*ucontext).uc_mcontext.gregs[rax] = i64::from(-libc::EPERM);
        }
    }
}

pub fn setup_sigsys_handler() {
    unsafe {
        let mut sa: libc::sigaction = std::mem::zeroed();
        sa.sa_flags = libc::SA_SIGINFO;
        sa.sa_sigaction = sigsys_handler as *const () as usize;
        libc::sigemptyset(std::ptr::addr_of_mut!(sa.sa_mask));
        if libc::sigaction(libc::SIGSYS, std::ptr::addr_of!(sa), std::ptr::null_mut()) != 0 {
            let error = std::io::Error::last_os_error();
            log::warn!("Failed to set SIGSYS handler: {error}");
        }
    }
}

const DRIVER_FD_NAME: &str = "anon_inode:[ksu_driver]";
const SU_DRIVER_FD_NAME: &str = "anon_inode:[ksu_driver_su]";
const FIRST_APPLICATION_APPID: u32 = 10_000;
const LAST_APPLICATION_APPID: u32 = 19_999;

// Global driver fd cache
static DRIVER_FD: Mutex<RawFd> = Mutex::new(-1);
static INFO_CACHE: OnceLock<ksu_uapi::ksu_get_info_cmd> = OnceLock::new();

fn scan_driver_fd() -> io::Result<Option<RawFd>> {
    let fd_dir = fs::read_dir("/proc/self/fd")?;
    let mut driver_fd = None;

    for entry in fd_dir.flatten() {
        if let Ok(fd_num) = entry.file_name().to_string_lossy().parse::<i32>() {
            let link_path = format!("/proc/self/fd/{fd_num}");
            if let Ok(target) = fs::read_link(&link_path) {
                let target_str = target.to_string_lossy();
                if target_str == SU_DRIVER_FD_NAME {
                    return Ok(Some(fd_num));
                }
                if target_str == DRIVER_FD_NAME {
                    driver_fd = Some(fd_num);
                }
            }
        }
    }

    Ok(driver_fd)
}

pub fn claim_inherited_driver_fd() -> io::Result<()> {
    let mut driver_fd = DRIVER_FD
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if *driver_fd < 0
        && let Some(fd) = scan_driver_fd()?
    {
        *driver_fd = fd;
    }
    drop(driver_fd);
    Ok(())
}

fn close_fd(fd: RawFd) {
    if fd >= 0 {
        unsafe {
            libc::close(fd);
        }
    }
}

fn wait_for_child(pid: libc::pid_t) {
    let mut status = 0;
    loop {
        let ret = unsafe { libc::waitpid(pid, &raw mut status, 0) };
        if ret >= 0 || io::Error::last_os_error().raw_os_error() != Some(libc::EINTR) {
            break;
        }
    }
}

fn set_recv_timeout(fd: RawFd) {
    let timeout = libc::timeval {
        tv_sec: 1,
        tv_usec: 500_000,
    };
    unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            (&raw const timeout).cast(),
            mem::size_of_val(&timeout) as libc::socklen_t,
        );
    }
}

const fn cmsg_align(len: usize) -> usize {
    let align = mem::size_of::<usize>();
    (len + align - 1) & !(align - 1)
}

const fn cmsg_space(payload_len: usize) -> usize {
    cmsg_align(mem::size_of::<libc::cmsghdr>()) + cmsg_align(payload_len)
}

const fn cmsg_len(payload_len: usize) -> usize {
    cmsg_align(mem::size_of::<libc::cmsghdr>()) + payload_len
}

fn send_fd(socket_fd: RawFd, fd: RawFd) -> io::Result<()> {
    let payload = [0u8; 1];
    let mut iov = libc::iovec {
        iov_base: payload.as_ptr().cast_mut().cast(),
        iov_len: payload.len(),
    };
    let control_len = cmsg_space(mem::size_of::<RawFd>());
    let mut control = [0usize; 8];
    let mut msg = unsafe { mem::zeroed::<libc::msghdr>() };
    msg.msg_iov = &raw mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr().cast();
    msg.msg_controllen = control_len;

    let cmsg = msg.msg_control.cast::<libc::cmsghdr>();
    unsafe {
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        (*cmsg).cmsg_len = cmsg_len(mem::size_of::<RawFd>()) as _;
        let data = cmsg
            .cast::<u8>()
            .add(cmsg_align(mem::size_of::<libc::cmsghdr>()));
        let fd_bytes = fd.to_ne_bytes();
        ptr::copy_nonoverlapping(fd_bytes.as_ptr(), data, fd_bytes.len());

        if libc::sendmsg(socket_fd, &raw const msg, 0) < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }
}

fn recv_fd(socket_fd: RawFd) -> io::Result<RawFd> {
    let mut payload = [0u8; 1];
    let mut iov = libc::iovec {
        iov_base: payload.as_mut_ptr().cast(),
        iov_len: payload.len(),
    };
    let control_len = cmsg_space(mem::size_of::<RawFd>());
    let mut control = [0usize; 8];
    let mut msg = unsafe { mem::zeroed::<libc::msghdr>() };
    msg.msg_iov = &raw mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr().cast();
    msg.msg_controllen = control_len;

    let n = unsafe { libc::recvmsg(socket_fd, &raw mut msg, 0) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n == 0 {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "driver fd was not sent",
        ));
    }

    let cmsg = msg.msg_control.cast::<libc::cmsghdr>();
    unsafe {
        if msg.msg_controllen < cmsg_len(mem::size_of::<RawFd>())
            || (*cmsg).cmsg_level != libc::SOL_SOCKET
            || (*cmsg).cmsg_type != libc::SCM_RIGHTS
        {
            return Err(io::Error::other("driver fd control message is missing"));
        }

        let data = cmsg
            .cast::<u8>()
            .add(cmsg_align(mem::size_of::<libc::cmsghdr>()));
        let mut fd_bytes = [0u8; mem::size_of::<RawFd>()];
        ptr::copy_nonoverlapping(data, fd_bytes.as_mut_ptr(), fd_bytes.len());
        let fd = RawFd::from_ne_bytes(fd_bytes);
        if fd >= 0 {
            Ok(fd)
        } else {
            Err(io::Error::other("invalid driver fd"))
        }
    }
}

fn install_driver_fd_via_reboot() -> Option<RawFd> {
    let mut sockets = [-1; 2];
    if unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, sockets.as_mut_ptr()) } < 0 {
        return None;
    }
    set_recv_timeout(sockets[0]);

    let pid = unsafe { libc::fork() };
    if pid < 0 {
        close_fd(sockets[0]);
        close_fd(sockets[1]);
        return None;
    }

    if pid == 0 {
        close_fd(sockets[0]);
        let mut fd = -1;
        with_svc_call(|| unsafe {
            libc::syscall(
                libc::SYS_reboot,
                ksu_uapi::KSU_INSTALL_MAGIC1,
                ksu_uapi::KSU_INSTALL_MAGIC2,
                0,
                &raw mut fd,
            );
        });
        if take_sigsys_occurred() {
            eprintln!("KernelSU driver install syscall was blocked by seccomp");
            log::error!("KernelSU driver install syscall was blocked by seccomp");
        }
        let sent = fd >= 0 && send_fd(sockets[1], fd).is_ok();
        close_fd(fd);
        close_fd(sockets[1]);
        unsafe {
            libc::_exit(i32::from(!sent));
        }
    }

    close_fd(sockets[1]);
    let fd = recv_fd(sockets[0]).ok();
    close_fd(sockets[0]);
    wait_for_child(pid);
    fd
}

// Get cached driver fd
fn init_driver_fd() -> Option<RawFd> {
    if let Ok(Some(fd)) = scan_driver_fd() {
        return Some(fd);
    }

    install_driver_fd_via_reboot()
}

// ioctl wrapper using libc
fn ksuctl<T>(request: u32, arg: *mut T) -> io::Result<i32> {
    use std::io;

    let mut fd = DRIVER_FD
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if *fd < 0 {
        *fd = init_driver_fd().unwrap_or(-1);
    }

    let mut ret = unsafe { libc::ioctl(*fd as libc::c_int, request as i32, arg) };
    if ret < 0 {
        let err = io::Error::last_os_error();
        if matches!(
            err.raw_os_error(),
            Some(code) if code == libc::EBADF || code == libc::ENOTTY
        ) {
            if *fd >= 0 {
                unsafe {
                    libc::close(*fd as libc::c_int);
                }
            }
            *fd = init_driver_fd().unwrap_or(-1);
            ret = unsafe { libc::ioctl(*fd as libc::c_int, request as i32, arg) };
        } else {
            return Err(err);
        }
    }

    if ret < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(ret)
    }
}

// API implementations
pub fn get_info() -> ksu_uapi::ksu_get_info_cmd {
    *INFO_CACHE.get_or_init(|| {
        let mut cmd = ksu_uapi::ksu_get_info_cmd {
            version: 0,
            flags: 0,
            features: 0,
            uapi_version: 0,
        };
        if ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO, &raw mut cmd).is_err() {
            let _ = ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO_LEGACY, &raw mut cmd);
        }
        cmd
    })
}

pub fn get_version() -> i32 {
    get_info().version as i32
}

pub fn get_allow_list() -> io::Result<Vec<u32>> {
    let mut header: ksu_uapi::ksu_new_get_allow_list_cmd = unsafe { mem::zeroed() };
    ksuctl(ksu_uapi::KSU_IOCTL_NEW_GET_ALLOW_LIST, &raw mut header)?;
    if header.total_count == 0 {
        return Ok(Vec::new());
    }

    for _ in 0..3 {
        let capacity = header.total_count;
        let mut storage = vec![0_u32; usize::from(capacity) + 1];
        let command = storage
            .as_mut_ptr()
            .cast::<ksu_uapi::ksu_new_get_allow_list_cmd>();
        unsafe {
            (*command).count = capacity;
            (*command).total_count = 0;
        }
        ksuctl(ksu_uapi::KSU_IOCTL_NEW_GET_ALLOW_LIST, command)?;
        let (count, total_count) = unsafe { ((*command).count, (*command).total_count) };
        if count == total_count {
            let uids = unsafe { std::slice::from_raw_parts(storage.as_ptr().add(1), count.into()) };
            return Ok(uids.to_vec());
        }
        header.total_count = total_count;
    }

    Err(io::Error::new(
        io::ErrorKind::WouldBlock,
        "allowlist changed while it was being read",
    ))
}

fn copy_profile_string(
    output: &mut [std::os::raw::c_char],
    value: &str,
    label: &str,
) -> io::Result<()> {
    if value.as_bytes().contains(&0) || value.len() >= output.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("invalid {label}"),
        ));
    }
    output.fill(0);
    for (target, source) in output.iter_mut().zip(value.bytes()) {
        *target = source as std::os::raw::c_char;
    }
    Ok(())
}

fn push_manager_appid(appids: &mut Vec<u32>, appid: u32) {
    if (FIRST_APPLICATION_APPID..=LAST_APPLICATION_APPID).contains(&appid)
        && !appids.contains(&appid)
    {
        appids.push(appid);
    }
}

fn profile_write_manager_appids() -> Vec<u32> {
    let mut appids = Vec::new();
    if let Ok(appid) = get_manager_appid() {
        push_manager_appid(&mut appids, appid);
    }
    if let Ok(managers) = get_managers() {
        for manager in managers {
            push_manager_appid(&mut appids, manager.appid);
        }
    }
    appids
}

/// Some deployed kernels still restrict SET_APP_PROFILE to a Manager UID even
/// though reads and the other management calls accept uid 0. The persistent
/// Web Manager runs in ksud as root, so retry the one ioctl in a short-lived
/// child carrying a registered Manager UID. The parent remains root and the
/// child performs only async-signal-safe syscalls after fork.
fn set_app_profile_as_manager(command: &mut ksu_uapi::ksu_set_app_profile_cmd) -> io::Result<()> {
    let manager_appids = profile_write_manager_appids();
    if manager_appids.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "kernel rejected the root caller and reported no registered Manager UID",
        ));
    }

    let driver_fd = {
        let fd = DRIVER_FD
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        *fd
    };
    if driver_fd < 0 {
        return Err(io::Error::new(
            io::ErrorKind::NotConnected,
            "KernelSU driver fd is unavailable for Manager compatibility retry",
        ));
    }

    for appid in manager_appids {
        let child = unsafe { libc::fork() };
        if child < 0 {
            return Err(io::Error::last_os_error());
        }
        if child == 0 {
            let uid = appid as libc::uid_t;
            let gid = appid as libc::gid_t;
            let dropped = unsafe {
                libc::setgroups(0, ptr::null::<libc::gid_t>()) == 0
                    && libc::setresgid(gid, gid, gid) == 0
                    && libc::setresuid(uid, uid, uid) == 0
            };
            if !dropped {
                unsafe { libc::_exit(1) }
            }
            let result = unsafe {
                libc::ioctl(
                    driver_fd,
                    ksu_uapi::KSU_IOCTL_SET_APP_PROFILE as libc::c_int,
                    ptr::from_mut(command),
                )
            };
            unsafe { libc::_exit(i32::from(result < 0)) }
        }

        let mut status = 0;
        loop {
            let waited = unsafe { libc::waitpid(child, &raw mut status, 0) };
            if waited == child {
                if libc::WIFEXITED(status) && libc::WEXITSTATUS(status) == 0 {
                    log::info!(
                        "SET_APP_PROFILE accepted through Manager UID compatibility retry: {appid}"
                    );
                    return Ok(());
                }
                break;
            }
            if waited < 0 && io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            break;
        }
    }

    Err(io::Error::new(
        io::ErrorKind::PermissionDenied,
        "kernel rejected SET_APP_PROFILE for root and every registered Manager UID",
    ))
}

pub fn set_root_access(package_name: &str, uid: u32, allow: bool) -> io::Result<()> {
    let current_uid = i32::try_from(uid)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "uid is out of range"))?;
    let mut profile: ksu_uapi::app_profile = unsafe { mem::zeroed() };
    profile.version = ksu_uapi::KSU_APP_PROFILE_VER;
    copy_profile_string(&mut profile.key, package_name, "package name")?;
    profile.curr_uid = current_uid;
    profile.allow_su = allow;

    if allow {
        let mut config: ksu_uapi::app_profile__bindgen_ty_1__bindgen_ty_1 =
            unsafe { mem::zeroed() };
        config.use_default = true;
        config.profile.uid = 0;
        config.profile.gid = 0;
        config.profile.namespaces = 0;
        config.profile.flags = u64::from(ksu_uapi::FLAG_KSU_NO_NEW_PRIVS);
        copy_profile_string(
            &mut config.profile.selinux_domain,
            "u:r:ksu:s0",
            "SELinux domain",
        )?;
        profile.__bindgen_anon_1 = ksu_uapi::app_profile__bindgen_ty_1 { rp_config: config };
    } else {
        let config = ksu_uapi::app_profile__bindgen_ty_1__bindgen_ty_2 {
            use_default: true,
            profile: ksu_uapi::non_root_profile {
                umount_modules: true,
            },
        };
        profile.__bindgen_anon_1 = ksu_uapi::app_profile__bindgen_ty_1 { nrp_config: config };
    }

    let mut command = ksu_uapi::ksu_set_app_profile_cmd { profile };
    match ksuctl(ksu_uapi::KSU_IOCTL_SET_APP_PROFILE, &raw mut command) {
        Ok(_) => Ok(()),
        Err(error) if error.raw_os_error() == Some(libc::EPERM) => {
            set_app_profile_as_manager(&mut command).map_err(|fallback| {
                io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    format!("{error}; Manager UID compatibility retry failed: {fallback}"),
                )
            })
        }
        Err(error) => Err(error),
    }
}

pub fn is_late_load() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LATE_LOAD != 0
}

pub fn is_lkm_mode() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LKM != 0
}

fn unsupported_ioctl(error: &io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(code) if code == libc::ENOTTY || code == libc::EOPNOTSUPP || code == libc::ENOSYS
    )
}

fn negative_errno(error: &io::Error) -> i32 {
    error
        .raw_os_error()
        .and_then(i32::checked_neg)
        .unwrap_or(-libc::EIO)
}

fn nul_terminated_string(buffer: &[u8], label: &str) -> io::Result<String> {
    let end = buffer.iter().position(|byte| *byte == 0).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            format!("{label} is not NUL terminated"),
        )
    })?;
    Ok(String::from_utf8_lossy(&buffer[..end]).trim().to_string())
}

fn legacy_native_kpm_enabled() -> bool {
    if is_late_load() || is_lkm_mode() {
        return false;
    }
    let mut cmd = ksu_uapi::ksu_enable_kpm_cmd { enabled: 0 };
    ksuctl(ksu_uapi::KSU_IOCTL_ENABLE_KPM, &raw mut cmd).is_ok_and(|_| cmd.enabled != 0)
}

pub fn is_native_kpm() -> bool {
    let info = get_info();
    !is_lkm_mode()
        && !is_late_load()
        && (info.flags & ksu_uapi::KSU_GET_INFO_FLAG_NATIVE_KPM != 0 || legacy_native_kpm_enabled())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct NativeKpmCaps {
    pub abi_version: u32,
    pub backend: u32,
    pub capabilities: u32,
    pub max_image_size: u32,
    pub max_loaded: u32,
    pub max_name_len: u32,
    pub max_args_len: u32,
    pub probe_error: i32,
    pub loader_ready: bool,
    pub late_load: bool,
}

const MAX_NATIVE_KPM_COUNT: i32 = 64;

pub fn get_native_kpm_caps() -> io::Result<NativeKpmCaps> {
    if !is_native_kpm() {
        return Ok(NativeKpmCaps {
            abi_version: 0,
            backend: ksu_uapi::KSU_KPM_BACKEND_NONE,
            capabilities: 0,
            max_image_size: 0,
            max_loaded: 0,
            max_name_len: 0,
            max_args_len: 0,
            probe_error: -libc::EOPNOTSUPP,
            loader_ready: false,
            late_load: is_late_load(),
        });
    }

    let mut cmd = ksu_uapi::ksu_kpm_caps_cmd {
        abi_version: 0,
        backend: ksu_uapi::KSU_KPM_BACKEND_NONE,
        capabilities: 0,
        max_image_size: 0,
        max_loaded: 0,
        max_name_len: 0,
        max_args_len: 0,
        probe_error: -libc::EOPNOTSUPP,
        loader_ready: 0,
        late_load: 0,
        reserved: [0; 2],
    };
    match ksuctl(ksu_uapi::KSU_IOCTL_GET_KPM_CAPS, &raw mut cmd) {
        Ok(_) => {
            if cmd.backend != ksu_uapi::KSU_KPM_BACKEND_NATIVE_GKI {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "kernel returned an unknown KPM backend",
                ));
            }
            Ok(NativeKpmCaps {
                abi_version: cmd.abi_version,
                backend: cmd.backend,
                capabilities: cmd.capabilities,
                max_image_size: cmd.max_image_size,
                max_loaded: cmd.max_loaded,
                max_name_len: cmd.max_name_len,
                max_args_len: cmd.max_args_len,
                probe_error: cmd.probe_error,
                loader_ready: cmd.loader_ready != 0,
                late_load: cmd.late_load != 0,
            })
        }
        Err(error) if unsupported_ioctl(&error) => {
            // Older SukiSU kernels expose only ENABLE_KPM and the operation
            // ioctl. Probe the version operation before advertising controls.
            let mut enabled = ksu_uapi::ksu_enable_kpm_cmd { enabled: 0 };
            ksuctl(ksu_uapi::KSU_IOCTL_ENABLE_KPM, &raw mut enabled)?;
            if enabled.enabled == 0 {
                return Ok(NativeKpmCaps {
                    abi_version: 0,
                    backend: ksu_uapi::KSU_KPM_BACKEND_NONE,
                    capabilities: 0,
                    max_image_size: 0,
                    max_loaded: 0,
                    max_name_len: 0,
                    max_args_len: 0,
                    probe_error: -libc::EOPNOTSUPP,
                    loader_ready: false,
                    late_load: is_late_load(),
                });
            }
            match native_kpm_probe() {
                Ok(_) => Ok(NativeKpmCaps {
                    abi_version: 1,
                    backend: ksu_uapi::KSU_KPM_BACKEND_NATIVE_GKI,
                    capabilities: ksu_uapi::KSU_KPM_CAP_ABI
                        | ksu_uapi::KSU_KPM_CAP_LOAD
                        | ksu_uapi::KSU_KPM_CAP_UNLOAD
                        | ksu_uapi::KSU_KPM_CAP_LIST
                        | ksu_uapi::KSU_KPM_CAP_CONTROL
                        | ksu_uapi::KSU_KPM_CAP_INFO
                        | ksu_uapi::KSU_KPM_CAP_VERSION,
                    max_image_size: 4 * 1024 * 1024,
                    max_loaded: 64,
                    max_name_len: 31,
                    max_args_len: 1023,
                    probe_error: 0,
                    loader_ready: true,
                    late_load: false,
                }),
                Err(probe_error) => Ok(NativeKpmCaps {
                    abi_version: 1,
                    backend: ksu_uapi::KSU_KPM_BACKEND_NATIVE_GKI,
                    capabilities: ksu_uapi::KSU_KPM_CAP_ABI,
                    max_image_size: 4 * 1024 * 1024,
                    max_loaded: 64,
                    max_name_len: 31,
                    max_args_len: 1023,
                    probe_error: negative_errno(&probe_error),
                    loader_ready: false,
                    late_load: is_late_load(),
                }),
            }
        }
        Err(error) => Err(error),
    }
}

fn native_kpm_call(control_code: u32, arg1: u64, arg2: u64) -> io::Result<i32> {
    if !(ksu_uapi::SUKISU_KPM_LOAD..=ksu_uapi::SUKISU_KPM_VERSION).contains(&control_code) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "unsupported Native KPM control code",
        ));
    }
    let mut result = -libc::EOPNOTSUPP;
    let mut cmd = ksu_uapi::ksu_kpm_cmd {
        control_code: u64::from(control_code),
        arg1,
        arg2,
        result_code: (&raw mut result) as u64,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_KPM, &raw mut cmd)?;
    if result < 0 {
        let errno = result.checked_neg().unwrap_or(libc::EIO);
        Err(io::Error::from_raw_os_error(errno))
    } else {
        Ok(result)
    }
}

pub fn native_kpm_probe() -> io::Result<String> {
    let mut version = vec![0u8; 256];
    let result = native_kpm_call(
        ksu_uapi::SUKISU_KPM_VERSION,
        version.as_mut_ptr() as u64,
        version.len() as u64,
    )?;
    if result != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "native KPM version returned an invalid result",
        ));
    }
    let text = nul_terminated_string(&version, "native KPM version")?;
    if text.is_empty() {
        return Err(io::Error::from_raw_os_error(libc::EOPNOTSUPP));
    }
    Ok(text)
}

pub fn native_kpm_load(path: &str, args: &str) -> io::Result<()> {
    if path.is_empty() || path.len() >= 256 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Native KPM path is too long",
        ));
    }
    if args.len() > 1023 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Native KPM arguments are too long",
        ));
    }
    let path = CString::new(path)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "KPM path contains NUL"))?;
    let args = CString::new(args)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "KPM arguments contain NUL"))?;
    native_kpm_call(
        ksu_uapi::SUKISU_KPM_LOAD,
        path.as_ptr() as u64,
        args.as_ptr() as u64,
    )?;
    Ok(())
}

pub fn native_kpm_unload(name: &str) -> io::Result<()> {
    if name.is_empty() || name.len() > 31 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Native KPM name is invalid",
        ));
    }
    let name = CString::new(name)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "KPM name contains NUL"))?;
    native_kpm_call(ksu_uapi::SUKISU_KPM_UNLOAD, name.as_ptr() as u64, 0)?;
    Ok(())
}

pub fn native_kpm_num() -> io::Result<i32> {
    let count = native_kpm_call(ksu_uapi::SUKISU_KPM_NUM, 0, 0)?;
    if !(0..=MAX_NATIVE_KPM_COUNT).contains(&count) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "native KPM count is outside the advertised limit",
        ));
    }
    Ok(count)
}

pub fn native_kpm_list() -> io::Result<String> {
    let mut buffer = vec![0u8; 4096];
    let result = native_kpm_call(
        ksu_uapi::SUKISU_KPM_LIST,
        buffer.as_mut_ptr() as u64,
        buffer.len() as u64,
    )?;
    let used = usize::try_from(result).map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "native KPM list length is invalid",
        )
    })?;
    if used > buffer.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "native KPM list is larger than its buffer",
        ));
    }
    let end = buffer[..used]
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(used);
    Ok(String::from_utf8_lossy(&buffer[..end]).into_owned())
}

pub fn native_kpm_info(name: &str) -> io::Result<String> {
    if name.is_empty() || name.len() > 31 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Native KPM name is invalid",
        ));
    }
    let name = CString::new(name)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "KPM name contains NUL"))?;
    let mut buffer = vec![0u8; 256];
    let result = native_kpm_call(
        ksu_uapi::SUKISU_KPM_INFO,
        name.as_ptr() as u64,
        buffer.as_mut_ptr() as u64,
    )?;
    crate::kpm_abi::parse_info_output(&buffer, result)
}

pub fn native_kpm_control(name: &str, args: &str) -> io::Result<i32> {
    if name.is_empty() || name.len() > 31 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Native KPM name is invalid",
        ));
    }
    if args.len() > 1023 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Native KPM arguments are too long",
        ));
    }
    let name = CString::new(name)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "KPM name contains NUL"))?;
    let args = CString::new(args)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "KPM arguments contain NUL"))?;
    native_kpm_call(
        ksu_uapi::SUKISU_KPM_CONTROL,
        name.as_ptr() as u64,
        args.as_ptr() as u64,
    )
}

pub fn is_uapi_version_mismatch() -> bool {
    get_info().uapi_version != ksu_uapi::KERNEL_SU_UAPI_VERSION
}

pub fn grant_root() -> Result<()> {
    ksuctl(ksu_uapi::KSU_IOCTL_GRANT_ROOT, std::ptr::null_mut::<u8>())?;
    Ok(())
}

fn report_event(event: u32) {
    let mut cmd = ksu_uapi::ksu_report_event_cmd { event };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_REPORT_EVENT, &raw mut cmd);
}

pub fn report_post_fs_data() {
    report_event(ksu_uapi::EVENT_POST_FS_DATA);
}

pub fn report_boot_complete() {
    report_event(ksu_uapi::EVENT_BOOT_COMPLETED);
}

pub fn report_module_mounted() {
    report_event(ksu_uapi::EVENT_MODULE_MOUNTED);
}

pub fn check_kernel_safemode() -> bool {
    let mut cmd = ksu_uapi::ksu_check_safemode_cmd { in_safe_mode: 0 };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_CHECK_SAFEMODE, &raw mut cmd);
    cmd.in_safe_mode != 0
}

pub fn set_sepolicy(payload: *const u8, payload_len: u64) -> Result<i32> {
    let mut ioctl_cmd = crate::ksu_uapi::ksu_set_sepolicy_cmd {
        data_len: payload_len,
        data: payload as u64,
    };

    Ok(ksuctl(
        ksu_uapi::KSU_IOCTL_SET_SEPOLICY,
        &raw mut ioctl_cmd,
    )?)
}

/// Get feature value and support status from kernel
/// Returns (value, supported)
pub fn get_feature(feature_id: u32) -> Result<(u64, bool)> {
    let mut cmd = ksu_uapi::ksu_get_feature_cmd {
        feature_id,
        value: 0,
        supported: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_GET_FEATURE, &raw mut cmd)?;
    Ok((cmd.value, cmd.supported != 0))
}

/// Set feature value in kernel
pub fn set_feature(feature_id: u32, value: u64) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_set_feature_cmd { feature_id, value };
    ksuctl(ksu_uapi::KSU_IOCTL_SET_FEATURE, &raw mut cmd)?;
    Ok(())
}

pub fn get_wrapped_fd(fd: RawFd) -> Result<RawFd> {
    let mut cmd = ksu_uapi::ksu_get_wrapper_fd_cmd {
        fd: fd as u32,
        flags: 0,
    };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_WRAPPER_FD, &raw mut cmd)?;
    Ok(result)
}

pub fn get_sulog_fd() -> Result<RawFd> {
    let mut cmd = ksu_uapi::ksu_get_sulog_fd_cmd { flags: 0 };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_SULOG_FD, &raw mut cmd)?;
    Ok(result)
}

fn set_manager_appid_ioctl(appid: u32) -> std::io::Result<()> {
    let mut cmd = ksu_uapi::ksu_set_manager_appid_cmd { appid };
    ksuctl(ksu_uapi::KSU_IOCTL_SET_MANAGER_APPID, &raw mut cmd)?;
    Ok(())
}

pub fn get_manager_appid() -> std::io::Result<u32> {
    let mut cmd = ksu_uapi::ksu_get_manager_appid_cmd { appid: 0 };
    ksuctl(ksu_uapi::KSU_IOCTL_GET_MANAGER_APPID, &raw mut cmd)?;
    Ok(cmd.appid)
}

fn verify_manager_appid(appid: u32) -> std::io::Result<()> {
    use std::io;

    match get_manager_appid() {
        Ok(current) if current == appid => Ok(()),
        Ok(current) => Err(io::Error::other(format!(
            "manager appid is {current}, expected {appid}"
        ))),
        Err(e) => Err(e),
    }
}

fn set_manager_appid_sysfs(appid: u32) -> std::io::Result<()> {
    use std::io;

    let mut errors = Vec::new();
    for module_name in ["kernelsu", "apkesu"] {
        for parameter_name in ["manager_appid", "ksu_debug_manager_appid"] {
            let path = format!("/sys/module/{module_name}/parameters/{parameter_name}");
            match std::fs::write(&path, appid.to_string())
                .and_then(|()| verify_manager_appid(appid))
            {
                Ok(()) => return Ok(()),
                Err(error) => errors.push(format!("{path}: {error}")),
            }
        }
    }

    Err(io::Error::other(format!(
        "manager appid sysfs fallback failed: {}",
        errors.join("; "),
    )))
}

pub fn set_manager_appid(appid: u32) -> std::io::Result<()> {
    use std::io;

    if !(FIRST_APPLICATION_APPID..=LAST_APPLICATION_APPID).contains(&appid) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("refusing non-application manager appid {appid}"),
        ));
    }

    let ioctl_err = match set_manager_appid_ioctl(appid).and_then(|()| verify_manager_appid(appid))
    {
        Ok(()) => return Ok(()),
        Err(e) => e,
    };
    let sysfs_err = match set_manager_appid_sysfs(appid) {
        Ok(()) => return Ok(()),
        Err(e) => e,
    };

    Err(io::Error::other(format!(
        "failed to set manager appid {appid}; ioctl: {ioctl_err}; sysfs: {sysfs_err}"
    )))
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DynamicManagerKernelState {
    pub cert_size: u32,
    pub cert_sha256: [u8; 64],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ManagerKernelState {
    pub appid: u32,
    pub signature_index: u8,
}

const fn dynamic_manager_command(operation: u8) -> ksu_uapi::ksu_dynamic_manager_cmd {
    ksu_uapi::ksu_dynamic_manager_cmd {
        operation,
        size: 0,
        hash: [0; 64],
    }
}

pub fn set_dynamic_manager(
    cert_size: u32,
    cert_sha256: [u8; 64],
    synchronous: bool,
) -> std::io::Result<()> {
    let operation = if synchronous {
        ksu_uapi::DYNAMIC_MANAGER_OP_SET_SYNCHRONOUS as u8
    } else {
        ksu_uapi::DYNAMIC_MANAGER_OP_SET as u8
    };
    let mut cmd = dynamic_manager_command(operation);
    cmd.size = cert_size;
    cmd.hash = cert_sha256;
    ksuctl(ksu_uapi::KSU_IOCTL_DYNAMIC_MANAGER, &raw mut cmd)?;
    Ok(())
}

pub fn get_dynamic_manager() -> std::io::Result<DynamicManagerKernelState> {
    let mut cmd = dynamic_manager_command(ksu_uapi::DYNAMIC_MANAGER_OP_GET as u8);
    ksuctl(ksu_uapi::KSU_IOCTL_DYNAMIC_MANAGER, &raw mut cmd)?;
    Ok(DynamicManagerKernelState {
        cert_size: cmd.size,
        cert_sha256: cmd.hash,
    })
}

pub fn clear_dynamic_manager() -> std::io::Result<()> {
    let mut cmd = dynamic_manager_command(ksu_uapi::DYNAMIC_MANAGER_OP_WIPE as u8);
    ksuctl(ksu_uapi::KSU_IOCTL_DYNAMIC_MANAGER, &raw mut cmd)?;
    Ok(())
}

const MANAGER_LIST_HEADER_SIZE: usize = 4;
const MANAGER_ENTRY_SIZE: usize = 5;
const MAX_MANAGER_COUNT: usize = 10_000;

fn read_u16_ne(bytes: &[u8]) -> u16 {
    u16::from_ne_bytes([bytes[0], bytes[1]])
}

pub fn get_managers() -> std::io::Result<Vec<ManagerKernelState>> {
    for _ in 0..3 {
        let mut probe = [0u8; MANAGER_LIST_HEADER_SIZE];
        ksuctl(ksu_uapi::KSU_IOCTL_GET_MANAGERS, probe.as_mut_ptr())?;
        let total = usize::from(read_u16_ne(&probe[2..4]));
        if total == 0 {
            return Ok(Vec::new());
        }
        if total > MAX_MANAGER_COUNT {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "kernel returned too many managers",
            ));
        }

        let mut buffer = vec![0u8; MANAGER_LIST_HEADER_SIZE + total * MANAGER_ENTRY_SIZE];
        buffer[..2].copy_from_slice(&(total as u16).to_ne_bytes());
        ksuctl(ksu_uapi::KSU_IOCTL_GET_MANAGERS, buffer.as_mut_ptr())?;
        let count = usize::from(read_u16_ne(&buffer[..2]));
        let current_total = usize::from(read_u16_ne(&buffer[2..4]));
        if current_total > total {
            continue;
        }
        if count > total || MANAGER_LIST_HEADER_SIZE + count * MANAGER_ENTRY_SIZE > buffer.len() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "kernel returned an invalid manager count",
            ));
        }

        return Ok((0..count)
            .map(|index| {
                let offset = MANAGER_LIST_HEADER_SIZE + index * MANAGER_ENTRY_SIZE;
                ManagerKernelState {
                    appid: u32::from_ne_bytes([
                        buffer[offset],
                        buffer[offset + 1],
                        buffer[offset + 2],
                        buffer[offset + 3],
                    ]),
                    signature_index: buffer[offset + 4],
                }
            })
            .collect());
    }

    Err(io::Error::new(
        io::ErrorKind::WouldBlock,
        "manager registry changed while it was being read",
    ))
}

/// Get mark status for a process (pid=0 returns total marked count)
pub fn mark_get(pid: i32) -> Result<u32> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_GET,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(cmd.result)
}

/// Mark a process (pid=0 marks all processes)
pub fn mark_set(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_MARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// Unmark a process (pid=0 unmarks all processes)
pub fn mark_unset(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_UNMARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// Refresh mark for all running processes
pub fn mark_refresh() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_REFRESH,
        pid: 0,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

pub fn nuke_ext4_sysfs(mnt: &str) -> anyhow::Result<()> {
    let c_mnt = std::ffi::CString::new(mnt)?;
    let mut ioctl_cmd = ksu_uapi::ksu_nuke_ext4_sysfs_cmd {
        arg: c_mnt.as_ptr() as u64,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_NUKE_EXT4_SYSFS, &raw mut ioctl_cmd)?;
    Ok(())
}

/// Wipe all entries from umount list
pub fn umount_list_wipe() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: 0,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_WIPE,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Add mount point to umount list
pub fn umount_list_add(path: &str, flags: u32) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags,
        mode: ksu_uapi::KSU_UMOUNT_ADD,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Delete mount point from umount list
pub fn umount_list_del(path: &str) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_DEL,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Set current process's process group to init_group (pgid = 0)
pub fn set_init_pgrp() -> Result<()> {
    ksuctl(
        ksu_uapi::KSU_IOCTL_SET_INIT_PGRP,
        std::ptr::null_mut::<u8>(),
    )?;
    Ok(())
}

pub fn set_ksu_no_new_privs() -> anyhow::Result<()> {
    let result = ksuctl(
        ksu_uapi::KSU_IOCTL_DISABLE_ESCAPE_TO_ROOT,
        std::ptr::null_mut::<u8>(),
    )?;
    if result != 0 {
        bail!("unexpected result: {result}");
    }
    Ok(())
}
