package com.github.jimmy90109.livestatus

import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.*
import java.util.Calendar

object CalendarCountdownManager {

    private const val CAPSULE_NOTIFICATION_ID = 9002
    private var countdownJob: Job? = null

    private fun cancelCapsuleNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(CAPSULE_NOTIFICATION_ID)
    }

    fun startCountdown(
        context: Context,
        eventTitle: String,
        targetHour: Int,
        targetMinute: Int
    ) {
        countdownJob?.cancel()

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val appContext = context.applicationContext

        // Run on background Default dispatcher so OS does not throttle Main
        countdownJob = CoroutineScope(Dispatchers.Default).launch {
            var lastReportedMinutes = -1

            while (isActive) {
                val remainingMillis = target.timeInMillis - System.currentTimeMillis()

                if (remainingMillis <= 0) {
                    cancelCapsuleNotification(appContext)
                    break
                }

                val minutesLeft = ((remainingMillis + 59_999L) / 60_000L).toInt()

                // Only post updates when minute rolls over or on start
                if (minutesLeft != lastReportedMinutes) {
                    lastReportedMinutes = minutesLeft
                    val pillText = if (minutesLeft > 1) "in ${minutesLeft}m" else "in 1m"

                    withContext(Dispatchers.Main) {
                        LiveStatusReminder.showCustomCapsule(
                            context = appContext,
                            pillText = pillText,
                            iconName = "ic_capsule_search",
                            title = eventTitle,
                            content = "Starts in $minutesLeft min",
                            timeoutSeconds = 0
                        )
                    }
                }

                // Ticks every 10 seconds to detect the exact minute boundary promptly
                delay(10_000L)
            }
        }
    }

    fun stop(context: Context) {
        countdownJob?.cancel()
        countdownJob = null
        cancelCapsuleNotification(context.applicationContext)
    }
}
