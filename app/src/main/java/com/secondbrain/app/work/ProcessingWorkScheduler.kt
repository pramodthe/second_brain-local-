package com.secondbrain.app.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object ProcessingWorkScheduler {
    private const val QUEUE_NAME = "second-brain-processing-queue"
    const val WORK_TAG = "second-brain-processing"

    /**
     * Appends a lightweight queue drain. Multiple kicks are safe: persisted job IDs provide
     * deduplication and the unique WorkManager chain serializes all heavy on-device inference.
     */
    fun kick(context: Context) {
        val request = OneTimeWorkRequestBuilder<BrainProcessingWorker>()
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .beginUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .enqueue()
    }
}
