package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.GiftClaim
import com.example.data.model.MarketPurchase
import com.example.data.model.NotificationData
import com.example.data.model.Order
import com.example.data.model.OrderItemDetail
import com.example.data.model.UserProfile
import com.example.data.util.OperationType
import com.example.data.util.handleFirestoreError
import com.example.util.AdminConfig
import com.example.util.InputSanitizer
import com.example.util.NetworkMonitor
import com.example.util.UrlSecurityHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private const val TAG = "OrderRepository"

data class MarkItemResult(
  val orderCompleted: Boolean,
  val totalCost: Double,
  val orderMarkedIncomplete: Boolean = false
)

data class AppConfig(
  val coinFeatureEnabled: Boolean = true,
  val appEnabled: Boolean = true
)

class OrderRepository(
  private val db: FirebaseFirestore,
  private val appContext: Context? = null
) {

  constructor(context: Context) : this(
    db = try {
      val dbId = context.applicationContext.getString(R.string.firestore_database_id)
      com.google.firebase.firestore.FirebaseFirestore.getInstance(dbId)
    } catch (e: Throwable) {
      com.google.firebase.firestore.FirebaseFirestore.getInstance()
    },
    appContext = context.applicationContext
  )

  private val auth = Firebase.auth

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
  }

  private fun isOnlineIfContextAvailable(): Boolean {
    val ctx = appContext ?: return true
    return NetworkMonitor.isOnlineNow(ctx)
  }

  suspend fun registerUserProfile(email: String, displayName: String): Result<UserProfile> = withContext(Dispatchers.IO) {
    try {
      val uid = requireUserId()
      val cleanEmail = email.trim().take(150)
      val cleanDisplayName = InputSanitizer.sanitizePersonName(displayName).ifBlank { "User" }
      val numericId = UserProfile.generateNumericId(cleanEmail)
      val userRef = db.collection("users").document(numericId)
      val snapshot = userRef.get().await()
      if (snapshot.exists()) {
        val profile = snapshot.toObject(UserProfile::class.java)
          ?: UserProfile(uid = uid, email = cleanEmail, displayName = cleanDisplayName, numericId = numericId)
        Result.success(profile)
      } else {
        val newProfile = UserProfile(
          uid = uid,
          email = cleanEmail,
          displayName = cleanDisplayName,
          numericId = numericId,
          createdAt = null
        )
        val payload = hashMapOf<String, Any>(
          "uid" to uid,
          "email" to cleanEmail,
          "displayName" to cleanDisplayName,
          "numericId" to numericId,
          "connectedPartnerId" to "",
          "connectedPartnerUid" to "",
          "connectedPartnerName" to "",
          "coins" to 0,
          "lastCoinDate" to "",
          "profilePhotoData" to "",
          "birthday" to "",
          "profession" to "",
          "address" to "",
          "lastProfileEditMonth" to "",
          "lastProfileEditAtMs" to 0L,
          "createdAt" to FieldValue.serverTimestamp()
        )
        userRef.set(payload).await()
        Result.success(newProfile)
      }
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, "users")
      Result.failure(e)
    }
  }

  suspend fun updateExtendedProfile(
    myNumericId: String,
    profilePhotoData: String,
    birthday: String,
    profession: String,
    address: String
  ): Result<Pair<String, Long>> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(IllegalStateException("No internet connection. Please connect to save your profile."))
      }
      requireUserId()
      val cleanId = myNumericId.trim()
      if (!InputSanitizer.isValidNumericId(cleanId)) {
        return@withContext Result.failure(IllegalArgumentException("Invalid user ID"))
      }

      val cleanPhoto = profilePhotoData.trim()
      if (!InputSanitizer.isValidProfilePhotoData(cleanPhoto)) {
        return@withContext Result.failure(IllegalArgumentException("Profile photo is too large or invalid format"))
      }

      val cleanBirthday = InputSanitizer.sanitizeBirthday(birthday)
      if (birthday.isNotBlank() && cleanBirthday.isBlank()) {
        return@withContext Result.failure(IllegalArgumentException("Please select a valid birthday"))
      }

      val cleanProfession = InputSanitizer.sanitizeProfession(profession)
      if (cleanProfession.isNotEmpty() && !InputSanitizer.isValidProfession(cleanProfession)) {
        return@withContext Result.failure(IllegalArgumentException("Please enter a valid profession (2-60 characters)"))
      }

      val cleanAddress = InputSanitizer.sanitizeProfileAddress(address)
      if (cleanAddress.isNotEmpty() && !InputSanitizer.isValidProfileAddress(cleanAddress)) {
        return@withContext Result.failure(IllegalArgumentException("Please enter a valid address (3-200 characters)"))
      }

      if (cleanPhoto.isBlank() && cleanBirthday.isBlank() && cleanProfession.isBlank() && cleanAddress.isBlank()) {
        return@withContext Result.failure(IllegalArgumentException("Please fill in at least one profile field"))
      }

      val nowMs = System.currentTimeMillis()
      val currentMonth = InputSanitizer.currentYearMonth(nowMs)
      val userRef = db.collection("users").document(cleanId)
      val existingSnap = userRef.get().await()
      val existingMonth = existingSnap.getString("lastProfileEditMonth") ?: ""
      val existingPhoto = existingSnap.getString("profilePhotoData") ?: ""
      val isLegacyPhotoUpgrade = existingPhoto.isNotBlank() &&
        !existingPhoto.startsWith("data:image/webp;base64,") &&
        cleanPhoto.startsWith("data:image/webp;base64,") &&
        cleanBirthday == (existingSnap.getString("birthday") ?: "") &&
        cleanProfession == (existingSnap.getString("profession") ?: "") &&
        cleanAddress == (existingSnap.getString("address") ?: "")

      if (existingMonth == currentMonth && !isLegacyPhotoUpgrade) {
        val nextDate = InputSanitizer.nextEligibleEditDateLabel(currentMonth)
        return@withContext Result.failure(
          IllegalStateException("You can only edit your profile once per month. Next edit available from $nextDate.")
        )
      }

      userRef.update(
        mapOf(
          "profilePhotoData" to cleanPhoto,
          "birthday" to cleanBirthday,
          "profession" to cleanProfession,
          "address" to cleanAddress,
          "lastProfileEditMonth" to currentMonth,
          "lastProfileEditAtMs" to nowMs
        )
      ).await()

      Result.success(currentMonth to nowMs)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "users/$myNumericId")
      Result.failure(e)
    }
  }

  suspend fun updateUserCoins(
    myNumericId: String,
    coins: Int,
    lastCoinDate: String
  ) = withContext(Dispatchers.IO) {
    try {
      val cleanId = myNumericId.trim()
      if (!InputSanitizer.isValidNumericId(cleanId)) return@withContext
      val userRef = db.collection("users").document(cleanId)
      userRef.update(
        mapOf(
          "coins" to coins.coerceIn(0, InputSanitizer.MAX_COINS),
          "lastCoinDate" to lastCoinDate.trim().take(10)
        )
      ).await()
    } catch (e: Exception) {
      Log.w(TAG, "updateUserCoins: ${e.message}")
    }
  }

  suspend fun updateConnectedPartner(
    myNumericId: String,
    partnerNumericId: String,
    partnerUid: String,
    partnerName: String
  ) = withContext(Dispatchers.IO) {
    try {
      val cleanId = myNumericId.trim()
      if (!InputSanitizer.isValidNumericId(cleanId)) return@withContext
      val userRef = db.collection("users").document(cleanId)
      userRef.update(
        mapOf(
          "connectedPartnerId" to InputSanitizer.filterDigitsOnly(partnerNumericId.trim(), InputSanitizer.NUMERIC_ID_MAX_LEN),
          "connectedPartnerUid" to partnerUid.trim().take(128),
          "connectedPartnerName" to InputSanitizer.sanitizePersonName(partnerName)
        )
      ).await()
    } catch (e: Exception) {
      // ignore
    }
  }

  suspend fun clearConnectedPartner(myNumericId: String) = withContext(Dispatchers.IO) {
    try {
      val userRef = db.collection("users").document(myNumericId.trim())
      userRef.update(
        mapOf(
          "connectedPartnerId" to "",
          "connectedPartnerUid" to "",
          "connectedPartnerName" to ""
        )
      ).await()
    } catch (e: Exception) {
      // ignore
    }
  }

  suspend fun getUserByNumericId(numericId: String): Result<UserProfile?> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(IllegalStateException("No internet connection. Cannot look up partner offline."))
      }
      val doc = db.collection("users").document(numericId.trim()).get().await()
      if (doc.exists()) {
        Result.success(doc.toObject(UserProfile::class.java))
      } else {
        Result.success(null)
      }
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, "users/$numericId")
      Result.failure(e)
    }
  }

  fun observeMyProfile(myNumericId: String): Flow<UserProfile?> = callbackFlow {
    val cleanId = myNumericId.trim()
    if (auth.currentUser == null || cleanId.isBlank()) {
      trySend(null)
      close()
      return@callbackFlow
    }
    val reg = db.collection("users").document(cleanId).addSnapshotListener { snap, err ->
      if (err != null) {
        if (auth.currentUser == null || err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          close()
          return@addSnapshotListener
        }
        Log.w(TAG, "observeMyProfile warning: ${err.message}")
        return@addSnapshotListener
      }
      val profile = if (snap != null && snap.exists()) snap.toObject(UserProfile::class.java) else null
      trySend(profile)
    }
    awaitClose { reg.remove() }
  }

  fun observeMyOrders(partnerUid: String): Flow<List<Order>> = callbackFlow {
    val uid = auth.currentUser?.uid
    if (uid == null) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    // Query all orders placed by this user - avoids complex composite index requirements
    val query = db.collection("orders").whereEqualTo("userId", uid)

    val registration = query.addSnapshotListener { snapshot, error ->
      if (error != null) {
        if (auth.currentUser == null || error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          Log.d(TAG, "observeMyOrders: unauthenticated or sign out event, closing cleanly")
          close()
          return@addSnapshotListener
        }
        handleFirestoreError(error, OperationType.LIST, "orders")
        close(error)
        return@addSnapshotListener
      }
      val documents = snapshot?.documents ?: emptyList()
      val items = documents.map { doc -> Order.fromDocument(doc) }
      val filteredItems = if (partnerUid.isNotBlank()) {
        items.filter { it.buddyId == partnerUid || it.buddyId.isBlank() }
      } else {
        items
      }
      val sortedItems = filteredItems.sortedByDescending { order ->
        order.createdAt?.toDate()?.time ?: System.currentTimeMillis()
      }
      trySend(sortedItems)
    }
    awaitClose { registration.remove() }
  }

  fun observePartnerOrders(partnerUid: String): Flow<List<Order>> = callbackFlow {
    val uid = auth.currentUser?.uid
    if (uid == null) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    // Query all orders targeted to this user as partner - single field index, no composite index needed
    val query = db.collection("orders").whereEqualTo("buddyId", uid)

    val registration = query.addSnapshotListener { snapshot, error ->
      if (error != null) {
        if (auth.currentUser == null || error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          Log.d(TAG, "observePartnerOrders: unauthenticated or sign out event, closing cleanly")
          close()
          return@addSnapshotListener
        }
        handleFirestoreError(error, OperationType.LIST, "orders")
        close(error)
        return@addSnapshotListener
      }
      val documents = snapshot?.documents ?: emptyList()
      val items = documents.map { doc -> Order.fromDocument(doc) }
      val filteredItems = if (partnerUid.isNotBlank()) {
        items.filter { it.userId == partnerUid }
      } else {
        items
      }
      val sortedItems = filteredItems.sortedByDescending { order ->
        order.createdAt?.toDate()?.time ?: System.currentTimeMillis()
      }
      trySend(sortedItems)
    }
    awaitClose { registration.remove() }
  }

  suspend fun createInvoiceOrder(
    items: List<OrderItemDetail>,
    partnerUid: String,
    partnerName: String,
    userName: String,
    orderNumber: String = "#INV-${Random.nextInt(1000, 9999)}"
  ): Result<String> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(
          IllegalStateException("No internet connection. Order cannot be sent while offline.")
        )
      }
      if (items.isEmpty() || items.size > InputSanitizer.MAX_ITEMS_PER_ORDER) {
        return@withContext Result.failure(
          IllegalArgumentException("Order must contain 1 to ${InputSanitizer.MAX_ITEMS_PER_ORDER} items")
        )
      }
      val sanitizedItems = items.mapNotNull { item ->
        val cleanName = InputSanitizer.sanitizeItemName(item.name)
        if (cleanName.isBlank()) null
        else item.copy(
          name = cleanName,
          quantity = item.quantity.coerceIn(1, InputSanitizer.MAX_ITEM_QUANTITY),
          notes = InputSanitizer.sanitizeNotes(item.notes),
          productUrl = UrlSecurityHelper.sanitizeUrl(item.productUrl) ?: "",
          imageUrl = UrlSecurityHelper.sanitizeUrl(item.imageUrl) ?: "",
          boughtPrice = 0.0,
          isBought = false,
          isUnavailable = false,
          unavailableReason = "",
          marketName = ""
        )
      }
      if (sanitizedItems.isEmpty()) {
        return@withContext Result.failure(IllegalArgumentException("Order must have at least 1 valid item"))
      }

      val uid = requireUserId()
      val cleanPartnerUid = partnerUid.trim().take(128)
      if (cleanPartnerUid.isBlank()) {
        return@withContext Result.failure(IllegalArgumentException("Partner UID is required"))
      }

      val docRef = db.collection("orders").document()

      val now = Date()
      val dateFormat = SimpleDateFormat("dd MMM, yyyy", Locale.US)
      val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

      val serializedItems = sanitizedItems.map { it.toMap() }

      val createdAtMs = System.currentTimeMillis()
      val payload = hashMapOf<String, Any>(
        "orderNumber" to InputSanitizer.sanitizeForSubmit(orderNumber, 40).ifBlank { "#INV-${Random.nextInt(1000, 9999)}" },
        "userId" to uid,
        "userName" to InputSanitizer.sanitizePersonName(userName).ifBlank { "User" },
        "buddyId" to cleanPartnerUid,
        "partnerName" to InputSanitizer.sanitizePersonName(partnerName).ifBlank { "Partner" },
        "status" to "pending",
        "items" to serializedItems,
        "totalCost" to 0.0,
        "formattedDate" to dateFormat.format(now),
        "formattedTime" to timeFormat.format(now),
        "createdAtMs" to createdAtMs,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )

      Log.d(TAG, "createInvoiceOrder: writing order to Firestore docId=${docRef.id}, user=$uid, partner=$cleanPartnerUid")
      docRef.set(payload).await()
      Log.d(TAG, "createInvoiceOrder: order successfully saved to Firestore: ${docRef.id}")
      Result.success(docRef.id)
    } catch (e: Exception) {
      Log.e(TAG, "createInvoiceOrder: failed to write order", e)
      handleFirestoreError(e, OperationType.CREATE, "orders")
      Result.failure(e)
    }
  }

  suspend fun acceptOrder(
    orderId: String,
    shoppingDurationMinutes: Int = 30
  ): Result<Long> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(IllegalStateException("No internet connection."))
      }
      val safeDuration = shoppingDurationMinutes.coerceIn(InputSanitizer.MIN_DURATION_MINS, InputSanitizer.MAX_DURATION_MINS)
      val acceptedAtMs = System.currentTimeMillis()
      val deadlineMs = acceptedAtMs + safeDuration * 60_000L
      val docRef = db.collection("orders").document(orderId)
      val payload = mapOf(
        "status" to "accepted",
        "acceptedAtMs" to acceptedAtMs,
        "shoppingDurationMinutes" to safeDuration,
        "shoppingDeadlineMs" to deadlineMs,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      docRef.update(payload).await()
      Result.success(deadlineMs)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "orders/$orderId")
      Result.failure(e)
    }
  }

  suspend fun rejectOrder(orderId: String, reason: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(IllegalStateException("No internet connection."))
      }
      val cleanReason = InputSanitizer.sanitizeReason(reason).ifBlank { "Unable to fulfill right now" }
      val docRef = db.collection("orders").document(orderId)
      val payload = mapOf(
        "status" to "rejected",
        "rejectionReason" to cleanReason,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      docRef.update(payload).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "orders/$orderId")
      Result.failure(e)
    }
  }

  suspend fun markOrderIncomplete(orderId: String, reason: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(IllegalStateException("No internet connection."))
      }
      val cleanReason = InputSanitizer.sanitizeReason(reason).ifBlank { "Could not complete shopping" }
      val docRef = db.collection("orders").document(orderId)
      val payload = mapOf(
        "status" to "incomplete",
        "incompleteReason" to cleanReason,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      docRef.update(payload).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "orders/$orderId")
      Result.failure(e)
    }
  }

  suspend fun expireOrderIfPending(orderId: String): Boolean = withContext(Dispatchers.IO) {
    com.example.service.OrderLifecycleManager.expireOrderIfPending(db, orderId)
  }

  suspend fun markItemBought(
    orderId: String,
    currentItems: List<OrderItemDetail>,
    itemIndex: Int,
    pricePaid: Double,
    marketName: String = "",
    orderNumber: String = "",
    requesterId: String = ""
  ): Result<MarkItemResult> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(IllegalStateException("No internet connection."))
      }
      if (!pricePaid.isFinite() || pricePaid < 0.0 || pricePaid > InputSanitizer.MAX_ITEM_PRICE) {
        return@withContext Result.failure(IllegalArgumentException("Invalid item price"))
      }
      val safePrice = String.format(Locale.US, "%.2f", pricePaid).toDoubleOrNull()
        ?: pricePaid.coerceIn(0.0, InputSanitizer.MAX_ITEM_PRICE)
      val uid = requireUserId()
      val docRef = db.collection("orders").document(orderId)

      val cleanMarket = InputSanitizer.sanitizeMarketName(marketName)
      var allDone = false
      var totalCost = 0.0
      var finalItems: List<OrderItemDetail> = emptyList()

      db.runTransaction { transaction ->
        val snap = transaction.get(docRef)
        val liveItems = if (snap.exists()) Order.fromDocument(snap).typedItems else emptyList()
        val baseItems = liveItems.ifEmpty { currentItems }
        if (itemIndex !in baseItems.indices) {
          throw IndexOutOfBoundsException("Item index invalid")
        }

        val updatedList = baseItems.toMutableList()
        val target = updatedList[itemIndex]
        updatedList[itemIndex] = target.copy(
          isBought = true,
          isUnavailable = false,
          unavailableReason = "",
          boughtPrice = safePrice,
          marketName = cleanMarket.ifBlank { InputSanitizer.sanitizeMarketName(target.marketName) }
        )

        allDone = updatedList.all { it.isBought || it.isUnavailable }
        totalCost = updatedList.filter { it.isBought }
          .sumOf { if (it.boughtPrice.isFinite()) it.boughtPrice.coerceIn(0.0, InputSanitizer.MAX_ITEM_PRICE) else 0.0 }
          .coerceIn(0.0, InputSanitizer.MAX_ORDER_COST)

        finalItems = if (allDone && cleanMarket.isNotBlank()) {
          updatedList.map {
            if (it.isBought) it.copy(marketName = it.marketName.ifBlank { cleanMarket }) else it
          }
        } else {
          updatedList
        }

        val payload = hashMapOf<String, Any>(
          "items" to finalItems.map { it.toMap() },
          "totalCost" to totalCost,
          "status" to if (allDone) "completed" else "accepted",
          "updatedAt" to FieldValue.serverTimestamp()
        )
        if (allDone && cleanMarket.isNotBlank()) {
          payload["purchaseMarketName"] = cleanMarket
        }

        transaction.update(docRef, payload)
        null
      }.await()

      // When buying the last available item completes the order, log only bought items on server
      if (allDone && cleanMarket.isNotBlank()) {
        val now = Date()
        val dateFormat = SimpleDateFormat("dd MMM, yyyy", Locale.US)
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)
        finalItems.filter { it.isBought }.forEach { item ->
          val marketPurchase = MarketPurchase(
            userId = uid,
            orderId = orderId.take(128),
            orderNumber = orderNumber.take(40),
            requesterId = requesterId.take(128),
            itemName = InputSanitizer.sanitizeItemName(item.name).ifBlank { "Item" },
            quantity = item.quantity.coerceIn(1, InputSanitizer.MAX_ITEM_QUANTITY),
            notes = InputSanitizer.sanitizeNotes(item.notes),
            boughtPrice = if (item.boughtPrice.isFinite()) item.boughtPrice.coerceIn(0.0, InputSanitizer.MAX_ITEM_PRICE) else 0.0,
            productUrl = UrlSecurityHelper.sanitizeUrl(item.productUrl) ?: "",
            marketSource = if (item.productUrl.isNotBlank()) "online" else "manual",
            marketName = InputSanitizer.sanitizeMarketName(item.marketName.ifBlank { cleanMarket }),
            formattedDate = dateFormat.format(now),
            formattedTime = timeFormat.format(now)
          )
          saveMarketPurchase(marketPurchase)
        }
      }

      Result.success(MarkItemResult(orderCompleted = allDone, totalCost = totalCost))
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "orders/$orderId")
      Result.failure(e)
    }
  }

  suspend fun markItemUnavailable(
    orderId: String,
    currentItems: List<OrderItemDetail>,
    itemIndex: Int,
    reason: String = "",
    marketNameIfCompleting: String = "",
    orderNumber: String = "",
    requesterId: String = ""
  ): Result<MarkItemResult> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(IllegalStateException("No internet connection."))
      }
      val uid = requireUserId()
      val docRef = db.collection("orders").document(orderId)
      val cleanReason = InputSanitizer.sanitizeReason(reason, InputSanitizer.MAX_UNAVAILABLE_REASON_LEN)
        .ifBlank { "Not available in market" }
      val cleanMarket = InputSanitizer.sanitizeMarketName(marketNameIfCompleting)

      var orderCompleted = false
      var orderIncomplete = false
      var totalCost = 0.0
      var finalItems: List<OrderItemDetail> = emptyList()

      db.runTransaction { transaction ->
        val snap = transaction.get(docRef)
        val liveItems = if (snap.exists()) Order.fromDocument(snap).typedItems else emptyList()
        val baseItems = liveItems.ifEmpty { currentItems }
        if (itemIndex !in baseItems.indices) {
          throw IndexOutOfBoundsException("Item index invalid")
        }

        val updatedList = baseItems.toMutableList()
        val target = updatedList[itemIndex]
        updatedList[itemIndex] = target.copy(
          isBought = false,
          isUnavailable = true,
          unavailableReason = cleanReason,
          boughtPrice = 0.0
        )

        val allResolved = updatedList.all { it.isBought || it.isUnavailable }
        val anyBought = updatedList.any { it.isBought }
        orderCompleted = allResolved && anyBought
        orderIncomplete = allResolved && !anyBought
        totalCost = updatedList.filter { it.isBought }
          .sumOf { if (it.boughtPrice.isFinite()) it.boughtPrice.coerceIn(0.0, InputSanitizer.MAX_ITEM_PRICE) else 0.0 }
          .coerceIn(0.0, InputSanitizer.MAX_ORDER_COST)

        finalItems = if (orderCompleted && cleanMarket.isNotBlank()) {
          updatedList.map {
            if (it.isBought) it.copy(marketName = it.marketName.ifBlank { cleanMarket }) else it
          }
        } else {
          updatedList
        }

        val nextStatus = when {
          orderCompleted -> "completed"
          orderIncomplete -> "incomplete"
          else -> "accepted"
        }

        val payload = hashMapOf<String, Any>(
          "items" to finalItems.map { it.toMap() },
          "totalCost" to totalCost,
          "status" to nextStatus,
          "updatedAt" to FieldValue.serverTimestamp()
        )
        if (orderCompleted && cleanMarket.isNotBlank()) {
          payload["purchaseMarketName"] = cleanMarket
        }
        if (orderIncomplete) {
          payload["incompleteReason"] = InputSanitizer.sanitizeReason("All items unavailable in market ($cleanReason)")
        }

        transaction.update(docRef, payload)
        null
      }.await()

      if (orderCompleted && cleanMarket.isNotBlank()) {
        val now = Date()
        val dateFormat = SimpleDateFormat("dd MMM, yyyy", Locale.US)
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)
        finalItems.filter { it.isBought }.forEach { item ->
          val marketPurchase = MarketPurchase(
            userId = uid,
            orderId = orderId.take(128),
            orderNumber = orderNumber.take(40),
            requesterId = requesterId.take(128),
            itemName = InputSanitizer.sanitizeItemName(item.name).ifBlank { "Item" },
            quantity = item.quantity.coerceIn(1, InputSanitizer.MAX_ITEM_QUANTITY),
            notes = InputSanitizer.sanitizeNotes(item.notes),
            boughtPrice = if (item.boughtPrice.isFinite()) item.boughtPrice.coerceIn(0.0, InputSanitizer.MAX_ITEM_PRICE) else 0.0,
            productUrl = UrlSecurityHelper.sanitizeUrl(item.productUrl) ?: "",
            marketSource = if (item.productUrl.isNotBlank()) "online" else "manual",
            marketName = InputSanitizer.sanitizeMarketName(item.marketName.ifBlank { cleanMarket }),
            formattedDate = dateFormat.format(now),
            formattedTime = timeFormat.format(now)
          )
          saveMarketPurchase(marketPurchase)
        }
      }

      Result.success(
        MarkItemResult(
          orderCompleted = orderCompleted,
          totalCost = totalCost,
          orderMarkedIncomplete = orderIncomplete
        )
      )
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "orders/$orderId")
      Result.failure(e)
    }
  }

  suspend fun saveMarketPurchase(purchase: MarketPurchase): Result<String> = withContext(Dispatchers.IO) {
    try {
      val uid = requireUserId()
      val cleanMarketName = InputSanitizer.sanitizeMarketName(purchase.marketName)
      if (!InputSanitizer.isValidMarketName(cleanMarketName)) {
        return@withContext Result.failure(IllegalArgumentException("Market name is required"))
      }
      val safePrice = if (purchase.boughtPrice.isFinite()) {
        purchase.boughtPrice.coerceIn(0.0, InputSanitizer.MAX_ITEM_PRICE)
      } else 0.0
      val docRef = db.collection("market_purchases").document()
      val payload = hashMapOf<String, Any>(
        "userId" to uid,
        "orderId" to purchase.orderId.ifBlank { "unknown" }.take(128),
        "orderNumber" to InputSanitizer.sanitizeForSubmit(purchase.orderNumber, 40),
        "requesterId" to purchase.requesterId.trim().take(128),
        "itemName" to InputSanitizer.sanitizeItemName(purchase.itemName).ifBlank { "Item" },
        "quantity" to purchase.quantity.coerceIn(1, InputSanitizer.MAX_ITEM_QUANTITY),
        "notes" to InputSanitizer.sanitizeNotes(purchase.notes),
        "boughtPrice" to safePrice,
        "productUrl" to (UrlSecurityHelper.sanitizeUrl(purchase.productUrl) ?: ""),
        "marketSource" to when (purchase.marketSource) {
          "online", "offline", "manual" -> purchase.marketSource
          else -> "manual"
        },
        "marketName" to cleanMarketName,
        "formattedDate" to purchase.formattedDate.take(40),
        "formattedTime" to purchase.formattedTime.take(40),
        "createdAt" to FieldValue.serverTimestamp()
      )

      docRef.set(payload).await()
      Log.d(TAG, "saveMarketPurchase: saved server-side market log ${docRef.id}")
      Result.success(docRef.id)
    } catch (e: Exception) {
      Log.w(TAG, "saveMarketPurchase warning: ${e.message}")
      handleFirestoreError(e, OperationType.CREATE, "market_purchases")
      Result.failure(e)
    }
  }

  suspend fun completeOrder(
    orderId: String,
    marketName: String = ""
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      val docRef = db.collection("orders").document(orderId)
      val cleanMarket = InputSanitizer.sanitizeMarketName(marketName)
      val payload = hashMapOf<String, Any>(
        "status" to "completed",
        "updatedAt" to FieldValue.serverTimestamp()
      )
      if (cleanMarket.isNotBlank()) {
        payload["purchaseMarketName"] = cleanMarket
      }
      docRef.update(payload).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "orders/$orderId")
      Result.failure(e)
    }
  }

  suspend fun deleteOrder(orderId: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(IllegalStateException("No internet connection."))
      }
      val docRef = db.collection("orders").document(orderId)
      docRef.delete().await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, "orders/$orderId")
      Result.failure(e)
    }
  }

  suspend fun sendConnectionNotification(
    targetUid: String,
    senderName: String,
    senderNumericId: String,
    senderUid: String
  ) {
    val cleanTarget = targetUid.trim().take(128)
    if (cleanTarget.isBlank() || cleanTarget == "ALL") return
    val cleanSender = InputSanitizer.sanitizePersonName(senderName).ifBlank { "Partner" }
    val cleanNumericId = InputSanitizer.filterDigitsOnly(senderNumericId, InputSanitizer.NUMERIC_ID_MAX_LEN)
    try {
      val doc = db.collection("notifications").document()
      val payload = mapOf(
        "targetUid" to cleanTarget,
        "title" to "Partner Connected",
        "message" to InputSanitizer.sanitizeForSubmit("$cleanSender (ID: $cleanNumericId) connected with you!", InputSanitizer.MAX_NOTIF_MESSAGE_LEN),
        "type" to "partner_connected",
        "senderNumericId" to cleanNumericId,
        "senderUid" to senderUid.trim().take(128),
        "senderName" to cleanSender,
        "createdAt" to FieldValue.serverTimestamp()
      )
      doc.set(payload).await()
    } catch (e: Exception) {
      Log.w(TAG, "sendConnectionNotification failed: ${e.message}")
    }
  }

  suspend fun sendOrderNotification(
    targetUid: String,
    senderName: String,
    orderNumber: String,
    itemCount: Int,
    orderId: String = ""
  ) {
    val cleanTarget = targetUid.trim().take(128)
    if (cleanTarget.isBlank() || cleanTarget == "ALL") return
    val cleanSender = InputSanitizer.sanitizePersonName(senderName).ifBlank { "Partner" }
    val cleanOrderNo = InputSanitizer.sanitizeForSubmit(orderNumber, 40)
    try {
      val doc = db.collection("notifications").document()
      val payload = hashMapOf<String, Any>(
        "targetUid" to cleanTarget,
        "title" to InputSanitizer.sanitizeForSubmit("New Order from $cleanSender", InputSanitizer.MAX_NOTIF_TITLE_LEN),
        "message" to InputSanitizer.sanitizeForSubmit(
          "$cleanSender sent a new order $cleanOrderNo with ${itemCount.coerceIn(1, InputSanitizer.MAX_ITEMS_PER_ORDER)} item(s).",
          InputSanitizer.MAX_NOTIF_MESSAGE_LEN
        ),
        "type" to "new_order",
        "createdAt" to FieldValue.serverTimestamp()
      )
      if (orderId.isNotBlank()) {
        payload["orderId"] = orderId.trim().take(128)
      }
      doc.set(payload).await()
    } catch (e: Exception) {
      Log.w(TAG, "sendOrderNotification failed: ${e.message}")
    }
  }

  suspend fun sendOrderAcceptedNotification(
    targetUid: String,
    partnerName: String,
    orderNumber: String,
    shoppingDurationMinutes: Int = 0,
    orderId: String = ""
  ) {
    val cleanTarget = targetUid.trim().take(128)
    if (cleanTarget.isBlank() || cleanTarget == "ALL") return
    val cleanPartner = InputSanitizer.sanitizePersonName(partnerName).ifBlank { "Partner" }
    val cleanOrderNo = InputSanitizer.sanitizeForSubmit(orderNumber, 40)
    try {
      val doc = db.collection("notifications").document()
      val timeSuffix = if (shoppingDurationMinutes > 0) " (Estimated shopping time: ${shoppingDurationMinutes.coerceIn(1, 1440)} mins)" else ""
      val payload = hashMapOf<String, Any>(
        "targetUid" to cleanTarget,
        "title" to "Order Accepted",
        "message" to InputSanitizer.sanitizeForSubmit("$cleanPartner accepted your order $cleanOrderNo$timeSuffix.", InputSanitizer.MAX_NOTIF_MESSAGE_LEN),
        "type" to "order_accepted",
        "createdAt" to FieldValue.serverTimestamp()
      )
      if (orderId.isNotBlank()) {
        payload["orderId"] = orderId.trim().take(128)
      }
      doc.set(payload).await()
    } catch (e: Exception) {
      Log.w(TAG, "sendOrderAcceptedNotification failed: ${e.message}")
    }
  }

  suspend fun sendOrderRejectedNotification(
    targetUid: String,
    partnerName: String,
    orderNumber: String,
    reason: String,
    orderId: String = ""
  ) {
    val cleanTarget = targetUid.trim().take(128)
    if (cleanTarget.isBlank() || cleanTarget == "ALL") return
    val cleanPartner = InputSanitizer.sanitizePersonName(partnerName).ifBlank { "Partner" }
    val cleanOrderNo = InputSanitizer.sanitizeForSubmit(orderNumber, 40)
    try {
      val doc = db.collection("notifications").document()
      val cleanReason = InputSanitizer.sanitizeReason(reason).ifBlank { "No reason provided" }
      val payload = hashMapOf<String, Any>(
        "targetUid" to cleanTarget,
        "title" to "Order Rejected",
        "message" to InputSanitizer.sanitizeForSubmit("$cleanPartner rejected order $cleanOrderNo. Reason: $cleanReason", InputSanitizer.MAX_NOTIF_MESSAGE_LEN),
        "type" to "order_rejected",
        "createdAt" to FieldValue.serverTimestamp()
      )
      if (orderId.isNotBlank()) {
        payload["orderId"] = orderId.trim().take(128)
      }
      doc.set(payload).await()
    } catch (e: Exception) {
      Log.w(TAG, "sendOrderRejectedNotification failed: ${e.message}")
    }
  }

  suspend fun sendOrderIncompleteNotification(
    targetUid: String,
    partnerName: String,
    orderNumber: String,
    reason: String,
    orderId: String = ""
  ) {
    val cleanTarget = targetUid.trim().take(128)
    if (cleanTarget.isBlank() || cleanTarget == "ALL") return
    val cleanPartner = InputSanitizer.sanitizePersonName(partnerName).ifBlank { "Partner" }
    val cleanOrderNo = InputSanitizer.sanitizeForSubmit(orderNumber, 40)
    try {
      val doc = db.collection("notifications").document()
      val cleanReason = InputSanitizer.sanitizeReason(reason).ifBlank { "Unable to complete shopping" }
      val payload = hashMapOf<String, Any>(
        "targetUid" to cleanTarget,
        "title" to "Order Incomplete",
        "message" to InputSanitizer.sanitizeForSubmit("$cleanPartner could not complete order $cleanOrderNo. Reason: $cleanReason", InputSanitizer.MAX_NOTIF_MESSAGE_LEN),
        "type" to "order_incomplete",
        "createdAt" to FieldValue.serverTimestamp()
      )
      if (orderId.isNotBlank()) {
        payload["orderId"] = orderId.trim().take(128)
      }
      doc.set(payload).await()
    } catch (e: Exception) {
      Log.w(TAG, "sendOrderIncompleteNotification failed: ${e.message}")
    }
  }

  suspend fun sendItemUnavailableNotification(
    targetUid: String,
    partnerName: String,
    orderNumber: String,
    itemName: String,
    reason: String,
    orderId: String = ""
  ) {
    val cleanTarget = targetUid.trim().take(128)
    if (cleanTarget.isBlank() || cleanTarget == "ALL") return
    val cleanPartner = InputSanitizer.sanitizePersonName(partnerName).ifBlank { "Partner" }
    val cleanOrderNo = InputSanitizer.sanitizeForSubmit(orderNumber, 40)
    val cleanItem = InputSanitizer.sanitizeItemName(itemName).ifBlank { "Item" }
    try {
      val doc = db.collection("notifications").document()
      val cleanReason = InputSanitizer.sanitizeReason(reason, InputSanitizer.MAX_UNAVAILABLE_REASON_LEN)
      val reasonPart = if (cleanReason.isNotBlank()) " ($cleanReason)" else ""
      val payload = hashMapOf<String, Any>(
        "targetUid" to cleanTarget,
        "title" to "Item Unavailable",
        "message" to InputSanitizer.sanitizeForSubmit("$cleanPartner marked '$cleanItem' as Unavailable in order $cleanOrderNo$reasonPart.", InputSanitizer.MAX_NOTIF_MESSAGE_LEN),
        "type" to "item_unavailable",
        "createdAt" to FieldValue.serverTimestamp()
      )
      if (orderId.isNotBlank()) {
        payload["orderId"] = orderId.trim().take(128)
      }
      doc.set(payload).await()
    } catch (e: Exception) {
      Log.w(TAG, "sendItemUnavailableNotification failed: ${e.message}")
    }
  }

  suspend fun sendOrderCompletedNotification(
    targetUid: String,
    partnerName: String,
    orderNumber: String,
    amount: Double,
    unavailableCount: Int = 0,
    orderId: String = ""
  ) {
    val cleanTarget = targetUid.trim().take(128)
    if (cleanTarget.isBlank() || cleanTarget == "ALL") return
    val cleanPartner = InputSanitizer.sanitizePersonName(partnerName).ifBlank { "Partner" }
    val cleanOrderNo = InputSanitizer.sanitizeForSubmit(orderNumber, 40)
    val safeAmount = if (amount.isFinite()) amount.coerceIn(0.0, InputSanitizer.MAX_ORDER_COST) else 0.0
    try {
      val doc = db.collection("notifications").document()
      val unavailNote = if (unavailableCount > 0) " ($unavailableCount unavailable)" else ""
      val payload = hashMapOf<String, Any>(
        "targetUid" to cleanTarget,
        "title" to "Order Completed",
        "message" to InputSanitizer.sanitizeForSubmit(
          "$cleanPartner completed order $cleanOrderNo$unavailNote. Total: ৳${String.format(Locale.US, "%.2f", safeAmount)}",
          InputSanitizer.MAX_NOTIF_MESSAGE_LEN
        ),
        "type" to "order_completed",
        "createdAt" to FieldValue.serverTimestamp()
      )
      if (orderId.isNotBlank()) {
        payload["orderId"] = orderId.trim().take(128)
      }
      doc.set(payload).await()
    } catch (e: Exception) {
      Log.w(TAG, "sendOrderCompletedNotification failed: ${e.message}")
    }
  }

  fun observeIncomingNotifications(): Flow<List<NotificationData>> = callbackFlow {
    val uid = auth.currentUser?.uid
    if (uid == null) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    val query = db.collection("notifications")
      .whereEqualTo("targetUid", uid)
      .limit(20)

    val reg = query.addSnapshotListener { snap, err ->
      if (err != null) {
        if (auth.currentUser == null || err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          close()
          return@addSnapshotListener
        }
        Log.w(TAG, "observeIncomingNotifications warning: ${err.message}")
        return@addSnapshotListener
      }
      val list = snap?.documents?.mapNotNull { doc ->
        doc.toObject(NotificationData::class.java)?.copy(id = doc.id)
      } ?: emptyList()
      trySend(list)
    }
    awaitClose { reg.remove() }
  }

  fun observeBroadcastNotifications(): Flow<List<NotificationData>> = callbackFlow {
    if (auth.currentUser == null) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    val query = db.collection("notifications")
      .whereEqualTo("targetUid", "ALL")
      .limit(30)

    val reg = query.addSnapshotListener { snap, err ->
      if (err != null) {
        if (auth.currentUser == null || err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          close()
          return@addSnapshotListener
        }
        Log.w(TAG, "observeBroadcastNotifications warning: ${err.message}")
        return@addSnapshotListener
      }
      val list = snap?.documents?.mapNotNull { doc ->
        doc.toObject(NotificationData::class.java)?.copy(id = doc.id)
      }?.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L } ?: emptyList()
      trySend(list)
    }
    awaitClose { reg.remove() }
  }

  suspend fun clearNotification(id: String) {
    try {
      db.collection("notifications").document(id).delete().await()
    } catch (e: Exception) {
      Log.w(TAG, "clearNotification($id) failed: ${e.message}")
    }
  }

  // --- 180 COINS GIFT CLAIM (USER) ---

  fun observeMyGiftClaim(): Flow<GiftClaim?> = callbackFlow {
    val uid = auth.currentUser?.uid
    if (uid == null) {
      trySend(null)
      close()
      return@callbackFlow
    }
    val docRef = db.collection("gift_claims").document(uid)
    val reg = docRef.addSnapshotListener { snap, err ->
      if (err != null) {
        if (auth.currentUser == null || err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          close()
          return@addSnapshotListener
        }
        handleFirestoreError(err, OperationType.GET, "gift_claims/$uid")
        close(err)
        return@addSnapshotListener
      }
      val claim = if (snap != null && snap.exists()) {
        snap.toObject(GiftClaim::class.java)?.copy(id = snap.id)
      } else null
      trySend(claim)
    }
    awaitClose { reg.remove() }
  }

  suspend fun submitGiftClaim(
    myNumericId: String,
    coins: Int,
    recipientName: String,
    phone: String,
    address: String,
    note: String
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      if (!isOnlineIfContextAvailable()) {
        return@withContext Result.failure(IllegalStateException("No internet connection."))
      }
      val currentUser = auth.currentUser
        ?: return@withContext Result.failure(IllegalStateException("User must be signed in."))
      val uid = currentUser.uid
      if (coins < AdminConfig.GIFT_COIN_THRESHOLD) {
        return@withContext Result.failure(IllegalArgumentException("At least ${AdminConfig.GIFT_COIN_THRESHOLD} coins required."))
      }

      val safeCoins = coins.coerceIn(AdminConfig.GIFT_COIN_THRESHOLD, InputSanitizer.MAX_COINS)
      val cleanName = InputSanitizer.sanitizePersonName(recipientName)
      val cleanPhone = InputSanitizer.sanitizePhone(phone)
      val cleanAddress = InputSanitizer.sanitizeAddress(address)
      val cleanNote = InputSanitizer.sanitizeForSubmit(note, InputSanitizer.MAX_GIFT_NOTE_LEN)
      if (!InputSanitizer.isValidPersonName(cleanName) ||
        !InputSanitizer.isValidPhone(cleanPhone) ||
        !InputSanitizer.isValidAddress(cleanAddress)
      ) {
        return@withContext Result.failure(IllegalArgumentException("Invalid delivery address details."))
      }

      val cleanNumericId = myNumericId.trim().ifBlank {
        UserProfile.generateNumericId(currentUser.email ?: uid)
      }
      // Ensure user doc has latest coin count synced before creating claim
      try {
        db.collection("users").document(cleanNumericId)
          .update("coins", safeCoins)
          .await()
      } catch (_: Exception) {}

      val cleanEmail = (currentUser.email ?: "").take(150)
      val cleanUserName = InputSanitizer.sanitizePersonName(
        currentUser.displayName ?: currentUser.email?.substringBefore("@") ?: "User"
      ).ifBlank { "User" }

      val docRef = db.collection("gift_claims").document(uid)
      val existing = docRef.get().await()
      if (existing.exists()) {
        docRef.update(
          mapOf(
            "userNumericId" to cleanNumericId,
            "userEmail" to cleanEmail,
            "userName" to cleanUserName,
            "coins" to safeCoins,
            "recipientName" to cleanName,
            "phone" to cleanPhone,
            "address" to cleanAddress,
            "note" to cleanNote,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        ).await()
      } else {
        val payload = hashMapOf<String, Any>(
          "userId" to uid,
          "userNumericId" to cleanNumericId,
          "userEmail" to cleanEmail,
          "userName" to cleanUserName,
          "coins" to safeCoins,
          "recipientName" to cleanName,
          "phone" to cleanPhone,
          "address" to cleanAddress,
          "note" to cleanNote,
          "status" to "pending",
          "createdAt" to FieldValue.serverTimestamp(),
          "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(payload).await()
      }
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, "gift_claims")
      Result.failure(e)
    }
  }

  // --- ADMIN OBSERVERS & FULL DATA CONTROL (zahidhasan2000joy@gmail.com) ---

  private fun requireAdmin(): String {
    val user = auth.currentUser
      ?: throw IllegalStateException("Admin must be signed in.")
    if (!AdminConfig.isAdminEmail(user.email)) {
      throw SecurityException("Admin privileges required.")
    }
    return user.uid
  }

  fun observeAllUsers(): Flow<List<UserProfile>> = callbackFlow {
    if (!AdminConfig.isAdminEmail(auth.currentUser?.email)) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    val reg = db.collection("users").addSnapshotListener { snap, err ->
      if (err != null) {
        if (auth.currentUser == null || err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          close()
          return@addSnapshotListener
        }
        handleFirestoreError(err, OperationType.LIST, "users")
        close(err)
        return@addSnapshotListener
      }
      val users = snap?.documents?.mapNotNull { it.toObject(UserProfile::class.java) }
        ?.sortedByDescending { it.coins }
        ?: emptyList()
      trySend(users)
    }
    awaitClose { reg.remove() }
  }

  fun observeAllOrders(): Flow<List<Order>> = callbackFlow {
    if (!AdminConfig.isAdminEmail(auth.currentUser?.email)) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    val reg = db.collection("orders").addSnapshotListener { snap, err ->
      if (err != null) {
        if (auth.currentUser == null || err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          close()
          return@addSnapshotListener
        }
        handleFirestoreError(err, OperationType.LIST, "orders")
        close(err)
        return@addSnapshotListener
      }
      val orders = snap?.documents?.map { Order.fromDocument(it) }
        ?.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
        ?: emptyList()
      trySend(orders)
    }
    awaitClose { reg.remove() }
  }

  fun observeAllGiftClaims(): Flow<List<GiftClaim>> = callbackFlow {
    if (!AdminConfig.isAdminEmail(auth.currentUser?.email)) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    val reg = db.collection("gift_claims").addSnapshotListener { snap, err ->
      if (err != null) {
        if (auth.currentUser == null || err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          close()
          return@addSnapshotListener
        }
        handleFirestoreError(err, OperationType.LIST, "gift_claims")
        close(err)
        return@addSnapshotListener
      }
      val claims = snap?.documents?.mapNotNull { doc ->
        doc.toObject(GiftClaim::class.java)?.copy(id = doc.id)
      }?.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L } ?: emptyList()
      trySend(claims)
    }
    awaitClose { reg.remove() }
  }

  fun observeAllMarketPurchases(): Flow<List<MarketPurchase>> = callbackFlow {
    if (!AdminConfig.isAdminEmail(auth.currentUser?.email)) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    val reg = db.collection("market_purchases").addSnapshotListener { snap, err ->
      if (err != null) {
        if (auth.currentUser == null || err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          close()
          return@addSnapshotListener
        }
        handleFirestoreError(err, OperationType.LIST, "market_purchases")
        close(err)
        return@addSnapshotListener
      }
      val list = snap?.documents?.mapNotNull { doc ->
        doc.toObject(MarketPurchase::class.java)?.copy(id = doc.id)
      }?.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L } ?: emptyList()
      trySend(list)
    }
    awaitClose { reg.remove() }
  }

  fun observeAllNotifications(): Flow<List<NotificationData>> = callbackFlow {
    if (!AdminConfig.isAdminEmail(auth.currentUser?.email)) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    val reg = db.collection("notifications").addSnapshotListener { snap, err ->
      if (err != null) {
        if (auth.currentUser == null || err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
          close()
          return@addSnapshotListener
        }
        handleFirestoreError(err, OperationType.LIST, "notifications")
        close(err)
        return@addSnapshotListener
      }
      val list = snap?.documents?.mapNotNull { doc ->
        doc.toObject(NotificationData::class.java)?.copy(id = doc.id)
      }?.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L } ?: emptyList()
      trySend(list)
    }
    awaitClose { reg.remove() }
  }

  suspend fun adminUpdateUser(
    numericId: String,
    displayName: String,
    coins: Int,
    clearPartner: Boolean = false
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      val updates = hashMapOf<String, Any>(
        "displayName" to InputSanitizer.sanitizePersonName(displayName).ifBlank { "User" },
        "coins" to coins.coerceIn(0, InputSanitizer.MAX_COINS)
      )
      if (clearPartner) {
        updates["connectedPartnerId"] = ""
        updates["connectedPartnerUid"] = ""
        updates["connectedPartnerName"] = ""
      }
      db.collection("users").document(numericId.trim()).update(updates).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "users/$numericId")
      Result.failure(e)
    }
  }

  suspend fun adminDeleteUser(numericId: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      db.collection("users").document(numericId.trim()).delete().await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, "users/$numericId")
      Result.failure(e)
    }
  }

  suspend fun adminUpdateOrder(
    orderId: String,
    status: String,
    totalCost: Double,
    marketName: String
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      val validStatus = if (status in listOf("pending", "accepted", "completed", "cancelled", "rejected", "expired", "incomplete")) status else "pending"
      val safeTotal = if (totalCost.isFinite()) totalCost.coerceIn(0.0, InputSanitizer.MAX_ORDER_COST) else 0.0
      val updates = hashMapOf<String, Any>(
        "status" to validStatus,
        "totalCost" to safeTotal,
        "purchaseMarketName" to InputSanitizer.sanitizeMarketName(marketName),
        "updatedAt" to FieldValue.serverTimestamp()
      )
      db.collection("orders").document(orderId).update(updates).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "orders/$orderId")
      Result.failure(e)
    }
  }

  suspend fun adminUpdateGiftClaim(
    claimId: String,
    status: String,
    recipientName: String,
    phone: String,
    address: String,
    note: String
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      val validStatus = if (status in listOf("pending", "approved", "delivered")) status else "pending"
      val updates = hashMapOf<String, Any>(
        "status" to validStatus,
        "recipientName" to InputSanitizer.sanitizePersonName(recipientName),
        "phone" to InputSanitizer.sanitizePhone(phone),
        "address" to InputSanitizer.sanitizeAddress(address),
        "note" to InputSanitizer.sanitizeForSubmit(note, InputSanitizer.MAX_GIFT_NOTE_LEN),
        "updatedAt" to FieldValue.serverTimestamp()
      )
      db.collection("gift_claims").document(claimId).update(updates).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "gift_claims/$claimId")
      Result.failure(e)
    }
  }

  suspend fun adminDeleteGiftClaim(claimId: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      db.collection("gift_claims").document(claimId).delete().await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, "gift_claims/$claimId")
      Result.failure(e)
    }
  }

  suspend fun adminUpdateMarketPurchase(
    purchaseId: String,
    itemName: String,
    marketName: String,
    boughtPrice: Double
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      val cleanMarket = InputSanitizer.sanitizeMarketName(marketName)
      val cleanItem = InputSanitizer.sanitizeItemName(itemName)
      if (!InputSanitizer.isValidMarketName(cleanMarket) || !InputSanitizer.isValidItemName(cleanItem)) {
        return@withContext Result.failure(IllegalArgumentException("Item and Market name are required."))
      }
      val safePrice = if (boughtPrice.isFinite()) boughtPrice.coerceIn(0.0, InputSanitizer.MAX_ITEM_PRICE) else 0.0
      db.collection("market_purchases").document(purchaseId).update(
        mapOf(
          "itemName" to cleanItem,
          "marketName" to cleanMarket,
          "boughtPrice" to safePrice
        )
      ).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "market_purchases/$purchaseId")
      Result.failure(e)
    }
  }

  suspend fun adminDeleteMarketPurchase(purchaseId: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      db.collection("market_purchases").document(purchaseId).delete().await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, "market_purchases/$purchaseId")
      Result.failure(e)
    }
  }

  suspend fun adminSendNotification(
    targetUid: String,
    title: String,
    message: String
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      val cleanUid = targetUid.trim().take(128)
      val cleanMsg = InputSanitizer.sanitizeForSubmit(message, InputSanitizer.MAX_ADMIN_MESSAGE_LEN, allowNewlines = true)
      if (cleanUid.isBlank() || cleanMsg.isBlank()) {
        return@withContext Result.failure(IllegalArgumentException("Target UID and message are required."))
      }
      val doc = db.collection("notifications").document()
      doc.set(
        mapOf(
          "targetUid" to cleanUid,
          "title" to InputSanitizer.sanitizeForSubmit(title, InputSanitizer.MAX_ADMIN_TITLE_LEN).ifBlank { "Paircart Admin" },
          "message" to cleanMsg,
          "type" to if (cleanUid == "ALL") "admin_broadcast" else "admin_message",
          "createdAt" to FieldValue.serverTimestamp()
        )
      ).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, "notifications")
      Result.failure(e)
    }
  }

  suspend fun adminBroadcastNotification(
    title: String,
    message: String
  ): Result<Unit> = adminSendNotification(
    targetUid = "ALL",
    title = title.trim().ifBlank { "Paircart Notice" },
    message = message
  )

  suspend fun adminUpdateNotification(
    notificationId: String,
    title: String,
    message: String
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      val cleanId = notificationId.trim()
      val cleanMsg = InputSanitizer.sanitizeForSubmit(message, InputSanitizer.MAX_ADMIN_MESSAGE_LEN, allowNewlines = true)
      if (cleanId.isBlank() || cleanMsg.isBlank()) {
        return@withContext Result.failure(IllegalArgumentException("Notification ID and message are required."))
      }
      db.collection("notifications").document(cleanId).update(
        mapOf(
          "title" to InputSanitizer.sanitizeForSubmit(title, InputSanitizer.MAX_ADMIN_TITLE_LEN).ifBlank { "Paircart Notice" },
          "message" to cleanMsg,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      ).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "notifications/$notificationId")
      Result.failure(e)
    }
  }

  suspend fun adminDeleteNotification(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      db.collection("notifications").document(notificationId).delete().await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, "notifications/$notificationId")
      Result.failure(e)
    }
  }

  fun observeAppConfig(): Flow<AppConfig> = callbackFlow {
    val docRef = db.collection("app_config").document("global")
    val reg = docRef.addSnapshotListener { snap, err ->
      if (err != null) {
        return@addSnapshotListener
      }
      val coinEnabled = snap?.getBoolean("coinFeatureEnabled") ?: true
      val appEnabled = snap?.getBoolean("appEnabled") ?: true
      trySend(AppConfig(coinFeatureEnabled = coinEnabled, appEnabled = appEnabled))
    }
    awaitClose { reg.remove() }
  }

  suspend fun adminUpdateCoinFeatureEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      db.collection("app_config").document("global").set(
        mapOf("coinFeatureEnabled" to enabled),
        com.google.firebase.firestore.SetOptions.merge()
      ).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "app_config/global")
      Result.failure(e)
    }
  }

  suspend fun adminUpdateAppEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      requireAdmin()
      db.collection("app_config").document("global").set(
        mapOf("appEnabled" to enabled),
        com.google.firebase.firestore.SetOptions.merge()
      ).await()
      Result.success(Unit)
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "app_config/global")
      Result.failure(e)
    }
  }

  suspend fun ensureDefaultNoticesSeeded() = withContext(Dispatchers.IO) {
    try {
      if (!AdminConfig.isAdminEmail(auth.currentUser?.email)) return@withContext
      val configRef = db.collection("app_config").document("global")
      val configSnap = configRef.get().await()
      if (configSnap.getBoolean("defaultNoticesSeeded") == true) return@withContext

      val existingBroadcasts = db.collection("notifications")
        .whereEqualTo("targetUid", "ALL")
        .limit(1)
        .get()
        .await()

      if (existingBroadcasts.isEmpty) {
        val notice1 = mapOf(
          "targetUid" to "ALL",
          "title" to "Welcome to Paircart",
          "message" to "Connect using your partner's 6-digit ID to share grocery lists and orders in real time.",
          "type" to "admin_broadcast",
          "createdAt" to FieldValue.serverTimestamp()
        )
        val notice2 = mapOf(
          "targetUid" to "ALL",
          "title" to "180 Coins Special Gift",
          "message" to "Collect coins daily and reach 180 coins to claim your free delivery gift!",
          "type" to "admin_broadcast",
          "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("notifications").document("default_notice_1").set(notice1).await()
        db.collection("notifications").document("default_notice_2").set(notice2).await()
      }

      configRef.set(
        mapOf("defaultNoticesSeeded" to true),
        com.google.firebase.firestore.SetOptions.merge()
      ).await()
    } catch (e: Exception) {
      Log.w(TAG, "ensureDefaultNoticesSeeded warning: ${e.message}")
    }
  }
}
