package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.example.service.NotificationSyncManager
import com.example.util.NotificationHelper
import com.example.util.SecureFirebaseVault
import com.example.util.configureAppCheck
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

private const val TAG = "PaircartApp"

object AppVisibilityTracker {
  @Volatile
  var isInForeground: Boolean = false
    internal set
}

class PaircartApplication : Application(), Application.ActivityLifecycleCallbacks {

  private var startedActivityCount = 0

  override fun onCreate() {
    super.onCreate()
    registerActivityLifecycleCallbacks(this)

    val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
    if (isRobolectric) return
    if (!com.example.util.AppIntegrityGuard.verifyAppIntegrity(this)) return

    try {
      SecureFirebaseVault.ensureFirebaseInitialized(this)
      configureAppCheck(this, null)
    } catch (e: Throwable) {
      Log.w(TAG, "Firebase init in Application skipped: ${e.message}")
    }

    try {
      NotificationHelper.createNotificationChannel(this)
    } catch (e: Throwable) {
      Log.w(TAG, "Notification channel init failed: ${e.message}")
    }

    try {
      if (Firebase.auth.currentUser != null) {
        NotificationSyncManager.scheduleBackgroundSync(this)
        NotificationHelper.ensureShoppingAlarmScheduledIfActive(this)
      }
    } catch (e: Throwable) {
      Log.w(TAG, "Background sync schedule in Application failed: ${e.message}")
    }
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

  override fun onActivityStarted(activity: Activity) {
    startedActivityCount++
    AppVisibilityTracker.isInForeground = startedActivityCount > 0
  }

  override fun onActivityResumed(activity: Activity) {
    AppVisibilityTracker.isInForeground = true
  }

  override fun onActivityPaused(activity: Activity) {}

  override fun onActivityStopped(activity: Activity) {
    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    AppVisibilityTracker.isInForeground = startedActivityCount > 0
    if (!AppVisibilityTracker.isInForeground) {
      try {
        if (Firebase.auth.currentUser != null) {
          NotificationSyncManager.scheduleBackgroundSync(this)
        }
      } catch (e: Throwable) {
        Log.w(TAG, "Failed to schedule background sync on stop: ${e.message}")
      }
    }
  }

  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

  override fun onActivityDestroyed(activity: Activity) {}
}
