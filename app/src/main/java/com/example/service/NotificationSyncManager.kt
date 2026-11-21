package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.AppVisibilityTracker
import com.example.R
import com.example.data.model.NotificationData
import com.example.data.model.Order
import com.example.util.NotificationHelper
import com.example.util.configureAppCheck
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TAG = "NotificationSyncManager"

object NotificationDeduplicator {
  fun canonicalKey(notif: NotificationData): String {
    return when {
      notif.orderId.isNotBlank() && notif.type == "new_order" -> "new_order_${notif.orderId}"
      notif.orderId.isNotBlank() && notif.type == "order_accepted" -> "accepted_${notif.orderId}"
      notif.orderId.isNotBlank() && notif.type == "order_completed" -> "completed_${notif.orderId}"
      notif.orderId.isNotBlank() && notif.type == "order_rejected" -> "rejected_${notif.orderId}"
      notif.orderId.isNotBlank() && notif.type == "order_expired" -> "expired_${notif.orderId}"
      notif.orderId.isNotBlank() && notif.type == "order_incomplete" -> "incomplete_${notif.orderId}"
      else -> "msg_${notif.id}"
    }
  }

  @Synchronized
  fun tryMarkHandled(context: Context, uid: String, key: String): Boolean {
    val prefs = context.applicationContext.getSharedPreferences(
      "orderbuddy_prefs_$uid",
      Context.MODE_PRIVATE
    )
    val prefKey = "notif_seen_$key"
    if (prefs.getBoolean(prefKey, false)) {
      return false
    }
    prefs.edit().putBoolean(prefKey, true).commit()
    return true
  }

  @Synchronized
  fun unmarkHandled(context: Context, uid: String, key: String) {
    val prefs = context.applicationContext.getSharedPreferences(
      "orderbuddy_prefs_$uid",
      Context.MODE_PRIVATE
    )
    prefs.edit().remove("notif_seen_$key").commit()
  }

  @Synchronized
  fun isHandled(context: Context, uid: String, key: String): Boolean {
    val prefs = context.applicationContext.getSharedPreferences(
      "orderbuddy_prefs_$uid",
      Context.MODE_PRIVATE
    )
    return prefs.getBoolean("notif_seen_$key", false)
  }

  @Synchronized
  fun markHandled(context: Context, uid: String, key: String) {
    val prefs = context.applicationContext.getSharedPreferences(
      "orderbuddy_prefs_$uid",
      Context.MODE_PRIVATE
    )
    prefs.edit().putBoolean("notif_seen_$key", true).commit()
  }

  @Synchronized
  fun recordOrderEventShown(context: Context, uid: String, eventType: String) {
    val prefs = context.applicationContext.getSharedPreferences(
      "orderbuddy_prefs_$uid",
      Context.MODE_PRIVATE
    )
    prefs.edit().putLong("last_event_ts_$eventType", System.currentTimeMillis()).commit()
  }

  @Synchronized
  fun wasRecentOrderEventShown(context: Context, uid: String, eventType: String, windowMs: Long = 90_000L): Boolean {
    val prefs = context.applicationContext.getSharedPreferences(
      "orderbuddy_prefs_$uid",
      Context.MODE_PRIVATE
    )
    val ts = prefs.getLong("last_event_ts_$eventType", 0L)
    return ts > 0L && (System.currentTimeMillis() - ts) in 0L..windowMs
  }

  @Synchronized
  fun recordLegacyNotifShown(context: Context, uid: String, eventType: String) {
    val prefs = context.applicationContext.getSharedPreferences(
      "orderbuddy_prefs_$uid",
      Context.MODE_PRIVATE
    )
    prefs.edit().putLong("last_legacy_ts_$eventType", System.currentTimeMillis()).commit()
  }

  @Synchronized
  fun wasRecentLegacyNotifShown(context: Context, uid: String, eventType: String, windowMs: Long = 45_000L): Boolean {
    val prefs = context.applicationContext.getSharedPreferences(
      "orderbuddy_prefs_$uid",
      Context.MODE_PRIVATE
    )
    val ts = prefs.getLong("last_legacy_ts_$eventType", 0L)
    return ts > 0L && (System.currentTimeMillis() - ts) in 0L..windowMs
  }
}

object NotificationSyncManager {

