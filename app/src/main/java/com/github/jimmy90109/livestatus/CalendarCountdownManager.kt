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

        countdownJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val remainingMillis = target.timeInMillis - System.currentTimeMillis()

                if (remainingMillis <= 0) {
                    cancelCapsuleNotification(appContext)
                    break
                }

                val minutesLeft = (remainingMillis / 60000).toInt()
                val pillText = if (minutesLeft > 0) "in ${minutesLeft}m" else "Now"

                LiveStatusReminder.showCustomCapsule(
                    context = appContext,
                    pillText = pillText,
                    iconName = "ic_capsule_calendar",
                    title = eventTitle,
                    content = "Starts in $minutesLeft min",
                    timeoutSeconds = 60
                )

                delay(30_000L)
            }
        }
    }

    fun stop(context: Context) {
        countdownJob?.cancel()
        countdownJob = null
        cancelCapsuleNotification(context.applicationContext)
    }
}
