package me.weishu.kernelsu.ui.webmanager

/**
 * Reboot modes exposed to the loopback Web Manager.
 *
 * The browser only sends [wireValue]. The shell reason is selected here from a
 * closed list, so request data can never become part of a shell command.
 */
internal enum class WebManagerRebootMode(
    val wireValue: String,
    val nativeReason: String,
) {
    SYSTEM("system", ""),
    USERSPACE("userspace", "userspace"),
    SOFT("soft", "soft_reboot"),
    RECOVERY("recovery", "recovery"),
    BOOTLOADER("bootloader", "bootloader"),
    DOWNLOAD("download", "download"),
    EDL("edl", "edl"),
    ;

    val usesKsudSoftReboot: Boolean
        get() = this == SOFT

    companion object {
        fun parse(value: String?): WebManagerRebootMode? {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}