  private const val ALARM_REQUEST_CODE = 3001
  internal const val JOB_ID_PERIODIC = 3002
  private const val JOB_ID_QUICK = 3003
  private const val JOB_ID_IMMEDIATE = 3004
  private const val POLL_INTERVAL_MS = 10_000L
  private const val QUICK_JOB_MIN_LATENCY_MS = 8_000L
  private const val QUICK_JOB_DEADLINE_MS = 15_000L
  private const val JOB_INTERVAL_MS = 15 * 60 * 1000L

  fun ensureFirebaseInitialized(context: Context) {
    val appContext = context.applicationContext
    try {
      com.example.util.SecureFirebaseVault.ensureFirebaseInitialized(appContext)
      configureAppCheck(appContext, null)
    } catch (e: Throwable) {
      Log.w(TAG, "ensureFirebaseInitialized warning: ${e.message}")
    }
  }

  fun scheduleBackgroundSync(context: Context) {
    val appContext = context.applicationContext
    ensureFirebaseInitialized(appContext)
    if (Firebase.auth.currentUser == null) {
      cancelBackgroundSync(appContext)
      return
    }
    scheduleNextAlarm(appContext)
    scheduleQuickPollJob(appContext)
    schedulePeriodicJob(appContext)
    OrderLifecycleManager.rescheduleFromCache(appContext)
  }

  fun scheduleNextAlarm(context: Context) {
    val appContext = context.applicationContext
    try {
      if (Firebase.auth.currentUser == null) return
      val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
      val intent = Intent(appContext, NotificationPollReceiver::class.java).apply {
        setPackage(appContext.packageName)
      }
      val pendingIntent = PendingIntent.getBroadcast(
        appContext,
        ALARM_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      val triggerAt = System.currentTimeMillis() + POLL_INTERVAL_MS
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
      } else {
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
      }
    } catch (e: Throwable) {
      Log.w(TAG, "scheduleNextAlarm failed: ${e.message}")
    }
  }

  fun scheduleQuickPollJob(context: Context) {
    val appContext = context.applicationContext
    try {
      if (Firebase.auth.currentUser == null) return
      val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
      val component = ComponentName(appContext, NotificationJobService::class.java)
      val jobInfo = JobInfo.Builder(JOB_ID_QUICK, component)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setMinimumLatency(QUICK_JOB_MIN_LATENCY_MS)
        .setOverrideDeadline(QUICK_JOB_DEADLINE_MS)
        .build()
      scheduler.schedule(jobInfo)
    } catch (e: Throwable) {
      Log.w(TAG, "scheduleQuickPollJob failed: ${e.message}")
    }
  }

  fun triggerImmediateJob(context: Context) {
    val appContext = context.applicationContext
    try {
      if (Firebase.auth.currentUser == null) return
      val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
      val component = ComponentName(appContext, NotificationJobService::class.java)
      val jobInfo = JobInfo.Builder(JOB_ID_IMMEDIATE, component)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setOverrideDeadline(1_000L)
        .build()
      scheduler.schedule(jobInfo)
    } catch (e: Throwable) {
      Log.w(TAG, "triggerImmediateJob failed: ${e.message}")
    }
  }

  private fun schedulePeriodicJob(context: Context) {
    val appContext = context.applicationContext
    try {
      val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
      val existing = scheduler.allPendingJobs.any { it.id == JOB_ID_PERIODIC }
      if (existing) return

      val component = ComponentName(appContext, NotificationJobService::class.java)
      val jobInfo = JobInfo.Builder(JOB_ID_PERIODIC, component)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPeriodic(JOB_INTERVAL_MS)
        .setPersisted(true)
        .build()
      scheduler.schedule(jobInfo)
    } catch (e: Throwable) {
      Log.w(TAG, "schedulePeriodicJob failed: ${e.message}")
    }
  }

  fun cancelBackgroundSync(context: Context) {
    val appContext = context.applicationContext
    try {
      val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
      val intent = Intent(appContext, NotificationPollReceiver::class.java).apply {
        setPackage(appContext.packageName)
      }
      val pendingIntent = PendingIntent.getBroadcast(
        appContext,
        ALARM_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
      )
      if (pendingIntent != null && alarmManager != null) {
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
      }
    } catch (e: Throwable) {
      Log.w(TAG, "cancel alarm failed: ${e.message}")
    }

    try {
      val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
      scheduler?.cancel(JOB_ID_PERIODIC)
      scheduler?.cancel(JOB_ID_QUICK)
      scheduler?.cancel(JOB_ID_IMMEDIATE)
    } catch (e: Throwable) {
      Log.w(TAG, "cancel job failed: ${e.message}")
    }
  }

