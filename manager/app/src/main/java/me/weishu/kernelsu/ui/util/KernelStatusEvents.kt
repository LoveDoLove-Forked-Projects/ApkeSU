package me.weishu.kernelsu.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object KernelStatusEvents {
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick
    private val _kpmDisableTick = MutableStateFlow(0)
    val kpmDisableTick: StateFlow<Int> = _kpmDisableTick
    private val _kpmEnableTick = MutableStateFlow(0)
    val kpmEnableTick: StateFlow<Int> = _kpmEnableTick

    fun requestRefresh() {
        _refreshTick.value = _refreshTick.value + 1
    }

    fun requestKpmDisable() {
        _kpmDisableTick.value = _kpmDisableTick.value + 1
        requestRefresh()
    }

    fun requestKpmEnable() {
        _kpmEnableTick.value = _kpmEnableTick.value + 1
        requestRefresh()
    }
}
