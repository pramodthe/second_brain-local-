package com.secondbrain.app.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.secondbrain.app.data.ActionItem
import com.secondbrain.app.data.ActionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object ActionReminderScheduler {
    private const val WORK_PREFIX = "second-brain-action-reminder-"
    private val reminderTime = LocalTime.of(9, 0)

    fun sync(context: Context, actions: List<ActionItem>) {
        actions.forEach { schedule(context, it) }
    }

    fun schedule(context: Context, action: ActionItem) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val workName = WORK_PREFIX + action.id
        val dueTimestamp = action.dueTimestamp
        if (action.status != ActionStatus.OPEN || dueTimestamp == null) {
            workManager.cancelUniqueWork(workName)
            return
        }

        val zone = ZoneId.systemDefault()
        val dueDate = Instant.ofEpochSecond(dueTimestamp.toLong()).atZone(zone).toLocalDate()
        if (dueDate.isBefore(LocalDate.now(zone))) {
            workManager.cancelUniqueWork(workName)
            return
        }
        val triggerMillis = dueDate.atTime(reminderTime).atZone(zone).toInstant().toEpochMilli()
        val delayMillis = (triggerMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ActionReminderWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ActionReminderWorker.KEY_ACTION_ID, action.id)
                    .putDouble(ActionReminderWorker.KEY_DUE_TIMESTAMP, dueTimestamp)
                    .build()
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(WORK_PREFIX)
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }
}
