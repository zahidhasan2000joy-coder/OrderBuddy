package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R

private const val TAG = "NotificationHelper"

object NotificationHelper {

  private val LEGACY_CHANNEL_IDS = listOf("orderbuddy_orders_channel", "orderbuddy_orders_channel_v2")
  private const val CHANNEL_ID = "orderbuddy_orders_channel_v3"
  private const val CHANNEL_NAME = "Paircart Notifications"
  private const val CHANNEL_DESC = "Notifications for order events, reminders, and partner updates"
  private val soundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

  fun areNotificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
      ) {
        return false
      }
    }
    return try {
      NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (_: Throwable) {
      true
    }
  }

  fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
      }
    } else {
      Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = android.net.Uri.fromParts("package", context.packageName, null)
      }
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(intent)
    } catch (_: Throwable) {
      // ignore if settings activity unavailable
    }
  }

  fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      LEGACY_CHANNEL_IDS.forEach { legacyId ->
        try {
          notificationManager.deleteNotificationChannel(legacyId)
        } catch (_: Throwable) {
          // ignore if legacy channel doesn't exist
        }
      }
      val importance = NotificationManager.IMPORTANCE_HIGH
      val soundUri = android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.paircart_sound}")
      val audioAttributes = android.media.AudioAttributes.Builder()
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
        .build()

      val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
        description = CHANNEL_DESC
        enableLights(true)
        enableVibration(true)
        vibrationPattern = longArrayOf(0L, 250L, 120L, 250L)
        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        setShowBadge(true)
        setSound(soundUri, audioAttributes)
      }
      notificationManager.createNotificationChannel(channel)
    }
  }

  private val recentNotifications = java.util.concurrent.ConcurrentHashMap<String, Long>()

  fun playCustomNotificationSound(context: Context) {
    val appContext = context.applicationContext
    soundExecutor.execute {
      var player: android.media.MediaPlayer? = null
      try {
        val soundUri = android.net.Uri.parse("android.resource://${appContext.packageName}/${R.raw.paircart_sound}")
        player = android.media.MediaPlayer().apply {
          setAudioAttributes(
            android.media.AudioAttributes.Builder()
              .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
              .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build()
          )
          setDataSource(appContext, soundUri)
          setOnCompletionListener { mp ->
            try { mp.release() } catch (_: Throwable) {}
          }
          setOnErrorListener { mp, _, _ ->
            try { mp.release() } catch (_: Throwable) {}
            true
          }
          prepare()
          start()
        }
      } catch (e: Throwable) {
        Log.w(TAG, "playCustomNotificationSound failed: ${e.message}")
        try { player?.release() } catch (_: Throwable) {}
      }
    }
  }

  fun showNotification(
    context: Context,
    title: String,
    message: String,
    notificationId: Int = 1001,
    bypassDebounce: Boolean = false,
    silent: Boolean = false
  ): Boolean {
    val dedupeKey = "$notificationId::$title::$message"
    val now = System.currentTimeMillis()
    if (!bypassDebounce) {
      val lastTime = recentNotifications[dedupeKey] ?: 0L
      if (now - lastTime < 5000L) {
        // Duplicate notification already posted within 5 seconds
        return true
      }
    }

    if (!areNotificationsAllowed(context)) {
      Log.w(TAG, "Notifications are disabled or POST_NOTIFICATIONS not granted; skipping notify")
      return false
    }

    createNotificationChannel(context)

    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val safeNotificationId = if (notificationId == 0) 1001 else notificationId
    val pendingIntent = PendingIntent.getActivity(
      context,
      safeNotificationId,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setColor(0xFFF57C00.toInt())
      .setContentTitle(title)
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(message))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setContentIntent(pendingIntent)
      .setOnlyAlertOnce(false)
      .setSilent(silent)
      .setAutoCancel(true)

    if (!silent && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      val soundUri = android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.paircart_sound}")
      builder
        .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
        .setSound(soundUri)
    }

    return try {
      val notificationManager = NotificationManagerCompat.from(context)
      notificationManager.notify(safeNotificationId, builder.build())
      recentNotifications[dedupeKey] = now
      true
    } catch (e: SecurityException) {
      Log.w(TAG, "SecurityException posting notification: ${e.message}")
      false
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to post notification: ${e.message}", e)
      false
    }
  }

  private const val REMINDER_PREFS = "orderbuddy_reminder_prefs"
  private const val KEY_REMINDER_ACTIVE = "reminder_active"
  private const val KEY_REMINDER_INTERVAL_MINS = "reminder_interval_mins"
  private const val KEY_REMINDER_ITEMS_SUMMARY = "reminder_items_summary"
  private const val KEY_REMINDER_ITEMS_COUNT = "reminder_items_count"

  fun isReminderActive(context: Context): Boolean {
    val prefs = context.applicationContext.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_REMINDER_ACTIVE, false) && prefs.getInt(KEY_REMINDER_INTERVAL_MINS, 0) > 0
  }

  fun getActiveReminderMinutes(context: Context): Int {
    val prefs = context.applicationContext.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(KEY_REMINDER_ACTIVE, false)) return 0
    return prefs.getInt(KEY_REMINDER_INTERVAL_MINS, 0)
  }

  fun getSavedReminderSummary(context: Context): String {
    val prefs = context.applicationContext.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
    return prefs.getString(KEY_REMINDER_ITEMS_SUMMARY, "") ?: ""
  }

  fun getSavedReminderCount(context: Context): Int {
    val prefs = context.applicationContext.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
    return prefs.getInt(KEY_REMINDER_ITEMS_COUNT, 0)
  }

  fun rescheduleShoppingAlarmIfActive(context: Context) {
    val mins = getActiveReminderMinutes(context)
    if (mins > 0) {
      scheduleShoppingAlarm(
        context = context,
        minutesLater = mins,
        itemsSummary = getSavedReminderSummary(context),
        itemsCount = getSavedReminderCount(context)
      )
    }
  }

  fun ensureShoppingAlarmScheduledIfActive(context: Context) {
    if (!isReminderActive(context)) return
    val appContext = context.applicationContext
    val intent = Intent(appContext, ShoppingReminderReceiver::class.java).apply {
      setPackage(appContext.packageName)
    }
    val existing = PendingIntent.getBroadcast(
      appContext,
      2002,
      intent,
      PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    if (existing == null) {
      rescheduleShoppingAlarmIfActive(appContext)
    }
  }

  fun scheduleShoppingAlarm(
    context: Context,
    minutesLater: Int,
    itemsSummary: String,
    itemsCount: Int
  ) {
    if (minutesLater <= 0) return
    val appContext = context.applicationContext
    appContext.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_REMINDER_ACTIVE, true)
      .putInt(KEY_REMINDER_INTERVAL_MINS, minutesLater)
      .putString(KEY_REMINDER_ITEMS_SUMMARY, itemsSummary)
      .putInt(KEY_REMINDER_ITEMS_COUNT, itemsCount)
      .commit()

    val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
    val intent = Intent(appContext, ShoppingReminderReceiver::class.java).apply {
      setPackage(appContext.packageName)
      putExtra("ITEMS_SUMMARY", itemsSummary)
      putExtra("ITEMS_COUNT", itemsCount)
      putExtra("MINUTES_LATER", minutesLater)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      appContext,
      2002,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val triggerAtMillis = System.currentTimeMillis() + (minutesLater * 60 * 1000L)
    try {
      val canScheduleExact = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> alarmManager.canScheduleExactAlarms()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> true
        else -> false
      }
      if (canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
      } else {
        alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
      }
    } catch (e: SecurityException) {
      Log.w(TAG, "Exact alarm not permitted, falling back to setAndAllowWhileIdle: ${e.message}")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
      } else {
        alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to schedule shopping alarm: ${e.message}", e)
    }
  }

  fun cancelShoppingAlarm(context: Context) {
    val appContext = context.applicationContext
    appContext.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_REMINDER_ACTIVE, false)
      .putInt(KEY_REMINDER_INTERVAL_MINS, 0)
      .remove(KEY_REMINDER_ITEMS_SUMMARY)
      .remove(KEY_REMINDER_ITEMS_COUNT)
      .commit()

    val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
    val intent = Intent(appContext, ShoppingReminderReceiver::class.java).apply {
      setPackage(appContext.packageName)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      appContext,
      2002,
      intent,
      PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    if (pendingIntent != null) {
      alarmManager.cancel(pendingIntent)
      pendingIntent.cancel()
    }
  }
}
