package com.secondbrain.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingJobTest {

    @Test
    fun stableIdDeduplicatesOneJobTypePerNote() {
        assertEquals(
            "transcribe-note-123",
            ProcessingJob.stableId("note-123", ProcessingJobType.TRANSCRIBE)
        )
        val organizeId = ProcessingJob.stableId("note-123", ProcessingJobType.ORGANIZE)
        assertEquals("organize-note-123", organizeId)
        assertNotEquals("transcribe-note-123", organizeId)
    }

    @Test
    fun onlyQueuedAndRunningStatusesAreActive() {
        assertTrue(ProcessingJobStatus.QUEUED.isActive)
        assertTrue(ProcessingJobStatus.RUNNING.isActive)
        assertFalse(ProcessingJobStatus.COMPLETED.isActive)
        assertFalse(ProcessingJobStatus.FAILED.isActive)
        assertFalse(ProcessingJobStatus.CANCELLED.isActive)
    }

    @Test
    fun failedAndCancelledJobsCanRetry() {
        assertTrue(ProcessingJobStatus.FAILED.canRetry)
        assertTrue(ProcessingJobStatus.CANCELLED.canRetry)
        assertFalse(ProcessingJobStatus.COMPLETED.canRetry)
    }
}
