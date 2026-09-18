package me.weishu.kernelsu.ui.webmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebManagerJobsTest {
    @Test
    fun startRejectsConcurrentJobsPerModule() {
        val jobs = WebManagerJobs()
        val first = jobs.start("example.module", nowMillis = 1_000)
        assertNotNull(first)
        assertNull(jobs.start("example.module", nowMillis = 1_100))
        assertNotNull(jobs.start("other.module", nowMillis = 1_200))
        assertEquals(first, jobs.runningJobId("example.module"))
    }

    @Test
    fun outputAccumulatesAndSnapshotOffsetsWork() {
        val jobs = WebManagerJobs()
        val id = jobs.start("example.module", nowMillis = 1_000)!!
        jobs.append(id, "line one\n")
        jobs.append(id, "line two\n")

        val first = jobs.snapshot(id)!!
        assertEquals("line one\nline two\n", first.output)
        assertEquals(WebManagerJobs.State.RUNNING, first.state)
        assertTrue(first.running)
        assertEquals(18, jobs.producedLength(id))

        val tail = jobs.snapshot(id, offset = jobs.producedLength(id))!!
        assertEquals("", tail.output)

        val partial = jobs.snapshot(id, offset = 9)!!
        assertEquals("line two\n", partial.output)
    }

    @Test
    fun finishMarksSuccessAndFailure() {
        val jobs = WebManagerJobs()
        val ok = jobs.start("ok.module", nowMillis = 10)!!
        jobs.finish(ok, exitCode = 0, cancelled = false, nowMillis = 20)
        val okSnapshot = jobs.snapshot(ok)!!
        assertEquals(WebManagerJobs.State.SUCCEEDED, okSnapshot.state)
        assertEquals(0, okSnapshot.exitCode)
        assertFalse(okSnapshot.running)
        assertEquals(20L, okSnapshot.finishedAtMillis)

        val bad = jobs.start("bad.module", nowMillis = 30)!!
        jobs.finish(bad, exitCode = 1, cancelled = false, nowMillis = 40)
        assertEquals(WebManagerJobs.State.FAILED, jobs.snapshot(bad)!!.state)
    }

    @Test
    fun cancelStopsRunningJobOnlyOnce() {
        val jobs = WebManagerJobs()
        val id = jobs.start("example.module", nowMillis = 1_000)!!
        assertTrue(jobs.cancel(id, nowMillis = 1_500))
        val snapshot = jobs.snapshot(id)!!
        assertEquals(WebManagerJobs.State.CANCELLED, snapshot.state)
        assertEquals(WebManagerJobs.CANCELLED_EXIT_CODE, snapshot.exitCode)
        assertFalse(jobs.isRunning(id))
        assertFalse(jobs.cancel(id, nowMillis = 1_600))

        jobs.append(id, "late output")
        assertEquals("", jobs.snapshot(id)!!.output)
    }

    @Test
    fun cancelledJobAllowsNewRunForSameModule() {
        val jobs = WebManagerJobs()
        val first = jobs.start("example.module", nowMillis = 1_000)!!
        jobs.cancel(first, nowMillis = 1_100)
        assertNotNull(jobs.start("example.module", nowMillis = 1_200))
    }

    @Test
    fun outputIsBoundedAndFlagged() {
        val jobs = WebManagerJobs(outputLimitChars = 10)
        val id = jobs.start("example.module", nowMillis = 1_000)!!
        jobs.append(id, "0123456789abcdef")
        val snapshot = jobs.snapshot(id)!!
        assertEquals("0123456789", snapshot.output)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun expiredJobsArePrunedWhenNewOnesStart() {
        val jobs = WebManagerJobs(retentionMillis = 100)
        val old = jobs.start("old.module", nowMillis = 1_000)!!
        jobs.finish(old, exitCode = 0, cancelled = false, nowMillis = 1_000)
        assertNotNull(jobs.start("new.module", nowMillis = 1_500))
        assertNull(jobs.snapshot(old))
    }

    @Test
    fun retainedJobsAreCapped() {
        val jobs = WebManagerJobs(maxRetainedJobs = 2)
        val first = jobs.start("one.module", nowMillis = 1_000)!!
        jobs.finish(first, exitCode = 0, cancelled = false, nowMillis = 1_000)
        val second = jobs.start("two.module", nowMillis = 1_100)!!
        jobs.finish(second, exitCode = 0, cancelled = false, nowMillis = 1_100)
        val third = jobs.start("three.module", nowMillis = 1_200)!!
        assertNotNull(jobs.snapshot(third))
        assertNull(jobs.snapshot(first))
    }

    @Test
    fun hasRunningJobReflectsActiveWork() {
        val jobs = WebManagerJobs()
        assertFalse(jobs.hasRunningJob())
        val id = jobs.start("example.module", nowMillis = 1_000)!!
        assertTrue(jobs.hasRunningJob())
        jobs.finish(id, exitCode = 0, cancelled = false, nowMillis = 1_100)
        assertFalse(jobs.hasRunningJob())
    }

    @Test
    fun unknownJobIdsAreIgnored() {
        val jobs = WebManagerJobs()
        assertNull(jobs.snapshot("missing"))
        assertFalse(jobs.cancel("missing", nowMillis = 1))
        assertFalse(jobs.isRunning("missing"))
        jobs.append("missing", "text")
        jobs.finish("missing", exitCode = 0, cancelled = false, nowMillis = 1)
    }
}
