package com.github.jimmy90109.livestatus

import android.Manifest
import android.app.Notification
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.content.ContextCompat

class LiveStatusNotificationListenerService : NotificationListenerService() {
    private val clockTimerTracker = ClockTimerTracker()
    private val yptStudyTracker = YptStudyTracker()
    private val hevyWorkoutTracker = HevyWorkoutTracker()
    private val stravaRecordingTracker = StravaRecordingTracker()
    private val citymapperTracker = CitymapperNavigationTracker()
    private val citymapperPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppReminderPreferences.App.CITYMAPPER.preferenceKey) {
            if (AppReminderPreferences.App.CITYMAPPER.isEnabled(this)) {
                restoreCitymapperNavigation(runCatching { activeNotifications }.getOrNull().orEmpty())
            } else {
                handleCitymapperDecision(citymapperTracker.reset())
            }
        }
    }
    private val discordVoiceTracker = DiscordVoiceTracker()
    private val teamsCallTracker = TeamsCallTracker()
    private val recorderTracker = RecorderTracker()
    private val clockTimerHandler = Handler(Looper.getMainLooper())
    private var activeClockTimerUpdate: ClockTimerUpdate? = null
    private val clockTimerRefreshRunnable = Runnable(::refreshClockTimer)
    private val mediaPlaybackMonitor by lazy {
        MediaPlaybackMonitor(
            context = this,
            listenerComponent = ComponentName(this, LiveStatusNotificationListenerService::class.java),
            handler = clockTimerHandler,
            onUpdate = { update ->
                if (update == null) {
                    LiveStatusReminder.clearMediaPlayback(this)
                } else {
                    LiveStatusReminder.showMediaPlayback(this, update)
                }
            },
        )
    }
    
    private var lastMediaTrack: String? = null
    private var lastRingerMode: Int? = null
    private var wasVpnConnected = false
    private val mediaSessionManager by lazy {
        getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.RINGER_MODE_CHANGED_ACTION) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val currentMode = audioManager.ringerMode

                if (lastRingerMode == null) {
                    lastRingerMode = currentMode
                    return
                }

                if (lastRingerMode == currentMode) return
                lastRingerMode = currentMode

                val (modeName, iconName) = when (currentMode) {
                    AudioManager.RINGER_MODE_SILENT -> "Silent" to "ic_volume_off"
                    AudioManager.RINGER_MODE_VIBRATE -> "Vibrate" to "ic_vibration"
                    AudioManager.RINGER_MODE_NORMAL -> "Ring" to "ic_volume_up"
                    else -> return
                }

                LiveStatusReminder.showCustomCapsule(
                    context = context,
                    pillText = modeName,
                    iconName = iconName,
                    title = "Sound Mode",
                    content = "Switched to $modeName",
                    timeoutSeconds = 3,
                    notificationId = 9007,
                    customContentIntent = android.app.PendingIntent.getActivity(
                        context,
                        9007,
                        Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            } catch (_: Exception) {
                null
            } ?: return

            val hasBtPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

            val deviceName = if (hasBtPermission) {
                try {
                    device.name ?: "Bluetooth Device"
                } catch (_: SecurityException) {
                    "Bluetooth Device"
                }
            } else {
                "Bluetooth Device"
            }

            val btSettingsIntent = android.app.PendingIntent.getActivity(
                context,
                9008,
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    LiveStatusReminder.showCustomCapsule(
                        context = context,
                        pillText = deviceName,
                        iconName = "ic_bluetooth",
                        title = "Connected",
                        content = deviceName,
                        timeoutSeconds = 4,
                        notificationId = 9008,
                        customContentIntent = btSettingsIntent
                    )
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    LiveStatusReminder.showCustomCapsule(
                        context = context,
                        pillText = "Offline",
                        iconName = "ic_bluetooth",
                        title = "Disconnected",
                        content = deviceName,
                        timeoutSeconds = 3,
                        notificationId = 9008,
                        customContentIntent = btSettingsIntent
                    )
                }
            }
        }
    }

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)
            val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

            if (isVpn && !wasVpnConnected) {
                wasVpnConnected = true
                LiveStatusReminder.showCustomCapsule(
                    context = context,
                    pillText = "VPN On",
                    iconName = "ic_vpn_key",
                    title = "VPN Connected",
                    content = "Secure network tunnel active",
                    timeoutSeconds = 4,
                    notificationId = 9009
                )
            } else if (!isVpn && wasVpnConnected) {
                wasVpnConnected = false
                LiveStatusReminder.showCustomCapsule(
                    context = context,
                    pillText = "VPN Off",
                    iconName = "ic_vpn_key",
                    title = "VPN Disconnected",
                    content = "Network tunnel closed",
                    timeoutSeconds = 3,
                    notificationId = 9009
                )
            }
        }
    }

    private var lastUberRideUpdate =
        LiveStatusNotificationParser.UberRideUpdate(LiveStatusNotificationParser.UberRideEvent.NONE)
    private val uberEatsTracker = UberEatsTracker()
    
    override fun onCreate() {
        super.onCreate()
        CustomRuleEngine.loadRules(this)

        val exportFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }

        try {
            registerReceiver(ringerReceiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), exportFlag)
        } catch (e: Exception) {
            Log.e("LiveStatus", "Failed ringer registration", e)
        }

        try {
            val btFilter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            registerReceiver(bluetoothReceiver, btFilter, exportFlag)
        } catch (e: Exception) {
            Log.e("LiveStatus", "Failed bluetooth registration", e)
        }

        try {
            registerReceiver(vpnReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), exportFlag)
        } catch (e: Exception) {
            Log.e("LiveStatus", "Failed vpn registration", e)
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.addPrimaryClipChangedListener {
                try {
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
                        if (isContextuallyInspectable(text)) {
                            LiveStatusReminder.showCustomCapsule(
                                context = this,
                                pillText = "Inspect",
                                iconName = "ic_capsule_search",
                                title = "Clipboard Insight",
                                content = text,
                                timeoutSeconds = 4,
                                notificationId = 9010
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        AppReminderPreferences.registerListener(this, citymapperPreferenceListener)
    }

    private fun isContextuallyInspectable(text: String): Boolean {
        if (text.isBlank() || text.length > 300) return false
        val mathPattern = Regex("""^[\d\s\+\-\*\/\^\(\)\.=\%]+$""")
        val currencyPattern = Regex("""(?i)(?:[\$€£₹¥]|USD|INR|EUR|GBP)\s*[\d,]+(?:\.\d+)?|[\d,]+(?:\.\d+)?\s*(?:USD|INR|EUR|GBP)""")
        val hexColorPattern = Regex("""^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""")
        val timezonePattern = Regex("""(?i)\b\d{1,2}(?::\d{2})?\s*(?:am|pm)?\s*(?:est|edt|pst|pdt|cst|cdt|gmt|utc|ist|jst)\b""")
        return text.matches(hexColorPattern) ||
               (text.length <= 40 && mathPattern.matches(text) && text.any { it.isDigit() } && text.any { "+-*/^%".contains(it) }) ||
               currencyPattern.containsMatchIn(text) ||
               timezonePattern.containsMatchIn(text)
    }

    private fun cleanSongTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?|with|remastered|bonus|deluxe|prod\.?|official).*?[\)\]]"""), "")
            .trim()
    }

    private fun isMediaActivelyPlaying(packageName: String): Boolean {
        return try {
            val controllers = mediaSessionManager?.getActiveSessions(
                ComponentName(this, LiveStatusNotificationListenerService::class.java)
            ) ?: emptyList()
            val match = controllers.firstOrNull { it.packageName == packageName } ?: controllers.firstOrNull()
            match?.playbackState?.state == PlaybackState.STATE_PLAYING
        } catch (_: SecurityException) {
            true
        } catch (_: Exception) {
            true
        }
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification?) {
        super.onNotificationPosted(statusBarNotification)
        if (statusBarNotification == null) return

        mediaPlaybackMonitor.onNotificationPosted(statusBarNotification)

        val notification = statusBarNotification.notification ?: return
        val packageName = statusBarNotification.packageName ?: ""

        val rawNotificationText = readNotificationText(this, packageName, notification)
        val notificationText = rawNotificationText?.toString() ?: ""

        val rawTitle = readNotificationTitle(notification)
        val title = rawTitle?.toString() ?: ""

        val rawContentText = readNotificationContentText(notification)
        val contentText = rawContentText?.toString() ?: ""

        val subText = notification.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val bigText = notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val lines = notification.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" ") { it?.toString() ?: "" } ?: ""

        val fullText = listOf(title, contentText, subText, bigText, lines, notificationText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")

        try {
            // 1. Proton VPN Specific Extraction
            if (packageName == "ch.protonvpn.android") {
                val serverRegex = Regex("""[A-Z]{2}(?:-[A-Z]+)?#\d+""")
                val matchedServer = serverRegex.find(fullText)?.value
                if (matchedServer != null) {
                    wasVpnConnected = true
                    LiveStatusReminder.showCustomCapsule(
                        context = this,
                        pillText = matchedServer,
                        iconName = "ic_vpn_key",
                        title = "Proton VPN",
                        content = "Connected: $matchedServer",
                        timeoutSeconds = 4,
                        notificationId = 9009,
                        targetPackage = packageName
                    )
                    return
                }
            }

            // 2. Ambient Music Mod (Now Playing) Hook
            if (packageName == "com.kieronquinn.app.ambientmusicmod") {
                if (title.isNotBlank()) {
                    val cleanTrack = cleanSongTitle(title)
                    LiveStatusReminder.showCustomCapsule(
                        context = this,
                        pillText = cleanTrack,
                        iconName = "ic_music_notification",
                        title = cleanTrack,
                        content = if (contentText.isNotBlank()) "by $contentText" else "Now Playing",
                        timeoutSeconds = 6,
                        notificationId = 9011,
                        targetPackage = packageName
                    )
                    return
                }
            }

            // 3. Download & Tasks Progress Hook
            val maxProgress = notification.extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0
            val currentProgress = notification.extras?.getInt(Notification.EXTRA_PROGRESS, 0) ?: 0
            val isIndeterminate = notification.extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ?: false

            if (maxProgress > 0 && !isIndeterminate) {
                val percent = ((currentProgress.toDouble() / maxProgress) * 100).toInt().coerceIn(0, 100)
                val appLabel = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                } catch (_: Exception) {
                    packageName
                }

                val fileDetail = when {
                    contentText.isNotBlank() && !contentText.matches(Regex("""^\d+[\s/]+\d+$""")) -> contentText
                    bigText.isNotBlank() -> bigText
                    title.isNotBlank() && title != appLabel -> title
                    else -> "$currentProgress / $maxProgress"
                }

                LiveStatusReminder.showCustomCapsule(
                    context = this,
                    pillText = "$percent%",
                    iconName = "ic_capsule_download",
                    title = "$appLabel ($percent%)",
                    content = fileDetail,
                    timeoutSeconds = 0,
                    notificationId = 9005,
                    targetPackage = packageName
                )
                return
            }

            // 4. Track Change & Spotify Ad Hook
            val isMedia = notification.category == Notification.CATEGORY_TRANSPORT ||
                          notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
            if (isMedia && title.isNotBlank()) {
                val trackIdentifier = "$title - $contentText"
                if (trackIdentifier != lastMediaTrack && isMediaActivelyPlaying(packageName)) {
                    lastMediaTrack = trackIdentifier

                    val isSpotifyAd = packageName == "com.spotify.music" && (
                        title.equals("Advertisement", ignoreCase = true) ||
                        title.equals("Spotify", ignoreCase = true) ||
                        contentText.contains("Advertisement", ignoreCase = true) ||
                        contentText.equals("Ad", ignoreCase = true)
                    )

                    val cleanTitle = cleanSongTitle(title)
                    val pillDisplay = if (isSpotifyAd) "Ad" else cleanTitle

                    LiveStatusReminder.showCustomCapsule(
                        context = this,
                        pillText = pillDisplay,
                        iconName = "ic_music_notification",
                        title = if (isSpotifyAd) "Spotify" else title,
                        content = if (isSpotifyAd) "Advertisement" else (if (contentText.isNotBlank()) contentText else "Now playing"),
                        timeoutSeconds = 5,
                        notificationId = 9006,
                        targetPackage = packageName
                    )
                }
            }

            // 5. Calendar Interceptor
            if (packageName == "com.google.android.calendar" || packageName == "com.oplus.calendar") {
                val timeRegex = Regex("(\\d{1,2}):(\\d{2})")
                val match = timeRegex.find(fullText)

                if (match != null) {
                    val hour = match.groupValues[1].toInt()
                    val minute = match.groupValues[2].toInt()

                    CalendarCountdownManager.startCountdown(
                        context = this,
                        eventTitle = if (title.isNotBlank()) title else "Calendar Event",
                        targetHour = hour,
                        targetMinute = minute
                    )
                    return
                }
            }

            // 6. Custom Rules Engine
            val titleMatch = if (title.isNotBlank()) {
                CustomRuleEngine.evaluate(packageName, title)
            } else null

            val customMatch = titleMatch ?: CustomRuleEngine.evaluate(packageName, fullText)

            if (customMatch != null) {
                LiveStatusReminder.showCustomCapsule(
                    context = this,
                    pillText = customMatch.pillText,
                    iconName = customMatch.iconName,
                    title = if (title.isNotBlank()) title else "Live Update",
                    content = if (contentText.isNotBlank()) contentText else fullText,
                    timeoutSeconds = customMatch.timeoutSeconds,
                    notificationId = 9004,
                    targetPackage = packageName
                )
                return
            }
        } catch (e: Exception) {
            Log.e("LiveStatus", "Error processing notification hooks", e)
        }

        // --- Jimmy's original downstream parser checks resume here ---
        
        when (statusBarNotification.packageName) {
            CITYMAPPER_PACKAGE -> {
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordCitymapper(
                        this,
                        statusBarNotification,
                        notificationText,
                        readNotificationTitle(notification),
                        readNotificationContentText(notification),
                        "POSTED",
                    )
                }
                if (AppReminderPreferences.App.CITYMAPPER.isEnabled(this)) {
                    handleCitymapperDecision(citymapperTracker.onPosted(
                        statusBarNotification.key,
                        CitymapperNotificationExtractor.extract(this, statusBarNotification),
                    ))
                } else {
                    handleCitymapperDecision(citymapperTracker.reset())
                }
            }
            BOLT_PACKAGE -> if (BuildConfig.DEBUG) {
                NotificationDebugPayloadStore.recordBolt(
                    this,
                    statusBarNotification,
                    notificationText,
                    readNotificationTitle(notification),
                    readNotificationContentText(notification),
                    "POSTED",
                )
            }
            STRAVA_PACKAGE -> {
                val notificationTitle = readNotificationTitle(notification)
                val notificationContentText = readNotificationContentText(notification)
                val update = parseStravaRecording(
                    statusBarNotification,
                    notificationTitle,
                    notificationContentText,
                )
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordStrava(
                        this,
                        statusBarNotification,
                        notificationText,
                        notificationTitle,
                        notificationContentText,
                        "POSTED",
                        update,
                    )
                }
                if (AppReminderPreferences.App.STRAVA.isEnabled(this)) {
                    handleStravaRecordingDecision(
                        stravaRecordingTracker.onPosted(statusBarNotification.key, update),
                    )
                } else {
                    stravaRecordingTracker.reset()
                    LiveStatusReminder.clearStravaRecording(this)
                }
            }
            TEAMS_PACKAGE -> {
                val extraction = TeamsCallNotificationExtractor.extract(statusBarNotification)
                if (BuildConfig.DEBUG) {
                    recordTeams(statusBarNotification, "POSTED", notificationText, extraction)
                }
                if (AppReminderPreferences.App.TEAMS_CALL.isEnabled(this)) {
                    handleTeamsCallDecision(
                        teamsCallTracker.onPosted(statusBarNotification.key, extraction.update),
                    )
                } else {
                    teamsCallTracker.reset()
                    LiveStatusReminder.clearTeamsCall(this)
                }
            }
            GOOGLE_RECORDER_PACKAGE -> {
                val extraction = GoogleRecorderNotificationExtractor.extract(
                    statusBarNotification,
                    notificationText,
                )
                if (BuildConfig.DEBUG) {
                    recordGoogleRecorder(statusBarNotification, "POSTED", extraction)
                }
                if (AppReminderPreferences.App.GOOGLE_RECORDER.isEnabled(this)) {
                    handleRecorderDecision(
                        recorderTracker.onPosted(statusBarNotification.key, extraction),
                    )
                } else {
                    recorderTracker.reset()
                    LiveStatusReminder.clearGoogleRecorder(this)
                }
            }
            DISCORD_PACKAGE -> {
                val extraction = DiscordVoiceNotificationExtractor.extract(statusBarNotification)
                if (BuildConfig.DEBUG) {
                    recordDiscord(statusBarNotification, "POSTED", extraction)
                }
                if (AppReminderPreferences.App.DISCORD_VOICE.isEnabled(this)) {
                    handleDiscordVoiceDecision(
                        discordVoiceTracker.onPosted(statusBarNotification.key, extraction.update),
                    )
                } else {
                    discordVoiceTracker.reset()
                    LiveStatusReminder.clearDiscordVoice(this)
                }
            }
            CLOCK_PACKAGE -> {
                val extraction = ClockTimerNotificationExtractor.extract(this, statusBarNotification)
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordClock(
                        this,
                        statusBarNotification,
                        notificationText,
                        readNotificationTitle(notification),
                        readNotificationContentText(notification),
                        extraction,
                    )
                }
                if (AppReminderPreferences.App.CLOCK.isEnabled(this)) {
                    handleClockTimerDecision(
                        clockTimerTracker.onPosted(statusBarNotification.key, extraction.update),
                    )
                } else {
                    clockTimerTracker.reset()
                    stopClockTimerRefresh()
                    LiveStatusReminder.clearClockTimer(this)
                }
            }
            IPASS_PACKAGE -> if (AppReminderPreferences.App.IPASS.isEnabled(this)) {
                handleRideNotification(notificationText)
            }
            TAIWAN_PAY_PACKAGE -> {
                val notificationTitle = readNotificationTitle(notification)
                val notificationContentText = readNotificationContentText(notification)
                val update = LiveStatusNotificationParser.parseTaiwanPay(
                    notificationTitle,
                    notificationContentText,
                    notificationText,
                )
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordTaiwanPay(
                        this,
                        statusBarNotification,
                        notificationText,
                        notificationTitle,
                        notificationContentText,
                        "POSTED",
                        update,
                    )
                }
                if (AppReminderPreferences.App.TAIWAN_PAY.isEnabled(this)) {
                    handleTaiwanPayRideNotification(update)
                }
            }
            YOU_BIKE_PACKAGE -> {
                val notificationTitle = readNotificationTitle(notification)
                val notificationContentText = readNotificationContentText(notification)
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordYouBike(
                        this,
                        statusBarNotification,
                        notificationText,
                        notificationTitle,
                        notificationContentText,
                    )
                }
                if (AppReminderPreferences.App.YOUBIKE.isEnabled(this)) {
                    val update = YouBikeNotificationParser.parse(
                        notificationContentText,
                        notificationText,
                    )
                    YouBikeRideManager.handle(this, update)
                } else {
                    YouBikeRideManager.clear(this)
                }
            }
            FOODPANDA_PACKAGE -> {
                val notificationTitle = readNotificationTitle(notification)
                val notificationContentText = readNotificationContentText(notification)
                val foodpandaText = notificationContentText.orEmpty()
                val event = LiveStatusNotificationParser.parseFoodpanda(foodpandaText)
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordFoodpanda(
                        this,
                        statusBarNotification,
                        notificationText,
                        notificationTitle,
                        notificationContentText,
                        event,
                    )
                }
                if (AppReminderPreferences.App.FOODPANDA.isEnabled(this)) {
                    handleFoodpandaNotification(event)
                }
            }
            TAIWAN_TAXI_PACKAGE -> {
                val notificationTitle = readNotificationTitle(notification)
                val notificationContentText = readNotificationContentText(notification)
                val update = LiveStatusNotificationParser.parseTaiwanTaxi(
                    notificationTitle,
                    notificationContentText,
                    notificationText,
                )
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordTaiwanTaxi(
                        this,
                        statusBarNotification,
                        notificationText,
                        notificationTitle,
                        notificationContentText,
                        update,
                    )
                }
                if (AppReminderPreferences.App.TAIWAN_TAXI.isEnabled(this)) {
                    TaiwanTaxiRideManager.handle(this, update)
                } else {
                    TaiwanTaxiRideManager.clear(this)
                }
            }
            UBER_RIDE_PACKAGE -> {
                val shortCriticalText = readShortCriticalText(notification)
                val notificationTitle = readNotificationTitle(notification)
                val notificationContentText = readNotificationContentText(notification)
                val update = LiveStatusNotificationParser.parseUberRide(
                    notificationText,
                    shortCriticalText,
                    notificationTitle,
                    notificationContentText,
                )
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordUber(
                        this,
                        statusBarNotification,
                        notificationText,
                        shortCriticalText,
                        notificationTitle,
                        notificationContentText,
                        update,
                    )
                }
                if (AppReminderPreferences.App.UBER_RIDE.isEnabled(this)) {
                    handleUberRideNotification(update)
                } else {
                    resetUberRideState()
                }
            }
            UBER_EATS_PACKAGE -> {
                val shortCriticalText = readShortCriticalText(notification)
                val notificationTitle = readNotificationTitle(notification)
                val notificationContentText = readNotificationContentText(notification)
                val update = LiveStatusNotificationParser.parseUberEats(
                    notificationText,
                    shortCriticalText,
                )
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordUberEats(
                        this,
                        statusBarNotification,
                        notificationText,
                        shortCriticalText,
                        notificationTitle,
                        notificationContentText,
                        update,
                    )
                }
                val template = notification.extras.getString(Notification.EXTRA_TEMPLATE)
                val isGroupSummary =
                    notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
                if (UberEatsNotificationSourcePolicy.supports(template, isGroupSummary)) {
                    if (AppReminderPreferences.App.UBER_EATS.isEnabled(this)) {
                        handleUberEatsDecision(
                            uberEatsTracker.onPosted(
                                sourceKey = statusBarNotification.key,
                                update = update,
                                officialTitle = notificationTitle,
                                officialText = notificationContentText ?: notificationText,
                            ),
                        )
                    } else {
                        uberEatsTracker.reset()
                        LiveStatusReminder.clearUberEats(this)
                    }
                }
            }
            PIKMIN_BLOOM_PACKAGE -> if (AppReminderPreferences.App.PIKMIN_BLOOM.isEnabled(this)) {
                handlePikminBloomNotification(notificationText)
            }
            YPT_PACKAGE -> {
                val extraction = YptStudyNotificationExtractor.extract(statusBarNotification)
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordYpt(
                        this,
                        statusBarNotification,
                        notificationText,
                        readNotificationTitle(notification),
                        readNotificationContentText(notification),
                        extraction,
                    )
                }
                if (AppReminderPreferences.App.YPT.isEnabled(this)) {
                    handleYptStudyDecision(
                        yptStudyTracker.onPosted(statusBarNotification.key, extraction.update),
                    )
                } else {
                    yptStudyTracker.reset()
                    LiveStatusReminder.clearYptStudy(this)
                }
            }
            HEVY_PACKAGE -> {
                val notificationTitle = readNotificationTitle(notification)
                val notificationContentText = readNotificationContentText(notification)
                val update = parseHevyWorkout(statusBarNotification, notificationText)
                if (BuildConfig.DEBUG) {
                    NotificationDebugPayloadStore.recordHevy(
                        this,
                        statusBarNotification,
                        notificationText,
                        notificationTitle,
                        notificationContentText,
                        "POSTED",
                        update,
                    )
                }
                if (AppReminderPreferences.App.HEVY.isEnabled(this)) {
                    handleHevyWorkoutDecision(
                        hevyWorkoutTracker.onPosted(statusBarNotification.key, update),
                    )
                } else {
                    hevyWorkoutTracker.reset()
                    LiveStatusReminder.clearHevyWorkout(this)
                }
            }

            GOOGLE_MESSAGES_PACKAGE,
            REALME_MESSAGES_PACKAGE,
            REALME_HEYTAP_PACKAGE,
            WHATSAPP_PACKAGE -> {
                val title = readNotificationTitle(notification)
                val contentText = readNotificationContentText(notification)
                val bigText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

                val messagingStyleText = if (statusBarNotification.packageName == "com.whatsapp") {
                    val messages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                    messages?.mapNotNull { msg ->
                        if (msg is android.os.Bundle) msg.getCharSequence("text")?.toString() else null
                    }?.joinToString(" ")
                } else null

                val combinedBody = listOfNotNull(contentText, bigText, notificationText, messagingStyleText)
                    .distinct()
                    .joinToString(" ")

                val otp = LiveStatusNotificationParser.parseOtp(title, combinedBody)
                if (otp?.code != null) {
                    LiveStatusReminder.showOtp(this, otp.code, otp.sender)
                }
            }
        }
    }

    private fun parseHevyWorkout(
        statusBarNotification: StatusBarNotification,
        notificationText: String,
    ): HevyWorkoutUpdate? {
        val notification = statusBarNotification.notification
        return HevyWorkoutNotificationParser.parse(
            sourceKey = statusBarNotification.key,
            channelId = notification.channelId,
            category = notification.category,
            startedAtEpochMillis = notification.`when`,
            notificationText = notificationText,
            nowEpochMillis = System.currentTimeMillis(),
            contentIntent = notification.contentIntent,
            sourceActions = notification.actions.orEmpty().toList(),
        )
    }

    private fun recordRemovedHevy(statusBarNotification: StatusBarNotification) {
        if (!BuildConfig.DEBUG) return
        val notification = statusBarNotification.notification
        val notificationText = readNotificationText(
            this,
            statusBarNotification.packageName,
            notification,
        )
        NotificationDebugPayloadStore.recordHevy(
            this,
            statusBarNotification,
            notificationText,
            readNotificationTitle(notification),
            readNotificationContentText(notification),
            "REMOVED",
            parseHevyWorkout(statusBarNotification, notificationText),
        )
    }

    private fun recordDiscord(
        statusBarNotification: StatusBarNotification,
        lifecycle: String,
        extraction: DiscordVoiceExtraction =
            DiscordVoiceNotificationExtractor.extract(statusBarNotification),
    ) {
        if (!BuildConfig.DEBUG) return
        val notification = statusBarNotification.notification
        NotificationDebugPayloadStore.recordDiscord(
            this,
            statusBarNotification,
            readNotificationText(this, statusBarNotification.packageName, notification),
            readNotificationTitle(notification),
            readNotificationContentText(notification),
            lifecycle,
            extraction,
        )
    }

    private fun recordTeams(
        statusBarNotification: StatusBarNotification,
        lifecycle: String,
        notificationText: String = readNotificationText(
            this,
            statusBarNotification.packageName,
            statusBarNotification.notification,
        ),
        extraction: TeamsCallExtraction? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val notification = statusBarNotification.notification
        NotificationDebugPayloadStore.recordTeams(
            this,
            statusBarNotification,
            notificationText,
            readNotificationTitle(notification),
            readNotificationContentText(notification),
            lifecycle,
            extraction,
        )
    }

    private fun recordGoogleRecorder(
        statusBarNotification: StatusBarNotification,
        lifecycle: String,
        extraction: RecorderExtraction? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val notification = statusBarNotification.notification
        val notificationText = readNotificationText(
            this,
            statusBarNotification.packageName,
            notification,
        )
        val resolvedExtraction = extraction ?: if (lifecycle == "REMOVED") {
            null
        } else {
            GoogleRecorderNotificationExtractor.extract(statusBarNotification, notificationText)
        }
        NotificationDebugPayloadStore.recordGoogleRecorder(
            this,
            statusBarNotification,
            notificationText,
            readNotificationTitle(notification),
            readNotificationContentText(notification),
            lifecycle,
            resolvedExtraction,
        )
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {
        super.onNotificationRemoved(statusBarNotification)
        if (statusBarNotification?.packageName == "com.google.android.calendar" ||
            statusBarNotification?.packageName == "com.oplus.calendar") {
            CalendarCountdownManager.stop(this)
        }
        mediaPlaybackMonitor.onNotificationRemoved(statusBarNotification)
        if (statusBarNotification.packageName == CITYMAPPER_PACKAGE) {
            if (BuildConfig.DEBUG) {
                val notification = statusBarNotification.notification
                NotificationDebugPayloadStore.recordCitymapper(
                    this,
                    statusBarNotification,
                    readNotificationText(this, statusBarNotification.packageName, notification),
                    readNotificationTitle(notification),
                    readNotificationContentText(notification),
                    "REMOVED",
                )
            }
            handleCitymapperDecision(citymapperTracker.onRemoved(statusBarNotification.key))
            return
        }
        if (BuildConfig.DEBUG && statusBarNotification.packageName == BOLT_PACKAGE) {
            val notification = statusBarNotification.notification
            NotificationDebugPayloadStore.recordBolt(
                this,
                statusBarNotification,
                readNotificationText(this, statusBarNotification.packageName, notification),
                readNotificationTitle(notification),
                readNotificationContentText(notification),
                "REMOVED",
            )
            return
        }
        if (BuildConfig.DEBUG && statusBarNotification.packageName == STRAVA_PACKAGE) {
            val notification = statusBarNotification.notification
            NotificationDebugPayloadStore.recordStrava(
                this,
                statusBarNotification,
                readNotificationText(this, statusBarNotification.packageName, notification),
                readNotificationTitle(notification),
                readNotificationContentText(notification),
                "REMOVED",
            )
        }
        if (statusBarNotification.packageName == STRAVA_PACKAGE) {
            handleStravaRecordingDecision(
                stravaRecordingTracker.onRemoved(statusBarNotification.key),
            )
            return
        }
        if (statusBarNotification.packageName == TEAMS_PACKAGE) {
            if (BuildConfig.DEBUG) recordTeams(statusBarNotification, "REMOVED")
            handleTeamsCallDecision(teamsCallTracker.onRemoved(statusBarNotification.key))
            return
        }
        if (statusBarNotification.packageName == GOOGLE_RECORDER_PACKAGE) {
            if (BuildConfig.DEBUG) recordGoogleRecorder(statusBarNotification, "REMOVED")
            handleRecorderDecision(recorderTracker.onRemoved(statusBarNotification.key))
            return
        }
        if (statusBarNotification.packageName == DISCORD_PACKAGE) {
            if (BuildConfig.DEBUG) recordDiscord(statusBarNotification, "REMOVED")
            handleDiscordVoiceDecision(
                discordVoiceTracker.onRemoved(statusBarNotification.key),
            )
            return
        }
        if (statusBarNotification.packageName == HEVY_PACKAGE) {
            recordRemovedHevy(statusBarNotification)
            handleHevyWorkoutDecision(hevyWorkoutTracker.onRemoved(statusBarNotification.key))
            return
        }
        if (
            BuildConfig.DEBUG &&
            statusBarNotification.packageName == TAIWAN_PAY_PACKAGE
        ) {
            val notification = statusBarNotification.notification
            val notificationTitle = readNotificationTitle(notification)
            val notificationContentText = readNotificationContentText(notification)
            NotificationDebugPayloadStore.recordTaiwanPay(
                this,
                statusBarNotification,
                readNotificationText(this, statusBarNotification.packageName, notification),
                notificationTitle,
                notificationContentText,
                "REMOVED",
                LiveStatusNotificationParser.parseTaiwanPay(
                    notificationTitle,
                    notificationContentText,
                ),
            )
            return
        }
        if (statusBarNotification.packageName == CLOCK_PACKAGE) {
            handleClockTimerDecision(clockTimerTracker.onRemoved(statusBarNotification.key))
            return
        }
        if (statusBarNotification.packageName == YPT_PACKAGE) {
            handleYptStudyDecision(yptStudyTracker.onRemoved(statusBarNotification.key))
            return
        }
        if (statusBarNotification.packageName == UBER_EATS_PACKAGE) {
            handleUberEatsDecision(uberEatsTracker.onRemoved(statusBarNotification.key))
            return
        }
        if (statusBarNotification.packageName != PIKMIN_BLOOM_PACKAGE) return

        val notificationText = readNotificationText(statusBarNotification.notification)
        if (
            LiveStatusNotificationParser.parsePikminBloom(notificationText).event ==
            LiveStatusNotificationParser.PikminEvent.FLOWER_PLANTING
        ) {
            LiveStatusReminder.clearPikminBloom(this)
        }
    }

    override fun onDestroy() {
        AppReminderPreferences.unregisterListener(this, citymapperPreferenceListener)
        handleCitymapperDecision(citymapperTracker.reset())
        stopClockTimerRefresh()
        mediaPlaybackMonitor.stop()
        try { unregisterReceiver(ringerReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(vpnReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        val activeNotifications = getActiveNotifications()
        mediaPlaybackMonitor.start(activeNotifications)
        if (BuildConfig.DEBUG) {
            activeNotifications
                .filter { it.packageName == TEAMS_PACKAGE }
                .forEach {
                    recordTeams(
                        it,
                        "ACTIVE_SNAPSHOT",
                        extraction = TeamsCallNotificationExtractor.extract(it),
                    )
                }
            activeNotifications
                .filter { it.packageName == DISCORD_PACKAGE }
                .forEach { recordDiscord(it, "ACTIVE_SNAPSHOT") }
            activeNotifications
                .filter { it.packageName == GOOGLE_RECORDER_PACKAGE }
                .forEach { recordGoogleRecorder(it, "ACTIVE_SNAPSHOT") }
            activeNotifications
                .filter { it.packageName == STRAVA_PACKAGE }
                .forEach {
                    val notification = it.notification
                    NotificationDebugPayloadStore.recordStrava(
                        this,
                        it,
                        readNotificationText(this, it.packageName, notification),
                        readNotificationTitle(notification),
                        readNotificationContentText(notification),
                        "ACTIVE_SNAPSHOT",
                        parseStravaRecording(
                            it,
                            readNotificationTitle(notification),
                            readNotificationContentText(notification),
                        ),
                    )
                }
        }
        restoreDiscordVoice(activeNotifications)
        restoreTeamsCall(activeNotifications)
        restoreGoogleRecorder(activeNotifications)
        restoreYptStudy(activeNotifications)
        restoreHevyWorkout(activeNotifications)
        restoreStravaRecording(activeNotifications)
        restoreCitymapperNavigation(activeNotifications)
        YouBikeRideManager.restore(this)
    }

    override fun onListenerDisconnected() {
        handleCitymapperDecision(citymapperTracker.reset())
        mediaPlaybackMonitor.stop()
        super.onListenerDisconnected()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mediaPlaybackMonitor.refresh()
    }

    private fun handleCitymapperDecision(decision: CitymapperNavigationDecision) {
        when (decision) {
            is CitymapperNavigationDecision.Show -> LiveStatusReminder.showCitymapperNavigation(this, decision.update)
            CitymapperNavigationDecision.Clear -> LiveStatusReminder.clearCitymapperNavigation(this)
            CitymapperNavigationDecision.None -> Unit
        }
    }

    private fun restoreCitymapperNavigation(notifications: Array<out StatusBarNotification>) {
        if (!AppReminderPreferences.App.CITYMAPPER.isEnabled(this)) {
            handleCitymapperDecision(citymapperTracker.reset())
            return
        }
        val updates = notifications.filter { it.packageName == CITYMAPPER_PACKAGE }
            .mapNotNull { CitymapperNotificationExtractor.extract(this, it) }
        handleCitymapperDecision(citymapperTracker.restore(updates))
    }

    private fun handleClockTimerDecision(decision: ClockTimerDecision) {
        when (decision) {
            is ClockTimerDecision.Show -> showAndScheduleClockTimer(decision.update)
            ClockTimerDecision.Clear -> {
                stopClockTimerRefresh()
                LiveStatusReminder.clearClockTimer(this)
            }
            ClockTimerDecision.None -> Unit
        }
    }

    private fun handleYptStudyDecision(decision: YptStudyDecision) {
        when (decision) {
            is YptStudyDecision.Show -> LiveStatusReminder.showYptStudy(this, decision.update)
            YptStudyDecision.Clear -> LiveStatusReminder.clearYptStudy(this)
            YptStudyDecision.None -> Unit
        }
    }

    private fun handleHevyWorkoutDecision(decision: HevyWorkoutDecision) {
        when (decision) {
            is HevyWorkoutDecision.Show -> LiveStatusReminder.showHevyWorkout(this, decision.update)
            HevyWorkoutDecision.Clear -> LiveStatusReminder.clearHevyWorkout(this)
            HevyWorkoutDecision.None -> Unit
        }
    }

    private fun handleStravaRecordingDecision(decision: StravaRecordingDecision) {
        when (decision) {
            is StravaRecordingDecision.Show ->
                LiveStatusReminder.showStravaRecording(this, decision.update)
            StravaRecordingDecision.Clear -> LiveStatusReminder.clearStravaRecording(this)
            StravaRecordingDecision.None -> Unit
        }
    }

    private fun handleDiscordVoiceDecision(decision: DiscordVoiceDecision) {
        when (decision) {
            is DiscordVoiceDecision.Show -> LiveStatusReminder.showDiscordVoice(this, decision.update)
            DiscordVoiceDecision.Clear -> LiveStatusReminder.clearDiscordVoice(this)
            DiscordVoiceDecision.None -> Unit
        }
    }

    private fun handleTeamsCallDecision(decision: TeamsCallDecision) {
        when (decision) {
            is TeamsCallDecision.Show -> LiveStatusReminder.showTeamsCall(this, decision.update)
            TeamsCallDecision.Clear -> LiveStatusReminder.clearTeamsCall(this)
            TeamsCallDecision.None -> Unit
        }
    }

    private fun handleRecorderDecision(decision: RecorderDecision) {
        when (decision) {
            is RecorderDecision.Show -> LiveStatusReminder.showGoogleRecorder(this, decision.update)
            RecorderDecision.Clear -> LiveStatusReminder.clearGoogleRecorder(this)
            RecorderDecision.None -> Unit
        }
    }

    private fun restoreDiscordVoice(activeNotifications: Array<StatusBarNotification>) {
        if (!AppReminderPreferences.App.DISCORD_VOICE.isEnabled(this)) {
            discordVoiceTracker.reset()
            LiveStatusReminder.clearDiscordVoice(this)
            return
        }

        val updates = activeNotifications
            .asSequence()
            .filter { DiscordVoiceNotificationParser.supportsPackage(it.packageName) }
            .mapNotNull { DiscordVoiceNotificationExtractor.extract(it).update }
            .toList()
        handleDiscordVoiceDecision(discordVoiceTracker.restore(updates))
    }

    private fun restoreTeamsCall(activeNotifications: Array<StatusBarNotification>) {
        if (!AppReminderPreferences.App.TEAMS_CALL.isEnabled(this)) {
            teamsCallTracker.reset()
            LiveStatusReminder.clearTeamsCall(this)
            return
        }

        val updates = activeNotifications
            .asSequence()
            .filter { TeamsCallNotificationParser.supportsPackage(it.packageName) }
            .mapNotNull { TeamsCallNotificationExtractor.extract(it).update }
            .toList()
        handleTeamsCallDecision(teamsCallTracker.restore(updates))
    }

    private fun restoreGoogleRecorder(activeNotifications: Array<StatusBarNotification>) {
        if (!AppReminderPreferences.App.GOOGLE_RECORDER.isEnabled(this)) {
            recorderTracker.reset()
            LiveStatusReminder.clearGoogleRecorder(this)
            return
        }

        val updates = activeNotifications
            .asSequence()
            .filter { GoogleRecorderNotificationParser.supportsPackage(it.packageName) }
            .mapNotNull { statusBarNotification ->
                GoogleRecorderNotificationExtractor.extract(
                    statusBarNotification,
                    readNotificationText(
                        this,
                        statusBarNotification.packageName,
                        statusBarNotification.notification,
                    ),
                ).update
            }
            .toList()
        handleRecorderDecision(recorderTracker.restore(updates))
    }

    private fun restoreYptStudy(activeNotifications: Array<StatusBarNotification>) {
        if (!AppReminderPreferences.App.YPT.isEnabled(this)) {
            yptStudyTracker.reset()
            LiveStatusReminder.clearYptStudy(this)
            return
        }

        val updates = activeNotifications
            .asSequence()
            .filter { it.packageName == YPT_PACKAGE }
            .mapNotNull { YptStudyNotificationExtractor.extract(it).update }
            .toList()
        handleYptStudyDecision(yptStudyTracker.restore(updates))
    }

    private fun restoreHevyWorkout(activeNotifications: Array<StatusBarNotification>) {
        if (!AppReminderPreferences.App.HEVY.isEnabled(this)) {
            hevyWorkoutTracker.reset()
            LiveStatusReminder.clearHevyWorkout(this)
            return
        }

        val updates = activeNotifications
            .asSequence()
            .filter { it.packageName == HEVY_PACKAGE }
            .mapNotNull { statusBarNotification ->
                parseHevyWorkout(
                    statusBarNotification,
                    readNotificationText(
                        this,
                        statusBarNotification.packageName,
                        statusBarNotification.notification,
                    ),
                )
            }
            .toList()
        handleHevyWorkoutDecision(hevyWorkoutTracker.restore(updates))
    }

    private fun restoreStravaRecording(activeNotifications: Array<StatusBarNotification>) {
        if (!AppReminderPreferences.App.STRAVA.isEnabled(this)) {
            stravaRecordingTracker.reset()
            LiveStatusReminder.clearStravaRecording(this)
            return
        }
        val updates = activeNotifications
            .asSequence()
            .filter { it.packageName == STRAVA_PACKAGE }
            .mapNotNull { statusBarNotification ->
                val notification = statusBarNotification.notification
                parseStravaRecording(
                    statusBarNotification,
                    readNotificationTitle(notification),
                    readNotificationContentText(notification),
                )
            }
            .toList()
        handleStravaRecordingDecision(stravaRecordingTracker.restore(updates))
    }

    private fun parseStravaRecording(
        statusBarNotification: StatusBarNotification,
        notificationTitle: String?,
        notificationContentText: String?,
    ): StravaRecordingUpdate? {
        val notification = statusBarNotification.notification
        return StravaRecordingNotificationParser.parse(
            sourceKey = statusBarNotification.key,
            channelId = notification.channelId,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            isForegroundService =
                notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            notificationTitle = notificationTitle,
            notificationContentText = notificationContentText,
            contentIntent = notification.contentIntent,
            sourceActions = notification.actions.orEmpty().toList(),
        )
    }

    private fun showAndScheduleClockTimer(update: ClockTimerUpdate) {
        clockTimerHandler.removeCallbacks(clockTimerRefreshRunnable)
        activeClockTimerUpdate = update
        LiveStatusReminder.showClockTimer(this, update)
        scheduleClockTimerRefresh(update)
    }

    private fun refreshClockTimer() {
        val update = activeClockTimerUpdate ?: return
        val endElapsedRealtimeMillis = update.endElapsedRealtimeMillis ?: return
        val remainingMillis = endElapsedRealtimeMillis - SystemClock.elapsedRealtime()
        if (remainingMillis <= 0) {
            stopClockTimerRefresh()
            LiveStatusReminder.clearClockTimer(this)
            return
        }
        LiveStatusReminder.showClockTimer(this, update)
        scheduleClockTimerRefresh(update)
    }

    private fun scheduleClockTimerRefresh(update: ClockTimerUpdate) {
        if (update.state != ClockTimerState.RUNNING) return
        val remainingMillis = requireNotNull(update.endElapsedRealtimeMillis) -
            SystemClock.elapsedRealtime()
        val delayMillis = ClockTimerRefreshTiming.nextDelayMillis(remainingMillis) ?: return
        clockTimerHandler.postDelayed(clockTimerRefreshRunnable, delayMillis)
    }

    private fun stopClockTimerRefresh() {
        clockTimerHandler.removeCallbacks(clockTimerRefreshRunnable)
        activeClockTimerUpdate = null
    }

    private fun handleRideNotification(notificationText: String) {
        when (LiveStatusNotificationParser.parse(notificationText)) {
            LiveStatusNotificationParser.RideEvent.ENTERED -> LiveStatusReminder.show(this)
            LiveStatusNotificationParser.RideEvent.EXITED -> LiveStatusReminder.clear(this)
            LiveStatusNotificationParser.RideEvent.NONE -> Unit
        }
    }

    private fun handleTaiwanPayRideNotification(
        update: LiveStatusNotificationParser.TaiwanPayRideUpdate,
    ) {
        when (update.event) {
            LiveStatusNotificationParser.RideEvent.ENTERED ->
                LiveStatusReminder.showTaiwanPay(this)
            LiveStatusNotificationParser.RideEvent.EXITED ->
                LiveStatusReminder.clearTaiwanPay(this)
            LiveStatusNotificationParser.RideEvent.NONE -> Unit
        }
    }

    private fun handleFoodpandaNotification(event: LiveStatusNotificationParser.FoodpandaEvent) {
        when (event) {
            LiveStatusNotificationParser.FoodpandaEvent.COURIER_ON_THE_WAY,
            LiveStatusNotificationParser.FoodpandaEvent.COURIER_ARRIVING,
            -> LiveStatusReminder.showFoodpanda(this, event)
            LiveStatusNotificationParser.FoodpandaEvent.ORDER_ENDED -> {
                LiveStatusReminder.clearFoodpanda(this)
            }
            LiveStatusNotificationParser.FoodpandaEvent.NONE -> Unit
        }
    }

    private fun handleUberRideNotification(update: LiveStatusNotificationParser.UberRideUpdate) {
        if (update.event == LiveStatusNotificationParser.UberRideEvent.TRIP_ENDED) {
            resetUberRideState()
            LiveStatusReminder.clearUberRide(this)
            return
        }

        if (update.event == LiveStatusNotificationParser.UberRideEvent.NONE) return

        lastUberRideUpdate = lastUberRideUpdate.merge(update)
        if (lastUberRideUpdate.event != LiveStatusNotificationParser.UberRideEvent.NONE) {
            LiveStatusReminder.showUberRide(this, lastUberRideUpdate)
        }
    }

    private fun handleUberEatsDecision(decision: UberEatsDecision) {
        when (decision) {
            is UberEatsDecision.Show -> LiveStatusReminder.showUberEats(
                context = this,
                event = decision.update.event,
                pin = decision.update.pin,
                language = decision.update.language,
                officialTitle = decision.update.officialTitle,
                officialText = decision.update.officialText,
            )
            UberEatsDecision.Clear -> LiveStatusReminder.clearUberEats(this)
            UberEatsDecision.None -> Unit
        }
    }

    private fun handlePikminBloomNotification(notificationText: String) {
        val update = LiveStatusNotificationParser.parsePikminBloom(notificationText)
        when (update.event) {
            LiveStatusNotificationParser.PikminEvent.FLOWER_PLANTING ->
                LiveStatusReminder.showPikminBloom(this, update.language)
            LiveStatusNotificationParser.PikminEvent.NONE ->
                LiveStatusReminder.clearPikminBloom(this)
        }
    }

    private fun resetUberRideState() {
        lastUberRideUpdate =
            LiveStatusNotificationParser.UberRideUpdate(LiveStatusNotificationParser.UberRideEvent.NONE)
    }

    private fun LiveStatusNotificationParser.UberRideUpdate.merge(
        update: LiveStatusNotificationParser.UberRideUpdate,
    ): LiveStatusNotificationParser.UberRideUpdate {
        if (rideType != update.rideType) return update

        val mergedEvent = if (uberRideEventRank(update.event) >= uberRideEventRank(event)) {
            update.event
        } else {
            event
        }
        return LiveStatusNotificationParser.UberRideUpdate(
            event = mergedEvent,
            rideType = update.rideType,
            language = update.language,
            title = update.title ?: title,
            officialText = update.officialText ?: officialText,
            pickupEtaMinutes = update.pickupEtaMinutes ?: pickupEtaMinutes,
            pickupPoint = update.pickupPoint ?: pickupPoint,
            dropoffPoint = update.dropoffPoint ?: dropoffPoint,
            plate = update.plate ?: plate,
            vehicle = update.vehicle ?: vehicle,
            pin = update.pin ?: pin,
        )
    }

    private fun uberRideEventRank(event: LiveStatusNotificationParser.UberRideEvent): Int = when (event) {
        LiveStatusNotificationParser.UberRideEvent.PICKUP_EN_ROUTE -> 1
        LiveStatusNotificationParser.UberRideEvent.PICKUP_APPROACHING -> 2
        LiveStatusNotificationParser.UberRideEvent.PICKUP_NEARBY -> 3
        LiveStatusNotificationParser.UberRideEvent.ARRIVED -> 4
        LiveStatusNotificationParser.UberRideEvent.ON_TRIP -> 5
        LiveStatusNotificationParser.UberRideEvent.TRIP_ENDED -> 6
        else -> 0
    }

    companion object {
        private const val CLOCK_PACKAGE = ClockTimerNotificationExtractor.CLOCK_PACKAGE
        private const val IPASS_PACKAGE = "com.ipass.ipassmoney"
        private const val TAIWAN_PAY_PACKAGE = "tw.com.twmp.twhcewallet"
        private const val YOU_BIKE_PACKAGE = "tw.com.youbike.plus"
        private const val FOODPANDA_PACKAGE = "com.global.foodpanda.android"
        private const val TAIWAN_TAXI_PACKAGE = "dbx.taiwantaxi"
        private const val CITYMAPPER_PACKAGE = CitymapperNavigationMapper.PACKAGE_NAME
        private const val BOLT_PACKAGE = "ee.mtakso.client"
        private const val UBER_RIDE_PACKAGE = "com.ubercab"
        private const val UBER_EATS_PACKAGE = "com.ubercab.eats"
        private const val PIKMIN_BLOOM_PACKAGE = "com.nianticlabs.pikmin"
        private const val YPT_PACKAGE = YptStudyNotificationParser.PACKAGE_NAME
        private const val HEVY_PACKAGE = HevyWorkoutNotificationParser.PACKAGE_NAME
        private const val STRAVA_PACKAGE = "com.strava"
        private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
        private const val REALME_MESSAGES_PACKAGE = "com.coloros.mms"
        private const val REALME_HEYTAP_PACKAGE = "com.heytap.mcs"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val DISCORD_PACKAGE = DiscordVoiceNotificationParser.PACKAGE_NAME
        private const val TEAMS_PACKAGE = "com.microsoft.teams"
        private const val GOOGLE_RECORDER_PACKAGE = GoogleRecorderNotificationParser.PACKAGE_NAME

        @JvmStatic
        fun readNotificationText(notification: Notification): String {
            val extras = notification.extras
            val text = join(
                notification.tickerText,
                extras.getCharSequence(Notification.EXTRA_TITLE),
                extras.getCharSequence(Notification.EXTRA_TEXT),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
                extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
            )
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            return if (lines == null) text else text + join(*lines)
        }

        @JvmStatic
        fun readNotificationText(context: Context, notification: Notification): String =
            readNotificationText(context, null, notification)

        @JvmStatic
        fun readNotificationText(
            context: Context,
            packageName: String?,
            notification: Notification,
        ): String =
            readNotificationText(notification) + readRemoteViewsText(context, packageName, notification)

        @JvmStatic
        fun readShortCriticalText(notification: Notification): String? =
            notification.shortCriticalText?.toString()

        @JvmStatic
        fun readNotificationTitle(notification: Notification): String? =
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toCleanString()

        @JvmStatic
        fun readNotificationContentText(notification: Notification): String? {
            val extras = notification.extras
            return extras.getCharSequence(Notification.EXTRA_TEXT)?.toCleanString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toCleanString()
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.mapNotNull { it.toCleanString() }
                    ?.joinToString(" · ")
                    ?.takeIf { it.isNotBlank() }
        }

        private fun join(vararg values: CharSequence?): String = buildString {
            values.forEach { value ->
                if (value != null) append(value).append('\n')
            }
        }

        internal fun readCitymapperNotificationText(
            context: Context,
            packageName: String,
            notification: Notification,
        ): String = readNotificationText(notification) +
            readRemoteViewsText(context, packageName, notification, preserveLineBreaks = true)

        private fun readRemoteViewsText(
            context: Context,
            packageName: String?,
            notification: Notification,
            preserveLineBreaks: Boolean = false,
        ): String {
            val packageContext = packageName?.let {
                runCatching { context.createPackageContext(it, 0) }.getOrNull()
            }
            val contexts = listOfNotNull(packageContext, context).distinct()
            return contexts
                .flatMap { remoteViewContext ->
                    notification.remoteViews().flatMap { remoteViews ->
                        remoteViews.readTextViews(remoteViewContext, preserveLineBreaks)
                    }
                }
                .distinct()
                .joinToString(separator = "\n", postfix = "\n")
        }

        private fun Notification.remoteViews(): List<RemoteViews> =
            listOfNotNull(
                contentView,
                bigContentView,
                headsUpContentView,
                publicVersion?.contentView,
                publicVersion?.bigContentView,
                publicVersion?.headsUpContentView,
            )

        private fun RemoteViews.readTextViews(context: Context, preserveLineBreaks: Boolean): List<String> =
            runCatching {
                val view = apply(context, null)
                view.collectTextViews(preserveLineBreaks)
            }.getOrDefault(emptyList())

        private fun View.collectTextViews(preserveLineBreaks: Boolean): List<String> = when (this) {
            is TextView -> listOfNotNull(
                if (preserveLineBreaks) text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                else text.toCleanString(),
            )
            is ViewGroup -> buildList {
                repeat(childCount) { index ->
                    addAll(getChildAt(index).collectTextViews(preserveLineBreaks))
                }
            }
            else -> emptyList()
        }

        private fun CharSequence.toCleanString(): String? =
            toString().replace(Regex("""\s+"""), " ").trim().takeIf { it.isNotEmpty() }
    }
}
