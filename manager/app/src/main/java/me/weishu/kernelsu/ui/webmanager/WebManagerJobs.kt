package me.weishu.kernelsu.ui.webmanager

/**
 * Tracks the module `action.sh` executions started from the browser manager.
 *
 * The store is thread agnostic: the HTTP client threads read snapshots while the
 * command thread appends output, so every mutation happens under one lock. It is
 * deliberately Android free so the state machine can be unit tested.
 */
internal class WebManagerJobs(
    private val maxRetainedJobs: Int = 12,
    private val retentionMillis: Long = 10 * 60_000L,
    private val outputLimitChars: Int = 96 * 1024,
) {
    enum class State {
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
    }

    data class Snapshot(
        val id: String,
        val moduleId: String,
        val state: State,
        val exitCode: Int?,
        val output: String,
        val truncated: Boolean,
        val startedAtMillis: Long,
        val finishedAtMillis: Long?,
    ) {
        val running: Boolean get() = state == State.RUNNING
    }

    private data class Job(
        val id: String,
        val moduleId: String,
        val startedAtMillis: Long,
        var finishedAtMillis: Long? = null,
        var state: State = State.RUNNING,
        var exitCode: Int? = null,
        val output: StringBuilder = StringBuilder(),
        var truncated: Boolean = false,
    )

    private val lock = Any()
    private val jobs = LinkedHashMap<String, Job>()
    private var sequence = 0L

    /**
     * Registers a new job for [moduleId]. Returns null when that module already
     * has a running job, which keeps a single action script per module in flight.
     */
    fun start(moduleId: String, nowMillis: Long): String? = synchronized(lock) {
        pruneLocked(nowMillis)
        if (jobs.values.any { it.moduleId == moduleId && it.state == State.RUNNING }) return null
        while (jobs.size >= maxRetainedJobs) {
            val oldest = jobs.values.firstOrNull { it.state != State.RUNNING } ?: break
            jobs.remove(oldest.id)
        }
        if (jobs.size >= maxRetainedJobs) return null
        sequence += 1
        val id = "job-" + nowMillis.toString(36) + "-" + sequence.toString(36)
        jobs[id] = Job(id = id, moduleId = moduleId, startedAtMillis = nowMillis)
        id
    }

    fun append(jobId: String, chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            val job = jobs[jobId] ?: return
            if (job.state != State.RUNNING) return
            val remaining = outputLimitChars - job.output.length
            when {
                remaining <= 0 -> job.truncated = true
                chunk.length <= remaining -> job.output.append(chunk)
                else -> {
                    job.output.append(chunk, 0, remaining)
                    job.truncated = true
                }
            }
        }
    }

    fun finish(jobId: String, exitCode: Int, cancelled: Boolean, nowMillis: Long) {
        synchronized(lock) {
            val job = jobs[jobId] ?: return
            if (job.state != State.RUNNING) return
            job.finishedAtMillis = nowMillis
            job.exitCode = exitCode
            job.state = when {
                cancelled -> State.CANCELLED
                exitCode == 0 -> State.SUCCEEDED
                else -> State.FAILED
            }
        }
    }

    /** Marks the job cancelled before the shell is actually closed. */
    fun cancel(jobId: String, nowMillis: Long): Boolean = synchronized(lock) {
        val job = jobs[jobId] ?: return false
        if (job.state != State.RUNNING) return false
        job.state = State.CANCELLED
        job.exitCode = CANCELLED_EXIT_CODE
        job.finishedAtMillis = nowMillis
        true
    }

    fun isRunning(jobId: String): Boolean = synchronized(lock) {
        jobs[jobId]?.state == State.RUNNING
    }

    fun hasRunningJob(): Boolean = synchronized(lock) {
        jobs.values.any { it.state == State.RUNNING }
    }

    fun runningJobId(moduleId: String): String? = synchronized(lock) {
        jobs.values.firstOrNull { it.moduleId == moduleId && it.state == State.RUNNING }?.id
    }

    /** Reads the job output starting at [offset] characters into the buffer. */
    fun snapshot(jobId: String, offset: Int = 0): Snapshot? = synchronized(lock) {
        val job = jobs[jobId] ?: return null
        val start = offset.coerceIn(0, job.output.length)
        Snapshot(
            id = job.id,
            moduleId = job.moduleId,
            state = job.state,
            exitCode = job.exitCode,
            output = job.output.substring(start),
            truncated = job.truncated,
            startedAtMillis = job.startedAtMillis,
            finishedAtMillis = job.finishedAtMillis,
        )
    }

    /** Total characters currently buffered, used as the next poll offset. */
    fun producedLength(jobId: String): Int = synchronized(lock) {
        jobs[jobId]?.output?.length ?: 0
    }

    fun prune(nowMillis: Long) = synchronized(lock) { pruneLocked(nowMillis) }

    private fun pruneLocked(nowMillis: Long) {
        val expired = jobs.values.filter { job ->
            job.state != State.RUNNING &&
                job.finishedAtMillis != null &&
                nowMillis - job.finishedAtMillis!! > retentionMillis
        }
        expired.forEach { jobs.remove(it.id) }
    }

    companion object {
        const val CANCELLED_EXIT_CODE = 130
    }
}
