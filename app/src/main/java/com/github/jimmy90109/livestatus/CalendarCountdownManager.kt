package com.github.jimmy90109.livestatus

import android.app.NotificationManager
import android.content.Context
import java.util.Calendar

object CalendarCountdownManager {

    const val CALENDAR_NOTIFICATION_ID = 9002

    fun startCountdown(
        context: Context,
        eventTitle: String,
        targetHour: Int,
        targetMinute: Int
    ) {
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

        val remainingMillis = target.timeInMillis - now.timeInMillis
        val minutesLeft = ((remainingMillis + 59_999L) / 60_000L).toInt()
        val pillText = if (minutesLeft > 1) "in ${minutesLeft}m" else "Now"

        LiveStatusReminder.showCustomCapsule(
            context = context.applicationContext,
            pillText = pillText,
            iconName = "ic_capsule_calendar",
            title = eventTitle,
            content = "Starts in $minutesLeft min",
            timeoutSeconds = (remainingMillis / 1000L).toInt().coerceAtLeast(1),
            notificationId = CALENDAR_NOTIFICATION_ID,
            chronometerTargetMillis = target.timeInMillis
        )
    }

    fun stop(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(CALENDAR_NOTIFICATION_ID)
    }
}
