package com.example.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.AppVisibilityTracker
import com.example.R
import com.example.data.model.Order
import com.example.service.InboxNotificationItem
import com.example.service.NotificationInboxStore
import com.example.service.NotificationSyncManager
import com.example.service.OrderLifecycleManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ShoppingReminderRcv"

class ShoppingReminderReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val appContext = context.applicationContext
    val pendingResult = goAsync()
    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val wakeLock = try {
      powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Paircart:ReminderWakeLock")?.apply {
        acquire(10_000L)
      }
    } catch (_: Throwable) {
      null
    }

    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
      try {
        withTimeoutOrNull(8_500L) {
          handleReminderTrigger(appContext, intent)
        }
      } catch (e: Throwable) {
        Log.w(TAG, "ShoppingReminderReceiver failed: ${e.message}")
      } finally {
        try {
          if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (_: Throwable) {}
        pendingResult.finish()
      }
    }
  }

  private suspend fun handleReminderTrigger(appContext: Context, intent: Intent?) {
    NotificationSyncManager.ensureFirebaseInitialized(appContext)
    val currentUser = try {
      Firebase.auth.currentUser
    } catch (_: Throwable) {
      null
    }

    if (currentUser == null) {
      NotificationHelper.cancelShoppingAlarm(appContext)
      return
    }

    val minutesLater = (intent?.getIntExtra("MINUTES_LATER", 0) ?: 0).takeIf { it > 0 }
      ?: NotificationHelper.getActiveReminderMinutes(appContext)
    if (minutesLater <= 0 || !NotificationHelper.isReminderActive(appContext)) {
      return
    }

    var liveCount: Int? = null
    var liveSummary: String? = null

    try {
      val dbId = appContext.getString(R.string.firestore_database_id)
      val db = FirebaseFirestore.getInstance(dbId)
      val query = db.collection("orders")
        .whereEqualTo("buddyId", currentUser.uid)

      val snapshot = try {
        query.get(Source.SERVER).await()
      } catch (_: Exception) {
        query.get(Source.DEFAULT).await()
      }

      val unboughtItems = snapshot.documents
        .map { Order.fromDocument(it) }
        .filter { it.status == "accepted" }
        .flatMap { it.typedItems }
        .filter { !it.isBought && !it.isUnavailable }

      liveCount = unboughtItems.size
      liveSummary = unboughtItems
        .map { it.name.trim() }
        .filter { it.isNotBlank() }
        .take(3)
        .joinToString(", ")
    } catch (e: Exception) {
      Log.w(TAG, "Could not query unbought items on server: ${e.message}")
    }

    if (liveCount != null && liveCount == 0) {
      // All items have been bought or completed; automatically stop repeating reminder
      NotificationHelper.cancelShoppingAlarm(appContext)
      return
    }

    val effectiveCount = liveCount ?: (intent?.getIntExtra("ITEMS_COUNT", 0) ?: 0).takeIf { it > 0 }
      ?: NotificationHelper.getSavedReminderCount(appContext)

    if (effectiveCount <= 0) {
      NotificationHelper.cancelShoppingAlarm(appContext)
      return
    }

    val effectiveSummary = liveSummary?.takeIf { it.isNotBlank() }
      ?: intent?.getStringExtra("ITEMS_SUMMARY")?.takeIf { it.isNotBlank() }
      ?: NotificationHelper.getSavedReminderSummary(appContext).ifBlank { "your partner's items" }

    val title = "Shopping Reminder"
    val message = "Time to head home! Did you pick up your partner's $effectiveCount item(s) ($effectiveSummary)? You will be reminded every $minutesLater mins until all items are bought."
    val nowMs = System.currentTimeMillis()
    val inForeground = AppVisibilityTracker.isInForeground

    NotificationHelper.showNotification(
      context = appContext,
      title = title,
      message = message,
      notificationId = 2002,
      bypassDebounce = true,
      silent = inForeground
    )
    if (inForeground) {
      NotificationHelper.playCustomNotificationSound(appContext)
    }

    val inboxItem = InboxNotificationItem(
      id = "shopping_rem_${nowMs / 60_000L}",
      title = title,
      message = message,
      type = "shopping_reminder",
      orderId = "",
      createdAtMs = nowMs,
      isRead = false,
      isBroadcast = false
    )
    NotificationInboxStore.addOrUpdate(appContext, currentUser.uid, inboxItem)
    if (inForeground) {
      OrderLifecycleManager.emitForegroundEvent(currentUser.uid, inboxItem)
    }

    // Re-schedule next reminder at the same interval until all items are purchased
    NotificationHelper.scheduleShoppingAlarm(
      context = appContext,
      minutesLater = minutesLater,
      itemsSummary = effectiveSummary,
      itemsCount = effectiveCount
    )
  }
}
