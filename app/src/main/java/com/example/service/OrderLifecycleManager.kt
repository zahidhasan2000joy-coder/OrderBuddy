package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.AppVisibilityTracker
import com.example.data.model.Order
import com.example.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val TAG = "OrderLifecycleManager"

enum class LifecycleMilestone(val code: String) {
  PENDING_5M_REMINDER("pending_5m"),
  PENDING_30M_EXPIRE("pending_30m_expire"),
  SHOPPING_PRE_15M("shopping_pre15m"),
  SHOPPING_OVERDUE("shopping_overdue")
}

data class CachedLifecycleOrder(
  val orderId: String,
  val orderNumber: String,
  val userId: String,
  val userName: String,
  val buddyId: String,
  val partnerName: String,
  val status: String,
  val createdAtMs: Long,
  val acceptedAtMs: Long,
  val shoppingDurationMinutes: Int,
  val shoppingDeadlineMs: Long,
  val remainingItemsCount: Int,
  val isMyOrder: Boolean
) {
  fun toJson(): JSONObject = JSONObject().apply {
    put("orderId", orderId)
    put("orderNumber", orderNumber)
    put("userId", userId)
    put("userName", userName)
    put("buddyId", buddyId)
    put("partnerName", partnerName)
    put("status", status)
    put("createdAtMs", createdAtMs)
    put("acceptedAtMs", acceptedAtMs)
    put("shoppingDurationMinutes", shoppingDurationMinutes)
    put("shoppingDeadlineMs", shoppingDeadlineMs)
    put("remainingItemsCount", remainingItemsCount)
    put("isMyOrder", isMyOrder)
  }

  companion object {
    fun fromOrder(order: Order, isMyOrder: Boolean): CachedLifecycleOrder = CachedLifecycleOrder(
      orderId = order.id,
      orderNumber = order.orderNumber,
      userId = order.userId,
      userName = order.userName,
      buddyId = order.buddyId,
      partnerName = order.partnerName,
      status = order.status,
      createdAtMs = order.effectiveCreatedAtMs,
      acceptedAtMs = order.acceptedAtMs,
      shoppingDurationMinutes = order.shoppingDurationMinutes,
      shoppingDeadlineMs = order.shoppingDeadlineMs,
      remainingItemsCount = order.remainingItemsCount,
      isMyOrder = isMyOrder
    )

    fun fromJson(obj: JSONObject): CachedLifecycleOrder = CachedLifecycleOrder(
      orderId = obj.optString("orderId", ""),
      orderNumber = obj.optString("orderNumber", ""),
      userId = obj.optString("userId", ""),
      userName = obj.optString("userName", ""),
      buddyId = obj.optString("buddyId", ""),
      partnerName = obj.optString("partnerName", ""),
      status = obj.optString("status", "pending"),
      createdAtMs = obj.optLong("createdAtMs", 0L),
      acceptedAtMs = obj.optLong("acceptedAtMs", 0L),
      shoppingDurationMinutes = obj.optInt("shoppingDurationMinutes", 0),
      shoppingDeadlineMs = obj.optLong("shoppingDeadlineMs", 0L),
      remainingItemsCount = obj.optInt("remainingItemsCount", 0),
      isMyOrder = obj.optBoolean("isMyOrder", false)
    )
  }
}

object OrderLifecycleManager {

  private const val PREF_NAME_PREFIX = "orderbuddy_lifecycle_"
  private const val KEY_CACHED_ORDERS = "cached_orders"
  const val EXTRA_ORDER_ID = "ORDER_ID"
  const val EXTRA_MILESTONE = "MILESTONE"
  const val EXTRA_UID = "UID"

  private val _foregroundEvents = MutableSharedFlow<Pair<String, InboxNotificationItem>>(extraBufferCapacity = 32)
  val foregroundEvents: SharedFlow<Pair<String, InboxNotificationItem>> = _foregroundEvents.asSharedFlow()

  fun emitForegroundEvent(uid: String, item: InboxNotificationItem) {
    if (uid.isNotBlank()) {
      _foregroundEvents.tryEmit(uid to item)
    }
  }

  private fun prefs(context: Context, uid: String) =
    context.applicationContext.getSharedPreferences("$PREF_NAME_PREFIX$uid", Context.MODE_PRIVATE)

