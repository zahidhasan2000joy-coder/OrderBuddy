package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class OrderItemDetail(
  val name: String = "",
  val quantity: Int = 1,
  val notes: String = "",
  val isBought: Boolean = false,
  val isUnavailable: Boolean = false,
  val unavailableReason: String = "",
  val boughtPrice: Double = 0.0,
  val productUrl: String = "",
  val imageUrl: String = "",
  val marketName: String = ""
) {
  val isResolved: Boolean get() = isBought || isUnavailable

  fun toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>(
      "name" to name,
      "quantity" to quantity,
      "notes" to notes,
      "isBought" to isBought,
      "isUnavailable" to isUnavailable,
      "boughtPrice" to boughtPrice
    )
    if (unavailableReason.isNotBlank()) {
      map["unavailableReason"] = unavailableReason
    }
    if (productUrl.isNotBlank()) {
      map["productUrl"] = productUrl
    }
    if (imageUrl.isNotBlank()) {
      map["imageUrl"] = imageUrl
    }
    if (marketName.isNotBlank()) {
      map["marketName"] = marketName
    }
    return map
  }
}

@Keep
@IgnoreExtraProperties
data class Order(
  @DocumentId val id: String = "",
  val orderNumber: String = "",
  val userId: String = "",
  val userName: String = "",
  val buddyId: String = "",
  val partnerName: String = "",
  val status: String = "pending", // "pending", "accepted", "completed", "cancelled", "rejected", "expired", "incomplete"
  val items: List<Map<String, Any>> = emptyList(),
  val totalCost: Double = 0.0,
  val formattedDate: String = "",
  val formattedTime: String = "",
  val purchaseMarketName: String = "",
  val createdAtMs: Long = 0L,
  val acceptedAtMs: Long = 0L,
  val shoppingDurationMinutes: Int = 0,
  val shoppingDeadlineMs: Long = 0L,
  val rejectionReason: String = "",
  val incompleteReason: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val typedItems: List<OrderItemDetail>
    get() = items.map { map ->
      OrderItemDetail(
        name = map["name"] as? String ?: "",
        quantity = (map["quantity"] as? Number)?.toInt() ?: 1,
        notes = map["notes"] as? String ?: "",
        isBought = map["isBought"] as? Boolean ?: false,
        isUnavailable = map["isUnavailable"] as? Boolean ?: false,
        unavailableReason = map["unavailableReason"] as? String ?: "",
        boughtPrice = (map["boughtPrice"] as? Number)?.toDouble() ?: 0.0,
        productUrl = map["productUrl"] as? String ?: "",
        imageUrl = map["imageUrl"] as? String ?: "",
        marketName = map["marketName"] as? String ?: ""
      )
    }

  val effectiveCreatedAtMs: Long
    get() = if (createdAtMs > 0L) createdAtMs else (createdAt?.toDate()?.time ?: 0L)

  val allItemsBought: Boolean
    get() = typedItems.isNotEmpty() && typedItems.all { it.isBought }

  val allItemsResolved: Boolean
    get() = typedItems.isNotEmpty() && typedItems.all { it.isBought || it.isUnavailable }

  val hasBoughtItems: Boolean
    get() = typedItems.any { it.isBought }

  val remainingItemsCount: Int
    get() = typedItems.count { !it.isBought && !it.isUnavailable }

  val computedTotalCost: Double
    get() = typedItems.filter { it.isBought }.sumOf { it.boughtPrice }

  fun isExpiredPending(nowMs: Long = System.currentTimeMillis()): Boolean {
    if (status == "expired") return true
    val startMs = effectiveCreatedAtMs
    return status == "pending" && startMs > 0L && nowMs >= startMs + EXPIRE_WINDOW_MS
  }

  fun isPendingResponseLate(nowMs: Long = System.currentTimeMillis()): Boolean {
    val startMs = effectiveCreatedAtMs
    if (status != "pending" || startMs <= 0L) return false
    val elapsed = nowMs - startMs
    return elapsed > ACCEPT_WINDOW_MS && elapsed < EXPIRE_WINDOW_MS
  }

  fun isOverdue(nowMs: Long = System.currentTimeMillis()): Boolean {
    return status == "accepted" && shoppingDeadlineMs > 0L && nowMs > shoppingDeadlineMs
  }

  fun effectiveStatus(nowMs: Long = System.currentTimeMillis()): String {
    return when {
      isExpiredPending(nowMs) -> "expired"
      isOverdue(nowMs) -> "overdue"
      else -> status
    }
  }

  fun preDeadlineReminderAtMs(): Long {
    if (shoppingDeadlineMs <= 0L || shoppingDurationMinutes <= 0) return 0L
    return if (shoppingDurationMinutes > 15) {
      shoppingDeadlineMs - PRE_DEADLINE_REMINDER_MS
    } else {
      val baseMs = if (acceptedAtMs > 0L) acceptedAtMs else shoppingDeadlineMs - (shoppingDurationMinutes * 60_000L)
      baseMs + (shoppingDurationMinutes * 60_000L / 2L)
    }
  }

  companion object {
    const val PENDING_REMINDER_MS = 5 * 60_000L
    const val ACCEPT_WINDOW_MS = 15 * 60_000L
    const val EXPIRE_WINDOW_MS = 30 * 60_000L
    const val PRE_DEADLINE_REMINDER_MS = 15 * 60_000L

    fun fromDocument(doc: com.google.firebase.firestore.DocumentSnapshot): Order {
      val data = doc.data ?: return Order(id = doc.id)
      @Suppress("UNCHECKED_CAST")
      val rawItems = data["items"] as? List<Map<String, Any>> ?: emptyList()
      return Order(
        id = doc.id,
        orderNumber = data["orderNumber"] as? String ?: "",
        userId = data["userId"] as? String ?: "",
        userName = data["userName"] as? String ?: "",
        buddyId = data["buddyId"] as? String ?: "",
        partnerName = data["partnerName"] as? String ?: "",
        status = data["status"] as? String ?: "pending",
        items = rawItems,
        totalCost = (data["totalCost"] as? Number)?.toDouble() ?: 0.0,
        formattedDate = data["formattedDate"] as? String ?: "",
        formattedTime = data["formattedTime"] as? String ?: "",
        purchaseMarketName = data["purchaseMarketName"] as? String ?: "",
        createdAtMs = (data["createdAtMs"] as? Number)?.toLong() ?: 0L,
        acceptedAtMs = (data["acceptedAtMs"] as? Number)?.toLong() ?: 0L,
        shoppingDurationMinutes = (data["shoppingDurationMinutes"] as? Number)?.toInt() ?: 0,
        shoppingDeadlineMs = (data["shoppingDeadlineMs"] as? Number)?.toLong() ?: 0L,
        rejectionReason = data["rejectionReason"] as? String ?: "",
        incompleteReason = data["incompleteReason"] as? String ?: "",
        createdAt = data["createdAt"] as? Timestamp,
        updatedAt = data["updatedAt"] as? Timestamp
      )
    }
  }
}

@Keep
@IgnoreExtraProperties
data class MarketPurchase(
  @DocumentId val id: String = "",
  val userId: String = "",
  val orderId: String = "",
  val orderNumber: String = "",
  val requesterId: String = "",
  val itemName: String = "",
  val quantity: Int = 1,
  val notes: String = "",
  val boughtPrice: Double = 0.0,
  val productUrl: String = "",
  val marketSource: String = "manual",
  val marketName: String = "",
  val formattedDate: String = "",
  val formattedTime: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class NotificationData(
  @DocumentId val id: String = "",
  val targetUid: String = "",
  val title: String = "",
  val message: String = "",
  val type: String = "info",
  val orderId: String = "",
  val senderNumericId: String = "",
  val senderUid: String = "",
  val senderName: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)
