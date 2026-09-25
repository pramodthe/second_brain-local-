package com.secondbrain.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.secondbrain.app.R
import com.secondbrain.app.data.ActionStatus
import com.secondbrain.app.data.BrainStore
import com.secondbrain.app.ui.MainActivity
import kotlinx.coroutines.CancellationException

class ActionReminderWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val actionId = inputData.getString(KEY_ACTION_ID) ?: return Result.failure()
        val scheduledDue = inputData.getDouble(KEY_DUE_TIMESTAMP, Double.NaN)
        if (scheduledDue.isNaN()) return Result.failure()

        val store = BrainStore(applicationContext)
        return try {
            store.open().getOrThrow()
            val action = store.getActionItem(actionId).getOrThrow() ?: return Result.success()
            if (
                action.status != ActionStatus.OPEN ||
                action.dueTimestamp != scheduledDue ||
                store.wasActionReminderDelivered(actionId, scheduledDue).getOrThrow()
            ) {
                return Result.success()
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return Result.success()
            }

            createChannel()
            val openApp = PendingIntent.getActivity(
                applicationContext,
                actionId.hashCode(),
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_OPEN_ACTIONS, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val publicNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Second Brain")
                .setContentText("You have an action due")
                .build()
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Action due")
                .setContentText(action.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(action.text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotification)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(actionId.hashCode(), notification)
            store.markActionReminderDelivered(actionId, scheduledDue).getOrThrow()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        } finally {
            store.close()
        }
    }

    private fun createChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Action reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Private reminders for actions stored in Second Brain"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    companion object {
        const val KEY_ACTION_ID = "action_id"
        const val KEY_DUE_TIMESTAMP = "due_timestamp"
        private const val CHANNEL_ID = "second_brain_action_reminders"
    }
}
