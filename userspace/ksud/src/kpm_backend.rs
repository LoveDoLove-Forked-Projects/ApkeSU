#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum KpmBackend {
    NativeGki,
    KpatchNext,
    Unsupported,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BootLoadAction {
    DisabledByPolicy,
    LoadNative,
    WaitForNativeLoader,
    DelegateToKpatchNext,
    Unsupported,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PolicyChangeAction {
    NoChange,
    Enable,
    DisableKnownRuntime,
    DisableUnknownRuntime,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PendingBootAction {
    ClearInvalid,
    KeepCurrentBoot,
    QuarantinePreviousBoot,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PendingMarkerAction {
    ClearAfterOperation,
    KeepUntilBootCompleted,
}

pub const fn select(is_lkm: bool, late_load: bool, native_flag: bool) -> KpmBackend {
    if late_load {
        KpmBackend::Unsupported
    } else if is_lkm {
        KpmBackend::KpatchNext
    } else if native_flag {
        KpmBackend::NativeGki
    } else {
        KpmBackend::Unsupported
    }
}

pub const fn boot_load_action(
    backend: KpmBackend,
    policy_enabled: bool,
    native_loader_ready: bool,
) -> BootLoadAction {
    if !policy_enabled {
        return BootLoadAction::DisabledByPolicy;
    }
    match backend {
        KpmBackend::NativeGki if native_loader_ready => BootLoadAction::LoadNative,
        KpmBackend::NativeGki => BootLoadAction::WaitForNativeLoader,
        KpmBackend::KpatchNext => BootLoadAction::DelegateToKpatchNext,
        KpmBackend::Unsupported => BootLoadAction::Unsupported,
    }
}

pub const fn policy_change_action(
    current_enabled: bool,
    requested_enabled: bool,
    runtime_known: bool,
) -> PolicyChangeAction {
    if !requested_enabled {
        return if runtime_known {
            PolicyChangeAction::DisableKnownRuntime
        } else {
            PolicyChangeAction::DisableUnknownRuntime
        };
    }
    if current_enabled {
        PolicyChangeAction::NoChange
    } else {
        PolicyChangeAction::Enable
    }
}

pub const fn pending_boot_action(marker_valid: bool, current_boot: bool) -> PendingBootAction {
    if !marker_valid {
        PendingBootAction::ClearInvalid
    } else if current_boot {
        PendingBootAction::KeepCurrentBoot
    } else {
        PendingBootAction::QuarantinePreviousBoot
    }
}

pub const fn pending_marker_action(keep_until_boot_completed: bool) -> PendingMarkerAction {
    if keep_until_boot_completed {
        PendingMarkerAction::KeepUntilBootCompleted
    } else {
        PendingMarkerAction::ClearAfterOperation
    }
}

pub const fn native_boot_batch_limit(reported_max: u32, marker_capacity: usize) -> usize {
    let reported_max = reported_max as usize;
    if reported_max < marker_capacity {
        reported_max
    } else {
        marker_capacity
    }
}

pub const fn native_boot_new_load_limit(
    reported_max: u32,
    current_loaded: u32,
    marker_capacity: usize,
    already_live_candidates: usize,
) -> usize {
    let runtime_slots = (reported_max as usize).saturating_sub(current_loaded as usize);
    let marker_slots = marker_capacity.saturating_sub(already_live_candidates);
    if runtime_slots < marker_slots {
        runtime_slots
    } else {
        marker_slots
    }
}

#[cfg(test)]
mod tests {
    use super::{
        BootLoadAction, KpmBackend, PendingBootAction, PendingMarkerAction, PolicyChangeAction,
        boot_load_action, native_boot_batch_limit, native_boot_new_load_limit, pending_boot_action,
        pending_marker_action, policy_change_action, select,
    };

    #[test]
    fn late_load_always_disables_both_backends() {
        assert_eq!(select(true, true, true), KpmBackend::Unsupported);
        assert_eq!(select(false, true, true), KpmBackend::Unsupported);
    }

    #[test]
    fn lkm_selects_kpatch_next_only() {
        assert_eq!(select(true, false, false), KpmBackend::KpatchNext);
        assert_eq!(select(true, false, true), KpmBackend::KpatchNext);
    }

    #[test]
    fn built_in_native_flag_selects_native_gki() {
        assert_eq!(select(false, false, true), KpmBackend::NativeGki);
        assert_eq!(select(false, false, false), KpmBackend::Unsupported);
    }

    #[test]
    fn disabled_policy_never_loads_a_backend() {
        assert_eq!(
            boot_load_action(KpmBackend::NativeGki, false, true),
            BootLoadAction::DisabledByPolicy
        );
        assert_eq!(
            boot_load_action(KpmBackend::KpatchNext, false, true),
            BootLoadAction::DisabledByPolicy
        );
    }

    #[test]
    fn native_boot_waits_for_the_early_loader() {
        assert_eq!(
            boot_load_action(KpmBackend::NativeGki, true, false),
            BootLoadAction::WaitForNativeLoader
        );
        assert_eq!(
            boot_load_action(KpmBackend::NativeGki, true, true),
            BootLoadAction::LoadNative
        );
        assert_eq!(
            boot_load_action(KpmBackend::KpatchNext, true, false),
            BootLoadAction::DelegateToKpatchNext
        );
    }

    #[test]
    fn repeated_disable_still_reconciles_runtime_state() {
        assert_eq!(
            policy_change_action(false, false, true),
            PolicyChangeAction::DisableKnownRuntime
        );
        assert_eq!(
            policy_change_action(false, false, false),
            PolicyChangeAction::DisableUnknownRuntime
        );
        assert_eq!(
            policy_change_action(true, true, true),
            PolicyChangeAction::NoChange
        );
        assert_eq!(
            policy_change_action(false, true, true),
            PolicyChangeAction::Enable
        );
    }

    #[test]
    fn pending_markers_distinguish_current_and_failed_boots() {
        assert_eq!(
            pending_boot_action(false, false),
            PendingBootAction::ClearInvalid
        );
        assert_eq!(
            pending_boot_action(true, true),
            PendingBootAction::KeepCurrentBoot
        );
        assert_eq!(
            pending_boot_action(true, false),
            PendingBootAction::QuarantinePreviousBoot
        );
    }

    #[test]
    fn boot_batches_keep_markers_but_manual_operations_clear_them() {
        assert_eq!(
            pending_marker_action(true),
            PendingMarkerAction::KeepUntilBootCompleted
        );
        assert_eq!(
            pending_marker_action(false),
            PendingMarkerAction::ClearAfterOperation
        );
    }

    #[test]
    fn native_batch_never_exceeds_recoverable_marker_capacity() {
        assert_eq!(native_boot_batch_limit(0, 64), 0);
        assert_eq!(native_boot_batch_limit(16, 64), 16);
        assert_eq!(native_boot_batch_limit(64, 64), 64);
        assert_eq!(native_boot_batch_limit(128, 64), 64);
    }

    #[test]
    fn native_batch_reserves_runtime_and_recovery_capacity() {
        assert_eq!(native_boot_new_load_limit(64, 0, 64, 0), 64);
        assert_eq!(native_boot_new_load_limit(64, 10, 64, 0), 54);
        assert_eq!(native_boot_new_load_limit(64, 10, 64, 5), 54);
        assert_eq!(native_boot_new_load_limit(64, 60, 64, 8), 4);
        assert_eq!(native_boot_new_load_limit(64, 64, 64, 8), 0);
        assert_eq!(native_boot_new_load_limit(128, 32, 64, 40), 24);
    }
}
