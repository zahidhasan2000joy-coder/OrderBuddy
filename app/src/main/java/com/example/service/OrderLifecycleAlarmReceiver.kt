package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.R
import com.example.data.model.Order
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

private const val TAG = "OrderLifecycleRcv"

class OrderLifecycleAlarmReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val orderId = intent?.getStringExtra(OrderLifecycleManager.EXTRA_ORDER_ID).orEmpty()
    val milestoneCode = intent?.getStringExtra(OrderLifecycleManager.EXTRA_MILESTONE).orEmpty()
    if (orderId.isBlank() || milestoneCode.isBlank()) return

    val appContext = context.applicationContext
    val pendingResult = goAsync()
    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val wakeLock = try {
      powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Paircart:LifecycleWakeLock")?.apply {
        acquire(10_000L)
      }
    } catch (_: Throwable) {
      null
    }

    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
      try {
        withTimeoutOrNull(8_500L) {
          handleMilestoneAlarm(appContext, orderId, milestoneCode, intent?.getStringExtra(OrderLifecycleManager.EXTRA_UID).orEmpty())
        }
      } catch (e: Throwable) {
        Log.w(TAG, "OrderLifecycleAlarmReceiver failed: ${e.message}")
      } finally {
        try {
          if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (_: Throwable) {}
        pendingResult.finish()
      }
    }
  }

  private suspend fun handleMilestoneAlarm(
    appContext: Context,
    orderId: String,
    milestoneCode: String,
    extraUid: String
  ) {
    NotificationSyncManager.ensureFirebaseInitialized(appContext)
    val currentUser = try {
      Firebase.auth.currentUser
    } catch (_: Throwable) {
      null
    } ?: return

    val uid = extraUid.ifBlank { currentUser.uid }
    if (uid != currentUser.uid) return

    val cached = OrderLifecycleManager.getCachedOrders(appContext, uid).firstOrNull { it.orderId == orderId }
    // Verify latest status with a single-document read (zero polling, only fires at scheduled milestone)
    val db: FirebaseFirestore? = try {
      val dbId = appContext.getString(R.string.firestore_database_id)
      FirebaseFirestore.getInstance(dbId)
    } catch (e: Throwable) {
      Log.w(TAG, "Failed to resolve firestore_database_id: ${e.message}")
      null
    }

    val liveOrder: Order? = try {
      val snap = if (db != null) {
        try {
          db.collection("orders").document(orderId).get(Source.SERVER).await()
        } catch (_: Exception) {
          db.collection("orders").document(orderId).get(Source.DEFAULT).await()
        }
      } else {
        null
      }
      if (snap != null && snap.exists()) Order.fromDocument(snap) else if (db == null) cached?.toFallbackOrder() else null
    } catch (e: Exception) {
      Log.w(TAG, "Single-doc milestone check failed, falling back to cache: ${e.message}")
      cached?.toFallbackOrder()
    }

    if (liveOrder == null) {
      OrderLifecycleManager.cancelAllAlarmsForOrder(appContext, orderId)
      return
    }

    val nowMs = System.currentTimeMillis()
    val isMyOrder = liveOrder.userId == uid

    when (milestoneCode) {
      LifecycleMilestone.PENDING_5M_REMINDER.code -> {
        if (liveOrder.status == "pending" && !isMyOrder) {
          OrderLifecycleManager.notifyReceiverPendingReminderIfNeeded(appContext, uid, liveOrder, nowMs)
        } else {
          OrderLifecycleManager.cancelPendingAlarmsForOrder(appContext, orderId)
        }
      }

      LifecycleMilestone.PENDING_30M_EXPIRE.code -> {
        if (liveOrder.status == "pending" && liveOrder.isExpiredPending(nowMs)) {
          if (db != null) {
            OrderLifecycleManager.expireOrderIfPending(db, orderId)
          }
          if (isMyOrder) {
            OrderLifecycleManager.notifyCreatorExpiredIfNeeded(appContext, uid, liveOrder, nowMs)
          }
        } else if (liveOrder.status == "expired" && isMyOrder) {
          OrderLifecycleManager.notifyCreatorExpiredIfNeeded(appContext, uid, liveOrder, nowMs)
        }
        OrderLifecycleManager.cancelPendingAlarmsForOrder(appContext, orderId)
      }

      LifecycleMilestone.SHOPPING_PRE_15M.code -> {
        if (liveOrder.status == "accepted" && !isMyOrder && liveOrder.remainingItemsCount > 0 && !liveOrder.isOverdue(nowMs)) {
          OrderLifecycleManager.notifyReceiverPreDeadlineIfNeeded(appContext, uid, liveOrder, nowMs)
        } else if (liveOrder.status != "accepted") {
          OrderLifecycleManager.cancelShoppingAlarmsForOrder(appContext, orderId)
        }
      }

      LifecycleMilestone.SHOPPING_OVERDUE.code -> {
        if (liveOrder.status == "accepted" && liveOrder.shoppingDeadlineMs > 0L && nowMs >= liveOrder.shoppingDeadlineMs) {
          OrderLifecycleManager.notifyOverdueIfNeeded(appContext, uid, liveOrder, isMyOrder, nowMs)
        }
        OrderLifecycleManager.cancelShoppingAlarmsForOrder(appContext, orderId)
      }
    }
  }

  private fun CachedLifecycleOrder.toFallbackOrder(): Order = Order(
    id = orderId,
    orderNumber = orderNumber,
    userId = userId,
    userName = userName,
    buddyId = buddyId,
    partnerName = partnerName,
    status = status,
    createdAtMs = createdAtMs,
    acceptedAtMs = acceptedAtMs,
    shoppingDurationMinutes = shoppingDurationMinutes,
    shoppingDeadlineMs = shoppingDeadlineMs
  )
}
