package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "NotificationPollReceiver"

class NotificationPollReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val appContext = context.applicationContext
    val pendingResult = goAsync()
    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val wakeLock = try {
      powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Paircart:PollWakeLock")?.apply {
        acquire(10_000L)
      }
    } catch (_: Throwable) {
      null
    }

    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
      try {
        val completed = withTimeoutOrNull(8_000L) {
          NotificationSyncManager.fetchAndShowPendingNotifications(appContext)
        }
        if (completed != true) {
          // If cold-start network took longer than BroadcastReceiver window, hand off to JobService WakeLock
          NotificationSyncManager.triggerImmediateJob(appContext)
        }
      } catch (e: Throwable) {
        Log.w(TAG, "Error polling notifications: ${e.message}")
        NotificationSyncManager.triggerImmediateJob(appContext)
      } finally {
        try {
          NotificationSyncManager.ensureFirebaseInitialized(appContext)
          if (Firebase.auth.currentUser != null) {
            NotificationSyncManager.scheduleNextAlarm(appContext)
            NotificationSyncManager.scheduleQuickPollJob(appContext)
          }
        } catch (e: Throwable) {
          Log.w(TAG, "Error rescheduling sync: ${e.message}")
        }
        try {
          if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (_: Throwable) {}
        pendingResult.finish()
      }
    }
  }
}