  private suspend fun Query.getServerFirst(): QuerySnapshot {
    return try {
      get(Source.SERVER).await()
    } catch (_: Exception) {
      get(Source.DEFAULT).await()
    }
  }

  private fun isFreshTimestamp(timestamp: com.google.firebase.Timestamp?, windowMs: Long = 15 * 60 * 1000L): Boolean {
    val timeMs = timestamp?.toDate()?.time ?: return true
    val ageMs = System.currentTimeMillis() - timeMs
    return ageMs <= windowMs && ageMs >= -5 * 60 * 1000L
  }

  suspend fun fetchAndShowPendingNotifications(context: Context): Boolean = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    ensureFirebaseInitialized(appContext)

    // When the app UI is actively in foreground, OrderViewModel's realtime listener handles notifications.
    if (AppVisibilityTracker.isInForeground) {
      return@withContext true
    }

    val currentUser = try {
      Firebase.auth.currentUser
    } catch (_: Throwable) {
      null
    } ?: return@withContext false

    val uid = currentUser.uid
    val db = try {
      val dbId = appContext.getString(R.string.firestore_database_id)
      FirebaseFirestore.getInstance(dbId)
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to get named Firestore instance", e)
      return@withContext false
    }

    var allSucceeded = true

    val prefs = appContext.getSharedPreferences("orderbuddy_prefs_$uid", Context.MODE_PRIVATE)
    val partnerOrdersBaseline = prefs.getBoolean("partner_orders_baseline_done", false)
    val myOrdersBaseline = prefs.getBoolean("my_orders_baseline_done", false)

    // Fetch personal notifications, global ALL broadcasts, and fallback order queries in parallel
    val (notifSnapshot, broadcastSnapshot, incomingSnap, myOrdersSnap) = coroutineScope {
      val notifDeferred = async {
        try {
          db.collection("notifications").whereEqualTo("targetUid", uid).getServerFirst()
        } catch (e: Exception) {
          Log.w(TAG, "fetch notifications query error: ${e.message}")
          null
        }
      }
      val broadcastDeferred = async {
        try {
          db.collection("notifications").whereEqualTo("targetUid", "ALL").limit(30).getServerFirst()
        } catch (e: Exception) {
          Log.w(TAG, "fetch broadcast notifications query error: ${e.message}")
          null
        }
      }
      val incomingDeferred = async {
        if (!partnerOrdersBaseline) null
        else try {
          db.collection("orders").whereEqualTo("buddyId", uid).getServerFirst()
        } catch (e: Exception) {
          Log.w(TAG, "fetch partner orders query error: ${e.message}")
          null
        }
      }
      val myOrdersDeferred = async {
        if (!myOrdersBaseline) null
        else try {
          db.collection("orders").whereEqualTo("userId", uid).getServerFirst()
        } catch (e: Exception) {
          Log.w(TAG, "fetch my orders query error: ${e.message}")
          null
        }
      }
      listOf(notifDeferred.await(), broadcastDeferred.await(), incomingDeferred.await(), myOrdersDeferred.await())
    }

