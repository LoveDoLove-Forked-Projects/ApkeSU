package me.weishu.kernelsu.ui.webmanager

/**
 * Bounded, thread safe log of what the web manager server actually did.
 *
 * The server runs inside the manager process where no logcat is reachable from
 * a browser, so the console page exposes this buffer through `/api/diagnostics`
 * and the Settings tab renders it. Every root shell, query deadline and endpoint
 * failure writes here, which turns "it does not work on my phone" into a
 * concrete reason the user can copy back.
 */
internal class WebManagerDiagnostics(private val maxEntries: Int = 60) {
    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val atMillis: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    fun info(tag: String, message: String) = append(Level.INFO, tag, message)

    fun warn(tag: String, message: String) = append(Level.WARN, tag, message)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        append(Level.ERROR, tag, throwable?.let { "$message: ${it.javaClass.simpleName}(${it.message})" } ?: message)

    private fun append(level: Level, tag: String, message: String) {
        val entry = Entry(
            atMillis = System.currentTimeMillis(),
            level = level,
            tag = tag.take(24),
            message = message.replace('\n', ' ').take(240),
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > maxEntries) entries.removeFirst()
        }
    }

    fun snapshot(limit: Int = maxEntries): List<Entry> = synchronized(lock) {
        entries.toList().takeLast(limit)
    }

    fun clear() = synchronized(lock) { entries.clear() }
}
