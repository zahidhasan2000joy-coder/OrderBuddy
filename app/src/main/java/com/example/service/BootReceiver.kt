package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
      val appContext = context.applicationContext
      try {
        NotificationSyncManager.ensureFirebaseInitialized(appContext)
        if (Firebase.auth.currentUser != null) {
          NotificationSyncManager.scheduleBackgroundSync(appContext)
          com.example.util.NotificationHelper.rescheduleShoppingAlarmIfActive(appContext)
          OrderLifecycleManager.rescheduleFromCache(appContext)
        }
      } catch (e: Throwable) {
        Log.w(TAG, "BootReceiver init warning: ${e.message}")
      }
    }
  }
}