    // 1. Primary path: pending notification documents targeted to this user
    if (notifSnapshot != null) {
      try {
        val notifs = notifSnapshot.documents.mapNotNull { doc ->
          doc.toObject(NotificationData::class.java)?.copy(id = doc.id)
        }
        val completedOrderIdsInBatch = notifs
          .filter { it.type == "order_completed" && it.orderId.isNotBlank() }
          .map { it.orderId }
          .toSet()

        for (notif in notifs) {
          val docKey = "msg_${notif.id}"
          val canonicalKey = NotificationDeduplicator.canonicalKey(notif)
          val eventType = when (notif.type) {
            "new_order" -> "new_order"
            "order_accepted" -> "accepted"
            "order_completed" -> "completed"
            "order_rejected" -> "rejected"
            "order_incomplete" -> "incomplete"
            "order_expired" -> "expired"
            else -> ""
          }

          if (notif.type == "order_accepted" && notif.orderId in completedOrderIdsInBatch) {
            NotificationDeduplicator.markHandled(appContext, uid, canonicalKey)
            NotificationDeduplicator.markHandled(appContext, uid, docKey)
            try { db.collection("notifications").document(notif.id).delete().await() } catch (_: Exception) {}
            continue
          }

          // Suppress duplicate legacy notifications (blank orderId from older sender app) if orders listener already notified
          if (notif.orderId.isBlank() && eventType.isNotBlank() &&
            NotificationDeduplicator.wasRecentOrderEventShown(appContext, uid, eventType)
          ) {
            NotificationDeduplicator.markHandled(appContext, uid, docKey)
            try { db.collection("notifications").document(notif.id).delete().await() } catch (_: Exception) {}
            continue
          }

          if (!NotificationDeduplicator.isHandled(appContext, uid, docKey) &&
            NotificationDeduplicator.tryMarkHandled(appContext, uid, canonicalKey)
          ) {
            if (notif.type == "order_completed" && notif.orderId.isNotBlank()) {
              NotificationDeduplicator.markHandled(appContext, uid, "accepted_${notif.orderId}")
            }
            val notifId = when {
              notif.type == "partner_connected" -> "partner_connected_${notif.senderUid.ifBlank { notif.id }}".hashCode()
              notif.orderId.isNotBlank() -> canonicalKey.hashCode()
              eventType.isNotBlank() -> "legacy_$eventType".hashCode()
              else -> canonicalKey.hashCode()
            }
            if (notif.message.isNotBlank()) {
              NotificationInboxStore.addOrUpdate(
                context = appContext,
                uid = uid,
                item = InboxNotificationItem(
                  id = canonicalKey,
                  title = notif.title.ifBlank { "Paircart" },
                  message = notif.message,
                  type = notif.type,
                  orderId = notif.orderId,
                  createdAtMs = notif.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                  isRead = false,
                  isBroadcast = false
                )
              )
            }
            val delivered = if (notif.message.isNotBlank()) {
              NotificationHelper.showNotification(
                context = appContext,
                title = notif.title.ifBlank { "Paircart" },
                message = notif.message,
                notificationId = notifId
              )
            } else {
              true
            }
            if (!delivered) {
              NotificationDeduplicator.unmarkHandled(appContext, uid, canonicalKey)
              allSucceeded = false
              continue
            }
            if (eventType.isNotBlank()) {
              NotificationDeduplicator.recordOrderEventShown(appContext, uid, eventType)
              if (notif.orderId.isBlank()) {
                NotificationDeduplicator.recordLegacyNotifShown(appContext, uid, eventType)
              }
            }
            NotificationDeduplicator.markHandled(appContext, uid, docKey)
          } else {
            NotificationDeduplicator.markHandled(appContext, uid, docKey)
          }

          if (notif.type == "partner_connected" && notif.senderNumericId.isNotBlank() && notif.senderUid.isNotBlank()) {
            if (prefs.getString("partner_uid", "").isNullOrBlank()) {
              prefs.edit()
                .putString("partner_numeric_id", notif.senderNumericId)
                .putString("partner_uid", notif.senderUid)
                .putString("partner_name", notif.senderName)
                .commit()
            }
          }

          try {
            db.collection("notifications").document(notif.id).delete().await()
          } catch (delErr: Exception) {
            Log.w(TAG, "Failed to clear notification ${notif.id}: ${delErr.message}")
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "fetchAndShowPendingNotifications notifications error: ${e.message}")
        allSucceeded = false
      }
    } else {
      allSucceeded = false
    }

    // 1b. Admin Broadcast notifications (targetUid == "ALL"): deliver once per user, never delete from Firestore
    if (broadcastSnapshot != null) {
      try {
        val broadcasts = broadcastSnapshot.documents.mapNotNull { doc ->
          doc.toObject(NotificationData::class.java)?.copy(id = doc.id)
        }
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
        for (bc in broadcasts) {
          if (bc.message.isBlank()) continue
          val bKey = "broadcast_${bc.id}"
          if (isFreshTimestamp(bc.createdAt, windowMs = sevenDaysMs) &&
            NotificationDeduplicator.tryMarkHandled(appContext, uid, bKey)
          ) {
            val shown = NotificationHelper.showNotification(
              context = appContext,
              title = bc.title.ifBlank { "Paircart Notice" },
              message = bc.message,
              notificationId = bKey.hashCode()
            )
            if (!shown) {
              NotificationDeduplicator.unmarkHandled(appContext, uid, bKey)
              allSucceeded = false
            }
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "fetchAndShowPendingNotifications broadcast error: ${e.message}")
      }
    }

    // 2. Fallback path: detect recent unseen order updates directly from orders collection on server
    try {
      if (incomingSnap != null) {
        for (doc in incomingSnap.documents) {
          val order = Order.fromDocument(doc)
          val key = "new_order_${order.id}"
          if ((order.status == "pending" || order.status == "accepted") &&
            isFreshTimestamp(order.createdAt)
          ) {
            if (NotificationDeduplicator.wasRecentLegacyNotifShown(appContext, uid, "new_order")) {
              NotificationDeduplicator.markHandled(appContext, uid, key)
              continue
            }
            if (NotificationDeduplicator.tryMarkHandled(appContext, uid, key)) {
              val sender = order.userName.ifBlank { "Partner" }
              val count = order.typedItems.size
              val shown = NotificationHelper.showNotification(
                context = appContext,
                title = "New Order from $sender",
                message = "$sender sent a new order ${order.orderNumber} with $count item(s).",
                notificationId = key.hashCode()
              )
              if (!shown) {
                NotificationDeduplicator.unmarkHandled(appContext, uid, key)
                allSucceeded = false
              } else {
                NotificationDeduplicator.recordOrderEventShown(appContext, uid, "new_order")
                NotificationInboxStore.addOrUpdate(
                  context = appContext,
                  uid = uid,
                  item = InboxNotificationItem(
                    id = key,
                    title = "New Order from $sender",
                    message = "$sender sent a new order ${order.orderNumber} with $count item(s).",
                    type = "new_order",
                    orderId = order.id,
                    createdAtMs = order.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                    isRead = false,
                    isBroadcast = false
                  )
                )
              }
            }
          } else {
            NotificationDeduplicator.markHandled(appContext, uid, key)
          }
        }
      }

      if (myOrdersSnap != null) {
        for (doc in myOrdersSnap.documents) {
          val order = Order.fromDocument(doc)
          val isFresh = isFreshTimestamp(order.updatedAt ?: order.createdAt)
          if (order.status == "completed") {
            NotificationDeduplicator.markHandled(appContext, uid, "accepted_${order.id}")
            val key = "completed_${order.id}"
            if (isFresh) {
              if (NotificationDeduplicator.wasRecentLegacyNotifShown(appContext, uid, "completed")) {
                NotificationDeduplicator.markHandled(appContext, uid, key)
                continue
              }
              if (NotificationDeduplicator.tryMarkHandled(appContext, uid, key)) {
                val shown = NotificationHelper.showNotification(
                  context = appContext,
                  title = "Order Completed",
                  message = "Order ${order.orderNumber} was completed by partner! Total: ৳${String.format(Locale.US, "%.2f", order.totalCost)}",
                  notificationId = key.hashCode()
                )
                if (!shown) {
                  NotificationDeduplicator.unmarkHandled(appContext, uid, key)
                  allSucceeded = false
                } else {
                  NotificationDeduplicator.recordOrderEventShown(appContext, uid, "completed")
                  NotificationInboxStore.addOrUpdate(
                    context = appContext,
                    uid = uid,
                    item = InboxNotificationItem(
                      id = key,
                      title = "Order Completed",
                      message = "Order ${order.orderNumber} was completed by partner! Total: ৳${String.format(Locale.US, "%.2f", order.totalCost)}",
                      type = "order_completed",
                      orderId = order.id,
                      createdAtMs = (order.updatedAt ?: order.createdAt)?.toDate()?.time ?: System.currentTimeMillis(),
                      isRead = false,
                      isBroadcast = false
                    )
                  )
                }
              }
            } else {
              NotificationDeduplicator.markHandled(appContext, uid, key)
            }
          } else if (order.status == "accepted") {
            val key = "accepted_${order.id}"
            if (isFresh) {
              if (NotificationDeduplicator.wasRecentLegacyNotifShown(appContext, uid, "accepted")) {
                NotificationDeduplicator.markHandled(appContext, uid, key)
                continue
              }
              if (NotificationDeduplicator.tryMarkHandled(appContext, uid, key)) {
                val durInfo = if (order.shoppingDurationMinutes > 0) " (Within ${order.shoppingDurationMinutes}m)" else ""
                val shown = NotificationHelper.showNotification(
                  context = appContext,
                  title = "Order Accepted",
                  message = "Partner accepted your order ${order.orderNumber}$durInfo.",
                  notificationId = key.hashCode()
                )
                if (!shown) {
                  NotificationDeduplicator.unmarkHandled(appContext, uid, key)
                  allSucceeded = false
                } else {
                  NotificationDeduplicator.recordOrderEventShown(appContext, uid, "accepted")
                  NotificationInboxStore.addOrUpdate(
                    context = appContext,
                    uid = uid,
                    item = InboxNotificationItem(
                      id = key,
                      title = "Order Accepted",
                      message = "Partner accepted your order ${order.orderNumber}$durInfo.",
                      type = "order_accepted",
                      orderId = order.id,
                      createdAtMs = (order.updatedAt ?: order.createdAt)?.toDate()?.time ?: System.currentTimeMillis(),
                      isRead = false,
                      isBroadcast = false
                    )
                  )
                }
              }
            } else {
              NotificationDeduplicator.markHandled(appContext, uid, key)
            }
          } else if (order.status == "rejected") {
            val key = "rejected_${order.id}"
            if (isFresh && NotificationDeduplicator.tryMarkHandled(appContext, uid, key)) {
              val reasonText = if (order.rejectionReason.isNotBlank()) " Reason: ${order.rejectionReason}" else ""
              val msg = "Partner rejected your order ${order.orderNumber}.$reasonText"
              val shown = NotificationHelper.showNotification(
                context = appContext,
                title = "Order Rejected",
                message = msg,
                notificationId = key.hashCode()
              )
              if (!shown) {
                NotificationDeduplicator.unmarkHandled(appContext, uid, key)
              } else {
                NotificationInboxStore.addOrUpdate(
                  context = appContext,
                  uid = uid,
                  item = InboxNotificationItem(
                    id = key,
                    title = "Order Rejected",
                    message = msg,
                    type = "order_rejected",
                    orderId = order.id,
                    createdAtMs = (order.updatedAt ?: order.createdAt)?.toDate()?.time ?: System.currentTimeMillis(),
                    isRead = false,
                    isBroadcast = false
                  )
                )
              }
            } else if (!isFresh) {
              NotificationDeduplicator.markHandled(appContext, uid, key)
            }
          } else if (order.status == "incomplete") {
            val key = "incomplete_${order.id}"
            if (isFresh && NotificationDeduplicator.tryMarkHandled(appContext, uid, key)) {
              val reasonText = if (order.incompleteReason.isNotBlank()) " Reason: ${order.incompleteReason}" else ""
              val msg = "Order ${order.orderNumber} marked Incomplete by partner.$reasonText"
              val shown = NotificationHelper.showNotification(
                context = appContext,
                title = "Order Incomplete",
                message = msg,
                notificationId = key.hashCode()
              )
              if (!shown) {
                NotificationDeduplicator.unmarkHandled(appContext, uid, key)
              } else {
                NotificationInboxStore.addOrUpdate(
                  context = appContext,
                  uid = uid,
                  item = InboxNotificationItem(
                    id = key,
                    title = "Order Incomplete",
                    message = msg,
                    type = "order_incomplete",
                    orderId = order.id,
                    createdAtMs = (order.updatedAt ?: order.createdAt)?.toDate()?.time ?: System.currentTimeMillis(),
                    isRead = false,
                    isBroadcast = false
                  )
                )
              }
            } else if (!isFresh) {
              NotificationDeduplicator.markHandled(appContext, uid, key)
            }
          } else if (order.status == "expired") {
            val key = OrderLifecycleManager.expiredKey(order.id)
            if (isFresh) {
              OrderLifecycleManager.notifyCreatorExpiredIfNeeded(appContext, uid, order)
            } else {
              NotificationDeduplicator.markHandled(appContext, uid, key)
            }
          }
        }
      }

      // Sync local lifecycle alarms & evaluate 5m reminders, 30m auto-expiry, 15m pre-deadline, and overdue using already-fetched lists (zero extra queries!)
      val fetchedMyOrders = myOrdersSnap?.documents?.map { Order.fromDocument(it) }.orEmpty()
      val fetchedPartnerOrders = incomingSnap?.documents?.map { Order.fromDocument(it) }.orEmpty()
      if (fetchedMyOrders.isNotEmpty() || fetchedPartnerOrders.isNotEmpty()) {
        val toExpire = OrderLifecycleManager.syncOrdersAndScheduleAlarms(
          context = appContext,
          uid = uid,
          myOrders = fetchedMyOrders,
          partnerOrders = fetchedPartnerOrders
        )
        for (expOrder in toExpire) {
          OrderLifecycleManager.expireOrderIfPending(db, expOrder.id)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "fetchAndShowPendingNotifications orders fallback error: ${e.message}")
      allSucceeded = false
    }

    allSucceeded
  }
}
