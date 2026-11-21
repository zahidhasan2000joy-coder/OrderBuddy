package com.example.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class InboxNotificationItem(
  val id: String,
  val title: String,
  val message: String,
  val type: String = "info",
  val orderId: String = "",
  val createdAtMs: Long = System.currentTimeMillis(),
  val isRead: Boolean = false,
  val isBroadcast: Boolean = false
)

object NotificationInboxStore {
  private const val MAX_ITEMS = 80

  private fun prefs(context: Context, uid: String) =
    context.applicationContext.getSharedPreferences("orderbuddy_notif_inbox_$uid", Context.MODE_PRIVATE)

  @Synchronized
  fun getDismissedBroadcastIds(context: Context, uid: String): Set<String> {
    return prefs(context, uid).getStringSet("dismissed_broadcast_ids", emptySet()) ?: emptySet()
  }

  @Synchronized
  fun getReadBroadcastIds(context: Context, uid: String): Set<String> {
    return prefs(context, uid).getStringSet("read_broadcast_ids", emptySet()) ?: emptySet()
  }

  @Synchronized
  fun addOrUpdate(context: Context, uid: String, item: InboxNotificationItem) {
    if (uid.isBlank() || item.id.isBlank()) return
    val cleanTitle = com.example.util.InputSanitizer.sanitizeForSubmit(
      item.title.ifBlank { "Paircart" },
      com.example.util.InputSanitizer.MAX_NOTIF_TITLE_LEN,
      allowNewlines = false
    ).ifBlank { "Paircart" }
    val cleanMessage = com.example.util.InputSanitizer.sanitizeForSubmit(
      item.message,
      com.example.util.InputSanitizer.MAX_NOTIF_MESSAGE_LEN,
      allowNewlines = true
    )
    if (cleanMessage.isBlank()) return
    val safeItem = item.copy(
      id = item.id.take(160),
      title = cleanTitle,
      message = cleanMessage,
      type = item.type.take(48),
      orderId = item.orderId.take(128)
    )
    val current = loadRawList(context, uid).toMutableList()
    val existingIdx = current.indexOfFirst { it.id == safeItem.id }
    if (existingIdx >= 0) {
      val prev = current[existingIdx]
      current[existingIdx] = safeItem.copy(
        isRead = prev.isRead || safeItem.isRead,
        createdAtMs = if (safeItem.createdAtMs > 0L) safeItem.createdAtMs else prev.createdAtMs
      )
    } else {
      current.add(0, safeItem)
    }
    val trimmed = current
      .sortedByDescending { it.createdAtMs }
      .take(MAX_ITEMS)
    saveRawList(context, uid, trimmed)
  }

  @Synchronized
  fun getAll(context: Context, uid: String): List<InboxNotificationItem> {
    if (uid.isBlank()) return emptyList()
    return loadRawList(context, uid).sortedByDescending { it.createdAtMs }
  }

  @Synchronized
  fun markRead(context: Context, uid: String, id: String, isBroadcast: Boolean = false) {
    if (uid.isBlank() || id.isBlank()) return
    if (isBroadcast) {
      val readSet = getReadBroadcastIds(context, uid).toMutableSet().apply { add(id) }
      prefs(context, uid).edit().putStringSet("read_broadcast_ids", readSet).apply()
    }
    val current = loadRawList(context, uid).map {
      if (it.id == id) it.copy(isRead = true) else it
    }
    saveRawList(context, uid, current)
  }

  @Synchronized
  fun markAllRead(context: Context, uid: String, broadcastIds: List<String> = emptyList()) {
    if (uid.isBlank()) return
    if (broadcastIds.isNotEmpty()) {
      val readSet = getReadBroadcastIds(context, uid).toMutableSet().apply { addAll(broadcastIds) }
      prefs(context, uid).edit().putStringSet("read_broadcast_ids", readSet).apply()
    }
    val current = loadRawList(context, uid).map { it.copy(isRead = true) }
    saveRawList(context, uid, current)
  }

  @Synchronized
  fun dismiss(context: Context, uid: String, id: String, isBroadcast: Boolean) {
    if (uid.isBlank() || id.isBlank()) return
    if (isBroadcast) {
      val dismissed = getDismissedBroadcastIds(context, uid).toMutableSet().apply { add(id) }
      prefs(context, uid).edit().putStringSet("dismissed_broadcast_ids", dismissed).apply()
    }
    val current = loadRawList(context, uid).filterNot { it.id == id }
    saveRawList(context, uid, current)
  }

  @Synchronized
  fun clearAll(context: Context, uid: String, activeBroadcastIds: List<String> = emptyList()) {
    if (uid.isBlank()) return
    val dismissed = getDismissedBroadcastIds(context, uid).toMutableSet().apply {
      addAll(activeBroadcastIds)
    }
    prefs(context, uid).edit()
      .putStringSet("dismissed_broadcast_ids", dismissed)
      .putString("items_json", "[]")
      .apply()
  }

  private fun loadRawList(context: Context, uid: String): List<InboxNotificationItem> {
    val raw = prefs(context, uid).getString("items_json", "[]") ?: "[]"
    val list = mutableListOf<InboxNotificationItem>()
    try {
      val arr = JSONArray(raw)
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val id = obj.optString("id", "")
        if (id.isBlank()) continue
        list.add(
          InboxNotificationItem(
            id = id,
            title = obj.optString("title", "Paircart"),
            message = obj.optString("message", ""),
            type = obj.optString("type", "info"),
            orderId = obj.optString("orderId", ""),
            createdAtMs = obj.optLong("createdAtMs", 0L),
            isRead = obj.optBoolean("isRead", false),
            isBroadcast = obj.optBoolean("isBroadcast", false)
          )
        )
      }
    } catch (_: Exception) {
    }
    return list
  }

  private fun saveRawList(context: Context, uid: String, items: List<InboxNotificationItem>) {
    val arr = JSONArray()
    items.forEach { item ->
      val obj = JSONObject()
      obj.put("id", item.id)
      obj.put("title", item.title)
      obj.put("message", item.message)
      obj.put("type", item.type)
      obj.put("orderId", item.orderId)
      obj.put("createdAtMs", item.createdAtMs)
      obj.put("isRead", item.isRead)
      obj.put("isBroadcast", item.isBroadcast)
      arr.put(obj)
    }
    prefs(context, uid).edit().putString("items_json", arr.toString()).apply()
  }
}