  @Synchronized
  fun getCachedOrders(context: Context, uid: String): List<CachedLifecycleOrder> {
    val raw = prefs(context, uid).getString(KEY_CACHED_ORDERS, "[]") ?: "[]"
    return try {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val item = CachedLifecycleOrder.fromJson(arr.getJSONObject(i))
          if (item.orderId.isNotBlank()) add(item)
        }
      }
    } catch (_: Throwable) {
      emptyList()
    }
  }

  @Synchronized
  private fun saveCachedOrders(context: Context, uid: String, items: List<CachedLifecycleOrder>) {
    val arr = JSONArray()
    items.forEach { arr.put(it.toJson()) }
    prefs(context, uid).edit().putString(KEY_CACHED_ORDERS, arr.toString()).commit()
  }

  fun syncOrdersAndScheduleAlarms(
    context: Context,
    uid: String,
    myOrders: List<Order>,
    partnerOrders: List<Order>,
    nowMs: Long = System.currentTimeMillis()
  ): List<Order> {
    if (uid.isBlank()) return emptyList()
    val appContext = context.applicationContext
    val previousCached = getCachedOrders(appContext, uid)
    val activeEntries = mutableListOf<CachedLifecycleOrder>()
    val ordersToExpireOnServer = mutableListOf<Order>()

    val combined = myOrders.map { it to true } + partnerOrders.map { it to false }
    val activeIds = combined.map { it.first.id }.toSet()

    // Cancel alarms for any orders that disappeared or were deleted
    previousCached.filter { it.orderId !in activeIds }.forEach { old ->
      cancelAllAlarmsForOrder(appContext, old.orderId)
    }

    combined.forEach { (order, isMyOrder) ->
      if (order.id.isBlank()) return@forEach

      when (order.status) {
        "pending" -> {
          val createdMs = order.effectiveCreatedAtMs
          if (createdMs > 0L) {
            val expireAt = createdMs + Order.EXPIRE_WINDOW_MS
            if (nowMs >= expireAt) {
              // 30m passed: cancel alarms, trigger local creator notification, and queue single conditional expire
              cancelPendingAlarmsForOrder(appContext, order.id)
              ordersToExpireOnServer.add(order)
              if (isMyOrder) {
                notifyCreatorExpiredIfNeeded(appContext, uid, order, nowMs)
              }
            } else {
              activeEntries.add(CachedLifecycleOrder.fromOrder(order, isMyOrder))
              // Schedule 30m auto-expiry alarm on both creator & receiver
              scheduleAlarm(appContext, uid, order.id, LifecycleMilestone.PENDING_30M_EXPIRE, expireAt)

              // For receiver: handle 5m pending reminder
              if (!isMyOrder) {
                val reminderAt = createdMs + Order.PENDING_REMINDER_MS
                val remKey = pending5mKey(order.id)
                if (!NotificationDeduplicator.isHandled(appContext, uid, remKey)) {
                  if (nowMs >= reminderAt) {
                    notifyReceiverPendingReminderIfNeeded(appContext, uid, order, nowMs)
                  } else {
                    scheduleAlarm(appContext, uid, order.id, LifecycleMilestone.PENDING_5M_REMINDER, reminderAt)
                  }
                }
              } else {
                cancelMilestoneAlarm(appContext, order.id, LifecycleMilestone.PENDING_5M_REMINDER)
              }
            }
          }
          cancelShoppingAlarmsForOrder(appContext, order.id)
        }

        "accepted" -> {
          cancelPendingAlarmsForOrder(appContext, order.id)
          val deadlineMs = order.shoppingDeadlineMs
          if (deadlineMs > 0L) {
            if (nowMs >= deadlineMs) {
              // Overdue! Notify both creator and receiver locally (zero server writes)
              cancelShoppingAlarmsForOrder(appContext, order.id)
              notifyOverdueIfNeeded(appContext, uid, order, isMyOrder, nowMs)
            } else {
              activeEntries.add(CachedLifecycleOrder.fromOrder(order, isMyOrder))
              // Schedule Overdue alarm for both users
              scheduleAlarm(appContext, uid, order.id, LifecycleMilestone.SHOPPING_OVERDUE, deadlineMs)

              // Schedule 15m pre-deadline reminder for receiver only
              if (!isMyOrder && order.remainingItemsCount > 0) {
                val preAt = order.preDeadlineReminderAtMs()
                val preKey = shoppingPre15mKey(order.id)
                if (preAt > 0L && !NotificationDeduplicator.isHandled(appContext, uid, preKey)) {
                  if (nowMs >= preAt) {
                    notifyReceiverPreDeadlineIfNeeded(appContext, uid, order, nowMs)
                  } else {
                    scheduleAlarm(appContext, uid, order.id, LifecycleMilestone.SHOPPING_PRE_15M, preAt)
                  }
                }
              } else {
                cancelMilestoneAlarm(appContext, order.id, LifecycleMilestone.SHOPPING_PRE_15M)
              }
            }
          } else {
            cancelShoppingAlarmsForOrder(appContext, order.id)
          }
        }

        "expired" -> {
          cancelAllAlarmsForOrder(appContext, order.id)
          if (isMyOrder) {
            val ageSinceExpireMs = if (order.effectiveCreatedAtMs > 0L) {
              nowMs - (order.effectiveCreatedAtMs + Order.EXPIRE_WINDOW_MS)
            } else 0L
            // Notify creator if expired within last 12 hours and not yet notified
            if (ageSinceExpireMs in -60_000L..(12 * 60 * 60_000L)) {
              notifyCreatorExpiredIfNeeded(appContext, uid, order, nowMs)
            }
          }
        }

        else -> {
          // completed, rejected, incomplete, cancelled -> clean up all alarms
          cancelAllAlarmsForOrder(appContext, order.id)
        }
      }
    }

    saveCachedOrders(appContext, uid, activeEntries)
    return ordersToExpireOnServer
  }

  fun rescheduleFromCache(context: Context, nowMs: Long = System.currentTimeMillis()) {
    val uid = try {
      Firebase.auth.currentUser?.uid
    } catch (_: Throwable) {
      null
    } ?: return
    val appContext = context.applicationContext
    val cached = getCachedOrders(appContext, uid)
    cached.forEach { entry ->
      when (entry.status) {
        "pending" -> {
          if (entry.createdAtMs > 0L) {
            val expireAt = entry.createdAtMs + Order.EXPIRE_WINDOW_MS
            if (expireAt > nowMs) {
              scheduleAlarm(appContext, uid, entry.orderId, LifecycleMilestone.PENDING_30M_EXPIRE, expireAt)
            }
            if (!entry.isMyOrder) {
              val remAt = entry.createdAtMs + Order.PENDING_REMINDER_MS
              if (remAt > nowMs && !NotificationDeduplicator.isHandled(appContext, uid, pending5mKey(entry.orderId))) {
                scheduleAlarm(appContext, uid, entry.orderId, LifecycleMilestone.PENDING_5M_REMINDER, remAt)
              }
            }
          }
        }
        "accepted" -> {
          if (entry.shoppingDeadlineMs > nowMs) {
            scheduleAlarm(appContext, uid, entry.orderId, LifecycleMilestone.SHOPPING_OVERDUE, entry.shoppingDeadlineMs)
            if (!entry.isMyOrder && entry.remainingItemsCount > 0) {
              val preAt = if (entry.shoppingDurationMinutes > 15) {
                entry.shoppingDeadlineMs - Order.PRE_DEADLINE_REMINDER_MS
              } else if (entry.shoppingDurationMinutes > 0) {
                val baseMs = if (entry.acceptedAtMs > 0L) entry.acceptedAtMs else entry.shoppingDeadlineMs - (entry.shoppingDurationMinutes * 60_000L)
                baseMs + (entry.shoppingDurationMinutes * 60_000L / 2L)
              } else 0L
              if (preAt > nowMs && !NotificationDeduplicator.isHandled(appContext, uid, shoppingPre15mKey(entry.orderId))) {
                scheduleAlarm(appContext, uid, entry.orderId, LifecycleMilestone.SHOPPING_PRE_15M, preAt)
              }
            }
          }
        }
      }
    }
  }

  fun cancelAllAlarmsForOrder(context: Context, orderId: String) {
    cancelPendingAlarmsForOrder(context, orderId)
    cancelShoppingAlarmsForOrder(context, orderId)
  }

  fun cancelPendingAlarmsForOrder(context: Context, orderId: String) {
    cancelMilestoneAlarm(context, orderId, LifecycleMilestone.PENDING_5M_REMINDER)
    cancelMilestoneAlarm(context, orderId, LifecycleMilestone.PENDING_30M_EXPIRE)
  }

  fun cancelShoppingAlarmsForOrder(context: Context, orderId: String) {
    cancelMilestoneAlarm(context, orderId, LifecycleMilestone.SHOPPING_PRE_15M)
    cancelMilestoneAlarm(context, orderId, LifecycleMilestone.SHOPPING_OVERDUE)
  }

  private fun requestCodeFor(orderId: String, milestone: LifecycleMilestone): Int {
    return ("lifecycle_${milestone.code}_$orderId").hashCode()
  }

  private fun scheduleAlarm(
    context: Context,
    uid: String,
    orderId: String,
    milestone: LifecycleMilestone,
    triggerAtMillis: Long
  ) {
    if (triggerAtMillis <= 0L) return
    val appContext = context.applicationContext
    val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val intent = Intent(appContext, OrderLifecycleAlarmReceiver::class.java).apply {
      setPackage(appContext.packageName)
      putExtra(EXTRA_ORDER_ID, orderId)
      putExtra(EXTRA_MILESTONE, milestone.code)
      putExtra(EXTRA_UID, uid)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      appContext,
      requestCodeFor(orderId, milestone),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    try {
      val canScheduleExact = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> alarmManager.canScheduleExactAlarms()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> true
        else -> false
      }
      if (canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
      } else {
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
      }
    } catch (_: SecurityException) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
          alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
      } catch (_: Throwable) {}
    } catch (e: Throwable) {
      Log.w(TAG, "scheduleAlarm failed: ${e.message}")
    }
  }

  private fun cancelMilestoneAlarm(context: Context, orderId: String, milestone: LifecycleMilestone) {
    val appContext = context.applicationContext
    try {
      val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
      val intent = Intent(appContext, OrderLifecycleAlarmReceiver::class.java).apply {
        setPackage(appContext.packageName)
      }
      val pendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCodeFor(orderId, milestone),
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
      )
      if (pendingIntent != null) {
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
      }
    } catch (_: Throwable) {}
  }

  fun pending5mKey(orderId: String): String = "pending_rem_5_$orderId"
  fun expiredKey(orderId: String): String = "expired_$orderId"
  fun shoppingPre15mKey(orderId: String): String = "shopping_pre15_$orderId"
  fun overdueKey(orderId: String): String = "overdue_$orderId"

  fun notifyReceiverPendingReminderIfNeeded(
    context: Context,
    uid: String,
    order: Order,
    nowMs: Long = System.currentTimeMillis()
  ): Boolean {
    val key = pending5mKey(order.id)
    if (!NotificationDeduplicator.tryMarkHandled(context, uid, key)) return false

    val sender = order.userName.ifBlank { "Partner" }
    val title = "Order Waiting for Response"
    val message = "${order.orderNumber} from $sender is still pending. Please Accept or Reject within 15 mins."

    val posted = postLocalNotificationAndInbox(
      context = context,
      uid = uid,
      key = key,
      type = "order_pending_reminder",
      orderId = order.id,
      title = title,
      message = message,
      nowMs = nowMs
    )
    if (!posted) {
      NotificationDeduplicator.unmarkHandled(context, uid, key)
    }
    return posted
  }

  fun notifyCreatorExpiredIfNeeded(
    context: Context,
    uid: String,
    order: Order,
    nowMs: Long = System.currentTimeMillis()
  ): Boolean {
    val key = expiredKey(order.id)
    if (!NotificationDeduplicator.tryMarkHandled(context, uid, key)) return false

    val partner = order.partnerName.ifBlank { "Partner" }
    val title = "Order Expired"
    val message = "Your order ${order.orderNumber} expired automatically because $partner did not respond within 30 mins."

    val posted = postLocalNotificationAndInbox(
      context = context,
      uid = uid,
      key = key,
      type = "order_expired",
      orderId = order.id,
      title = title,
      message = message,
      nowMs = nowMs
    )
    if (!posted) {
      NotificationDeduplicator.unmarkHandled(context, uid, key)
    }
    return posted
  }

  fun notifyReceiverPreDeadlineIfNeeded(
    context: Context,
    uid: String,
    order: Order,
    nowMs: Long = System.currentTimeMillis()
  ): Boolean {
    val key = shoppingPre15mKey(order.id)
    if (!NotificationDeduplicator.tryMarkHandled(context, uid, key)) return false

    val minsLeft = ((order.shoppingDeadlineMs - nowMs).coerceAtLeast(60_000L) / 60_000L).toInt()
    val remCount = order.remainingItemsCount
    val title = "Shopping Time Reminder"
    val message = "${order.orderNumber}: Only ~$minsLeft min(s) left to finish shopping ($remCount item(s) remaining)!"

    val posted = postLocalNotificationAndInbox(
      context = context,
      uid = uid,
      key = key,
      type = "shopping_pre_deadline",
      orderId = order.id,
      title = title,
      message = message,
      nowMs = nowMs
    )
    if (!posted) {
      NotificationDeduplicator.unmarkHandled(context, uid, key)
    }
    return posted
  }

  fun notifyOverdueIfNeeded(
    context: Context,
    uid: String,
    order: Order,
    isMyOrder: Boolean,
    nowMs: Long = System.currentTimeMillis()
  ): Boolean {
    val key = overdueKey(order.id)
    if (!NotificationDeduplicator.tryMarkHandled(context, uid, key)) return false

    val title = if (isMyOrder) "Order Overdue" else "Shopping Overdue"
    val message = if (isMyOrder) {
      val partner = order.partnerName.ifBlank { "Partner" }
      "Order ${order.orderNumber} with $partner is now Overdue (scheduled ${order.shoppingDurationMinutes} mins passed)."
    } else {
      "Scheduled shopping time (${order.shoppingDurationMinutes} mins) for ${order.orderNumber} has passed! Mark items or tap Unable to Complete."
    }

    val posted = postLocalNotificationAndInbox(
      context = context,
      uid = uid,
      key = key,
      type = "order_overdue",
      orderId = order.id,
      title = title,
      message = message,
      nowMs = nowMs
    )
    if (!posted) {
      NotificationDeduplicator.unmarkHandled(context, uid, key)
    }
    return posted
  }

  private fun postLocalNotificationAndInbox(
    context: Context,
    uid: String,
    key: String,
    type: String,
    orderId: String,
    title: String,
    message: String,
    nowMs: Long
  ): Boolean {
    val inForeground = AppVisibilityTracker.isInForeground
    val shown = NotificationHelper.showNotification(
      context = context,
      title = title,
      message = message,
      notificationId = key.hashCode(),
      bypassDebounce = true,
      silent = inForeground
    )
    if (inForeground) {
      NotificationHelper.playCustomNotificationSound(context)
    }
    val inboxItem = InboxNotificationItem(
      id = key,
      title = title,
      message = message,
      type = type,
      orderId = orderId,
      createdAtMs = nowMs,
      isRead = false,
      isBroadcast = false
    )
    NotificationInboxStore.addOrUpdate(
      context = context,
      uid = uid,
      item = inboxItem
    )
    if (inForeground) {
      emitForegroundEvent(uid, inboxItem)
      return true
    }
    return shown
  }

  suspend fun expireOrderIfPending(db: FirebaseFirestore, orderId: String): Boolean {
    if (orderId.isBlank()) return false
    return try {
      var didExpire = false
      val docRef = db.collection("orders").document(orderId)
      db.runTransaction { tx ->
        val snap = tx.get(docRef)
        if (snap.exists() && snap.getString("status") == "pending") {
          tx.update(
            docRef,
            mapOf(
              "status" to "expired",
              "updatedAt" to FieldValue.serverTimestamp()
            )
          )
          didExpire = true
        }
        null
      }.await()
      didExpire
    } catch (e: Exception) {
      Log.w(TAG, "expireOrderIfPending($orderId) skipped/failed: ${e.message}")
      false
    }
  }

  fun formatRemainingDuration(ms: Long): String {
    val absMins = (kotlin.math.abs(ms) / 60_000L).coerceAtLeast(0L)
    val hrs = absMins / 60L
    val mins = absMins % 60L
    return when {
      hrs > 0 && mins > 0 -> "${hrs}h ${mins}m"
      hrs > 0 -> "${hrs}h"
      absMins == 0L -> "<1m"
      else -> String.format(Locale.US, "%dm", mins)
    }
  }
}
