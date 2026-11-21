package com.example.ui.order

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppVisibilityTracker
import com.example.data.model.GiftClaim
import com.example.data.model.MarketPurchase
import com.example.data.model.NotificationData
import com.example.data.model.Order
import com.example.data.model.OrderItemDetail
import com.example.data.model.UserProfile
import com.example.data.repository.OrderRepository
import com.example.service.InboxNotificationItem
import com.example.service.NotificationDeduplicator
import com.example.service.NotificationInboxStore
import com.example.service.OrderLifecycleManager
import com.example.util.AdminConfig
import com.example.util.InputSanitizer
import com.example.util.NetworkMonitor
import com.example.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private const val TAG = "OrderViewModel"

data class OrderUiState(
  val myOrders: List<Order> = emptyList(),
  val partnerOrders: List<Order> = emptyList(),
  val myNumericId: String = "",
  val partnerNumericId: String = "",
  val partnerUid: String = "",
  val partnerDisplayName: String = "",
  val partnerPhotoData: String = "",
  val partnerProfession: String = "",
  val partnerBirthday: String = "",
  val partnerAddress: String = "",
  val hasLoadedPartnerProfile: Boolean = false,
  val isLoading: Boolean = false,
  val errorMessage: String? = null,
  val bannerNotification: String? = null,
  val selectedTab: Int = 0, // 0: Partner Orders, 1: My Orders, 2: Profile, 3: Admin (if admin)
  val coins: Int = 0,
  val lastCoinDate: String = "",
  val showCoinAnimation: Boolean = false,
  val isAdmin: Boolean = false,
  val myGiftClaim: GiftClaim? = null,
  val isSubmittingGiftClaim: Boolean = false,
  val adminUsers: List<UserProfile> = emptyList(),
  val adminOrders: List<Order> = emptyList(),
  val adminGiftClaims: List<GiftClaim> = emptyList(),
  val adminMarketPurchases: List<MarketPurchase> = emptyList(),
  val adminNotifications: List<NotificationData> = emptyList(),
  val inboxNotifications: List<InboxNotificationItem> = emptyList(),
  val serverAnnouncements: List<InboxNotificationItem> = emptyList(),
  val coinFeatureEnabled: Boolean = true,
  val appEnabled: Boolean = true,
  val isOnline: Boolean = true,
  val isAppEntryLoading: Boolean = true,
  val hasLoadedMyOrders: Boolean = false,
  val hasLoadedPartnerOrders: Boolean = false,
  val hasLoadedProfile: Boolean = false,
  val hasLoadedAdminData: Boolean = false,
  val isSendingOrder: Boolean = false,
  val isWaitingOfflineToSendOrder: Boolean = false,
  val isConnectingPartner: Boolean = false,
  val busyOrderIds: Set<String> = emptySet(),
  val profilePhotoData: String = "",
  val profileBirthday: String = "",
  val profileProfession: String = "",
  val profileAddress: String = "",
  val lastProfileEditMonth: String = "",
  val lastProfileEditAtMs: Long = 0L,
  val isSavingProfile: Boolean = false
) {
  val isPartnerConnected: Boolean get() = partnerUid.isNotBlank()
  val unreadNotificationCount: Int get() = inboxNotifications.count { !it.isRead }

  // My spent total: orders placed by partner where I purchased items
  val mySpentTotal: Double get() = partnerOrders.sumOf { it.totalCost }

  // Partner spent total: orders placed by me where partner purchased items
  val partnerSpentTotal: Double get() = myOrders.sumOf { it.totalCost }

  val grandTotalCost: Double get() = mySpentTotal + partnerSpentTotal
}

class OrderViewModel(
  private val repository: OrderRepository,
  private val appContext: Context
) : ViewModel() {

  private val _uiState = MutableStateFlow(OrderUiState())
  val uiState: StateFlow<OrderUiState> = _uiState.asStateFlow()

  private var previousPartnerOrderCount = -1
  private var previousCompletedOrders = setOf<String>()
  private var previousAcceptedOrders = setOf<String>()
  private var previousRejectedOrders = setOf<String>()
  private var previousIncompleteOrders = setOf<String>()
  private var previousExpiredOrders = setOf<String>()
  private var isFirstMyOrdersSnapshot = true
  private var isFirstPartnerOrdersSnapshot = true
  private val sessionHandledNotifications = mutableSetOf<String>()
  private val sessionExpiredRequests = mutableSetOf<String>()
  private var pendingOfflineOrder: Pair<List<OrderItemDetail>, () -> Unit>? = null
  private var entryLoadingJob: kotlinx.coroutines.Job? = null
  private var hasStartedInitialProfileSync = false

  init {
    Log.d(TAG, "init: OrderViewModel created")
    val initialOnline = NetworkMonitor.isOnlineNow(appContext)
    _uiState.update { it.copy(isOnline = initialOnline, isAppEntryLoading = true) }

    viewModelScope.launch {
      NetworkMonitor.observeOnlineState(appContext).collect { online ->
        val wasOffline = !_uiState.value.isOnline
        _uiState.update { it.copy(isOnline = online) }
        if (online) {
          val pending = pendingOfflineOrder
          if (pending != null) {
            pendingOfflineOrder = null
            performSendInvoiceOrder(pending.first, pending.second)
          }
          if (wasOffline && Firebase.auth.currentUser != null && !_uiState.value.hasLoadedProfile) {
            onSessionResumed()
          }
        }
      }
    }

    viewModelScope.launch {
      OrderLifecycleManager.foregroundEvents.collect { (eventUid, item) ->
        val currentUid = Firebase.auth.currentUser?.uid
        if (currentUid != null && eventUid == currentUid) {
          val bannerText = if (item.title.isNotBlank()) "${item.title}: ${item.message}" else item.message
          _uiState.update { it.copy(bannerNotification = bannerText) }
          rebuildInboxNotifications()
        }
      }
    }
    try {
      initCurrentUser()
    } catch (e: Throwable) {
      Log.e(TAG, "init: Error in initCurrentUser", e)
      _uiState.update {
        it.copy(
          isAppEntryLoading = false,
          errorMessage = "Initialization error: ${e.localizedMessage}"
        )
      }
    }
  }

  private fun startAppEntryLoadingGuard() {
    entryLoadingJob?.cancel()
    _uiState.update { it.copy(isAppEntryLoading = true) }
    entryLoadingJob = viewModelScope.launch {
      delay(400L)
      val waitDeadline = System.currentTimeMillis() + 1200L
      while (System.currentTimeMillis() < waitDeadline) {
        val st = _uiState.value
        val onlineDone = st.isOnline && st.hasLoadedProfile && st.hasLoadedMyOrders && st.hasLoadedPartnerOrders
        val offlineDone = !st.isOnline && (System.currentTimeMillis() >= waitDeadline - 700L)
        if (onlineDone || offlineDone) break
        delay(100L)
      }
      _uiState.update { it.copy(isAppEntryLoading = false) }
    }
  }

  private fun getPrefs(): android.content.SharedPreferences {
    val uid = Firebase.auth.currentUser?.uid ?: "default_user"
    return appContext.getSharedPreferences("orderbuddy_prefs_$uid", Context.MODE_PRIVATE)
  }

  private fun initCurrentUser() {
    Log.d(TAG, "initCurrentUser: starting")
    val currentUser = Firebase.auth.currentUser
    if (currentUser == null) {
      Log.w(TAG, "initCurrentUser: currentUser is null")
      _uiState.update { it.copy(isAppEntryLoading = false) }
      return
    }
    if (!hasStartedInitialProfileSync) {
      hasStartedInitialProfileSync = true
      startAppEntryLoadingGuard()
    }
    
    val email = currentUser.email ?: "user@paircart.app"
    val displayName = currentUser.displayName ?: email.substringBefore("@")
    val numericId = UserProfile.generateNumericId(email)
    val adminAccount = AdminConfig.isAdminEmail(email)

    Log.d(TAG, "initCurrentUser: numericId=$numericId, email=$email, isAdmin=$adminAccount")

    val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    // 1. Immediately restore locally saved partner & coins
    val prefs = getPrefs()
    val savedPartnerId = prefs.getString("partner_numeric_id", "") ?: ""
    val savedPartnerUid = prefs.getString("partner_uid", "") ?: ""
    val savedPartnerName = prefs.getString("partner_name", "") ?: ""
    val savedCoins = prefs.getInt("user_coins", 0)
    val savedLastCoinDate = prefs.getString("last_coin_date", "") ?: ""
    val savedPhoto = prefs.getString("profile_photo_data", "") ?: ""
    val savedBirthday = prefs.getString("profile_birthday", "") ?: ""
    val savedProfession = prefs.getString("profile_profession", "") ?: ""
    val savedAddress = prefs.getString("profile_address", "") ?: ""
    val savedEditMonth = prefs.getString("last_profile_edit_month", "") ?: ""
    val savedEditAtMs = prefs.getLong("last_profile_edit_at_ms", 0L)

    Log.d(TAG, "initCurrentUser: restoring saved partner: id=$savedPartnerId, coins=$savedCoins, lastDate=$savedLastCoinDate, today=$todayDate")

    _uiState.update {
      it.copy(
        myNumericId = numericId,
        partnerNumericId = savedPartnerId,
        partnerUid = savedPartnerUid,
        partnerDisplayName = savedPartnerName,
        coins = savedCoins,
        lastCoinDate = savedLastCoinDate,
        showCoinAnimation = false, // Keep false until Firestore profile sync completes to prevent flickering
        isAdmin = adminAccount,
        selectedTab = 0,
        profilePhotoData = savedPhoto,
        profileBirthday = savedBirthday,
        profileProfession = savedProfession,
        profileAddress = savedAddress,
        lastProfileEditMonth = savedEditMonth,
        lastProfileEditAtMs = savedEditAtMs
      )
    }

    // Load locally saved notification inbox immediately and subscribe to incoming & broadcast notifications
    rebuildInboxNotifications()
    observeIncomingNotifications()
    observeBroadcastNotifications()

    // Always observe user's own gift claim (180+ coins)
    observeMyGiftClaim()
    observeAppConfig()

    // Always load orders so historical transactions and bill summaries are visible even when disconnected
    loadOrders()
    observePartnerProfile(savedPartnerId)

    // If Admin account (zahidhasan2000joy@gmail.com), ensure default server notices exist and observe all app collections in real-time
    if (adminAccount) {
      viewModelScope.launch {
        repository.ensureDefaultNoticesSeeded()
      }
      observeAdminCollections()
    }

    // 2. Also sync with Firestore user document
    viewModelScope.launch {
      try {
        Log.d(TAG, "initCurrentUser: registering user profile in Firestore")
        val regResult = repository.registerUserProfile(email, displayName)
        if (regResult.isSuccess) {
          val profile = regResult.getOrNull()
          Log.d(TAG, "initCurrentUser: Firestore registration success: ${profile?.uid}")
          if (profile != null) {
            val firestoreLastDate = profile.lastCoinDate
            val firestoreCoins = profile.coins
            
            val isCollectedToday = (savedLastCoinDate == todayDate || firestoreLastDate == todayDate)
            val effectiveCoins = maxOf(savedCoins, firestoreCoins)
            val effectiveLastDate = if (isCollectedToday) todayDate else if (firestoreLastDate.isNotBlank()) firestoreLastDate else savedLastCoinDate

            val effPhoto = profile.profilePhotoData
            val effBirthday = profile.birthday
            val effProfession = profile.profession
            val effAddress = profile.address
            val effEditMonth = profile.lastProfileEditMonth
            val effEditAtMs = profile.lastProfileEditAtMs

            prefs.edit()
              .putString("last_coin_date", effectiveLastDate)
              .putInt("user_coins", effectiveCoins)
              .putString("profile_photo_data", effPhoto)
              .putString("profile_birthday", effBirthday)
              .putString("profile_profession", effProfession)
              .putString("profile_address", effAddress)
              .putString("last_profile_edit_month", effEditMonth)
              .putLong("last_profile_edit_at_ms", effEditAtMs)
              .commit()

            val shouldShowPopup = !isCollectedToday

            _uiState.update {
              it.copy(
                hasLoadedProfile = true,
                coins = effectiveCoins,
                lastCoinDate = effectiveLastDate,
                showCoinAnimation = shouldShowPopup,
                profilePhotoData = effPhoto,
                profileBirthday = effBirthday,
                profileProfession = effProfession,
                profileAddress = effAddress,
                lastProfileEditMonth = effEditMonth,
                lastProfileEditAtMs = effEditAtMs
              )
            }

            if (savedLastCoinDate == todayDate) {
              repository.updateUserCoins(numericId, effectiveCoins, todayDate)
            }

            if (profile.connectedPartnerId.isNotBlank() && (_uiState.value.partnerUid.isBlank() || _uiState.value.partnerUid.length < 15)) {
              Log.d(TAG, "initCurrentUser: Applying partner from Firestore profile")
              saveAndApplyPartner(
                partnerId = profile.connectedPartnerId,
                partnerUid = profile.connectedPartnerUid,
                partnerName = profile.connectedPartnerName,
                showBanner = false
              )
            }
          } else {
            _uiState.update { it.copy(hasLoadedProfile = true) }
          }
          observeMyProfile(numericId)
        } else {
          Log.e(TAG, "initCurrentUser: Firestore registration failed", regResult.exceptionOrNull())
          if (NetworkMonitor.isOnlineNow(appContext)) {
            _uiState.update { it.copy(hasLoadedProfile = true) }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "initCurrentUser: exception during Firestore sync", e)
        if (NetworkMonitor.isOnlineNow(appContext)) {
          _uiState.update { it.copy(hasLoadedProfile = true) }
        }
      }
    }
  }

  private fun saveAndApplyPartner(
    partnerId: String,
    partnerUid: String,
    partnerName: String,
    showBanner: Boolean = false
  ) {
    val partnerChanged = _uiState.value.partnerUid != partnerUid
    Log.d(TAG, "saveAndApplyPartner: id=$partnerId, name=$partnerName, partnerChanged=$partnerChanged, showBanner=$showBanner")
    getPrefs().edit()
      .putString("partner_numeric_id", partnerId)
      .putString("partner_uid", partnerUid)
      .putString("partner_name", partnerName)
      .commit()

    _uiState.update {
      it.copy(
        partnerNumericId = partnerId,
        partnerUid = partnerUid,
        partnerDisplayName = partnerName,
        bannerNotification = if (showBanner) "Connected with $partnerName (ID: $partnerId)!" else it.bannerNotification
      )
    }

    val myId = _uiState.value.myNumericId
    viewModelScope.launch {
      try {
        repository.updateConnectedPartner(myId, partnerId, partnerUid, partnerName)
      } catch (e: Exception) {
        Log.e(TAG, "saveAndApplyPartner: failed to update connected partner in Firestore", e)
      }
    }

    if (partnerChanged || myOrdersJob?.isActive != true || partnerOrdersJob?.isActive != true) {
      loadOrders()
    }
    observePartnerProfile(partnerId)
  }

  private fun isNotificationHandled(key: String): Boolean {
    if (sessionHandledNotifications.contains(key)) return true
    val uid = Firebase.auth.currentUser?.uid ?: "default_user"
    return NotificationDeduplicator.isHandled(appContext, uid, key)
  }

  private fun markNotificationHandled(key: String) {
    sessionHandledNotifications.add(key)
    val uid = Firebase.auth.currentUser?.uid ?: "default_user"
    NotificationDeduplicator.markHandled(appContext, uid, key)
  }

  private fun tryMarkNotificationHandled(key: String): Boolean {
    if (sessionHandledNotifications.contains(key)) return false
    val uid = Firebase.auth.currentUser?.uid ?: "default_user"
    val isNew = NotificationDeduplicator.tryMarkHandled(appContext, uid, key)
    sessionHandledNotifications.add(key)
    return isNew
  }

  private fun unmarkNotificationHandled(key: String) {
    sessionHandledNotifications.remove(key)
    val uid = Firebase.auth.currentUser?.uid ?: "default_user"
    NotificationDeduplicator.unmarkHandled(appContext, uid, key)
  }

  private fun isWithinFreshWindow(timestamp: com.google.firebase.Timestamp?, windowMs: Long = 10 * 60 * 1000L): Boolean {
    val timeMs = timestamp?.toDate()?.time ?: return true
    val ageMs = System.currentTimeMillis() - timeMs
    // Allow slight negative clock skew (-5 min) and up to windowMs in the past
    return ageMs <= windowMs && ageMs >= -5 * 60 * 1000L
  }

  private fun deliverOrderNotification(
    title: String,
    message: String,
    bannerMessage: String,
    notificationId: Int
  ): Boolean {
    val inForeground = AppVisibilityTracker.isInForeground
    val shown = NotificationHelper.showNotification(
      context = appContext,
      title = title,
      message = message,
      notificationId = notificationId,
      silent = inForeground
    )
    if (inForeground) {
      NotificationHelper.playCustomNotificationSound(appContext)
      _uiState.update { it.copy(bannerNotification = bannerMessage) }
    }
    return shown || inForeground
  }

  private fun observeIncomingNotifications() {
    incomingNotificationsJob?.cancel()
    incomingNotificationsJob = viewModelScope.launch {
      try {
        Log.d(TAG, "observeIncomingNotifications: starting")
        val uid = Firebase.auth.currentUser?.uid ?: "default_user"
        repository.observeIncomingNotifications()
          .collect { notifications ->
            val completedOrderIdsInBatch = notifications
              .filter { it.type == "order_completed" && it.orderId.isNotBlank() }
              .map { it.orderId }
              .toSet()

            notifications.forEach { it ->
              val docKey = "msg_${it.id}"
              val canonicalKey = NotificationDeduplicator.canonicalKey(it)
              val eventType = when (it.type) {
                "new_order" -> "new_order"
                "order_accepted" -> "accepted"
                "order_completed" -> "completed"
                "order_rejected" -> "rejected"
                "order_incomplete" -> "incomplete"
                "order_expired" -> "expired"
                else -> ""
              }

              // If the same order already completed in this batch, suppress superseded accepted notification
              if (it.type == "order_accepted" && it.orderId in completedOrderIdsInBatch) {
                markNotificationHandled(canonicalKey)
                markNotificationHandled(docKey)
                repository.clearNotification(it.id)
                return@forEach
              }

              // Suppress duplicate legacy notifications (blank orderId from older sender app) if orders listener already notified
              if (it.orderId.isBlank() && eventType.isNotBlank() &&
                NotificationDeduplicator.wasRecentOrderEventShown(appContext, uid, eventType)
              ) {
                markNotificationHandled(docKey)
                repository.clearNotification(it.id)
                return@forEach
              }

              if (!isNotificationHandled(docKey) && tryMarkNotificationHandled(canonicalKey)) {
                if (it.type == "order_completed" && it.orderId.isNotBlank()) {
                  markNotificationHandled("accepted_${it.orderId}")
                }
                val notifId = when {
                  it.type == "partner_connected" -> "partner_connected_${it.senderUid.ifBlank { it.id }}".hashCode()
                  it.orderId.isNotBlank() -> canonicalKey.hashCode()
                  eventType.isNotBlank() -> "legacy_$eventType".hashCode()
                  else -> canonicalKey.hashCode()
                }
                val delivered = if (it.message.isNotBlank()) {
                  deliverOrderNotification(
                    title = it.title.ifBlank { "Paircart" },
                    message = it.message,
                    bannerMessage = it.message,
                    notificationId = notifId
                  )
                } else {
                  true
                }

                if (delivered) {
                  if (it.message.isNotBlank()) {
                    NotificationInboxStore.addOrUpdate(
                      context = appContext,
                      uid = uid,
                      item = InboxNotificationItem(
                        id = canonicalKey,
                        title = it.title.ifBlank { "Paircart" },
                        message = it.message,
                        type = it.type,
                        orderId = it.orderId,
                        createdAtMs = it.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        isRead = false,
                        isBroadcast = false
                      )
                    )
                    rebuildInboxNotifications()
                  }
                  if (eventType.isNotBlank()) {
                    NotificationDeduplicator.recordOrderEventShown(appContext, uid, eventType)
                    if (it.orderId.isBlank()) {
                      NotificationDeduplicator.recordLegacyNotifShown(appContext, uid, eventType)
                    }
                  }
                  markNotificationHandled(docKey)
                  // If partner connected with us, save and auto-connect mutually!
                  if (it.type == "partner_connected" && it.senderNumericId.isNotBlank() && it.senderUid.isNotBlank()) {
                    if (_uiState.value.partnerUid.isBlank()) {
                      saveAndApplyPartner(
                        partnerId = it.senderNumericId,
                        partnerUid = it.senderUid,
                        partnerName = it.senderName,
                        showBanner = AppVisibilityTracker.isInForeground
                      )
                    }
                  }
                  repository.clearNotification(it.id)
                } else {
                  unmarkNotificationHandled(canonicalKey)
                }
              } else {
                markNotificationHandled(docKey)
                repository.clearNotification(it.id)
              }
            }
          }
      } catch (e: Exception) {
        Log.e(TAG, "observeIncomingNotifications: error", e)
      }
    }
  }

  private var myOrdersJob: kotlinx.coroutines.Job? = null
  private var partnerOrdersJob: kotlinx.coroutines.Job? = null

  private fun loadOrders() {
    val partnerUid = _uiState.value.partnerUid
    val uid = Firebase.auth.currentUser?.uid ?: "default_user"
    Log.d(TAG, "loadOrders: partnerUid=$partnerUid")
    
    // Cancel existing jobs
    myOrdersJob?.cancel()
    partnerOrdersJob?.cancel()

    // Reset snapshot flags when starting a new listener
    isFirstMyOrdersSnapshot = true
    isFirstPartnerOrdersSnapshot = true
    previousPartnerOrderCount = -1
    _uiState.update { it.copy(hasLoadedMyOrders = false, hasLoadedPartnerOrders = false) }
    
    // Keep observing orders even when partnerUid is blank so historical data and summary remain visible

    myOrdersJob = viewModelScope.launch {
      Log.d(TAG, "loadOrders: observing my orders")
      repository.observeMyOrders(partnerUid)
        .distinctUntilChanged()
        .catch { error ->
          Log.e(TAG, "loadOrders: error observing my orders", error)
          if (Firebase.auth.currentUser != null && !error.message.orEmpty().contains("PERMISSION_DENIED")) {
            _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Failed to load orders") }
          }
        }
        .collect { orders ->
          Log.d(TAG, "loadOrders: my orders collected: count=${orders.size}")
          val completedNow = orders.filter { it.status == "completed" }.map { it.id }.toSet()
          val acceptedNow = orders.filter { it.status == "accepted" }.map { it.id }.toSet()
          val rejectedNow = orders.filter { it.status == "rejected" }.map { it.id }.toSet()
          val incompleteNow = orders.filter { it.status == "incomplete" }.map { it.id }.toSet()
          val expiredNow = orders.filter { it.status == "expired" }.map { it.id }.toSet()

          if (isFirstMyOrdersSnapshot) {
            isFirstMyOrdersSnapshot = false
            // Never flood notifications on initial snapshot; unread notifications arrive via observeIncomingNotifications()
            orders.forEach { ord ->
              markNotificationHandled("completed_${ord.id}")
              markNotificationHandled("accepted_${ord.id}")
              markNotificationHandled("rejected_${ord.id}")
              markNotificationHandled("incomplete_${ord.id}")
              if (!isWithinFreshWindow(ord.updatedAt ?: ord.createdAt, windowMs = 12 * 60 * 60_000L)) {
                markNotificationHandled(OrderLifecycleManager.expiredKey(ord.id))
              }
            }
            getPrefs().edit().putBoolean("my_orders_baseline_done", true).commit()
            previousCompletedOrders = completedNow
            previousAcceptedOrders = acceptedNow
            previousRejectedOrders = rejectedNow
            previousIncompleteOrders = incompleteNow
            previousExpiredOrders = expiredNow
          } else {
            // A. Check for completions
            val newlyCompleted = completedNow - previousCompletedOrders
            if (newlyCompleted.isNotEmpty()) {
              newlyCompleted.forEach { id ->
                val notifKey = "completed_$id"
                markNotificationHandled("accepted_$id")
                val completedOrder = orders.firstOrNull { it.id == id }
                val isFresh = isWithinFreshWindow(completedOrder?.updatedAt ?: completedOrder?.createdAt)
                if (isFresh && !NotificationDeduplicator.wasRecentLegacyNotifShown(appContext, uid, "completed")) {
                  if (tryMarkNotificationHandled(notifKey)) {
                    val amount = completedOrder?.totalCost ?: 0.0
                    val msg = "Order ${completedOrder?.orderNumber ?: ""} was completed by partner! Total: ৳${String.format(Locale.US, "%.2f", amount)}"
                    val delivered = deliverOrderNotification(
                      title = "Order Completed",
                      message = msg,
                      bannerMessage = msg,
                      notificationId = notifKey.hashCode()
                    )
                    if (!delivered) {
                      unmarkNotificationHandled(notifKey)
                    } else {
                      NotificationDeduplicator.recordOrderEventShown(appContext, uid, "completed")
                      NotificationInboxStore.addOrUpdate(
                        context = appContext,
                        uid = uid,
                        item = InboxNotificationItem(
                          id = notifKey,
                          title = "Order Completed",
                          message = msg,
                          type = "order_completed",
                          orderId = id,
                          createdAtMs = (completedOrder?.updatedAt ?: completedOrder?.createdAt)?.toDate()?.time ?: System.currentTimeMillis(),
                          isRead = false,
                          isBroadcast = false
                        )
                      )
                      rebuildInboxNotifications()
                    }
                  }
                } else {
                  markNotificationHandled(notifKey)
                }
              }
            }
            previousCompletedOrders = completedNow

            // B. Check for acceptances (Notification for sender when partner accepts)
            val newlyAccepted = acceptedNow - previousAcceptedOrders
            if (newlyAccepted.isNotEmpty()) {
              newlyAccepted.forEach { id ->
                val notifKey = "accepted_$id"
                val acceptedOrder = orders.firstOrNull { it.id == id }
                val isFresh = isWithinFreshWindow(acceptedOrder?.updatedAt ?: acceptedOrder?.createdAt)
                if (isFresh && !NotificationDeduplicator.wasRecentLegacyNotifShown(appContext, uid, "accepted")) {
                  if (tryMarkNotificationHandled(notifKey)) {
                    val durSuffix = if ((acceptedOrder?.shoppingDurationMinutes ?: 0) > 0) {
                      " (Est. ${acceptedOrder?.shoppingDurationMinutes} mins)"
                    } else ""
                    val msg = "Partner accepted your order ${acceptedOrder?.orderNumber ?: ""}$durSuffix."
                    val delivered = deliverOrderNotification(
                      title = "Order Accepted",
                      message = msg,
                      bannerMessage = msg,
                      notificationId = notifKey.hashCode()
                    )
                    if (!delivered) {
                      unmarkNotificationHandled(notifKey)
                    } else {
                      NotificationDeduplicator.recordOrderEventShown(appContext, uid, "accepted")
                      NotificationInboxStore.addOrUpdate(
                        context = appContext,
                        uid = uid,
                        item = InboxNotificationItem(
                          id = notifKey,
                          title = "Order Accepted",
                          message = msg,
                          type = "order_accepted",
                          orderId = id,
                          createdAtMs = (acceptedOrder?.updatedAt ?: acceptedOrder?.createdAt)?.toDate()?.time ?: System.currentTimeMillis(),
                          isRead = false,
                          isBroadcast = false
                        )
                      )
                      rebuildInboxNotifications()
                    }
                  }
                } else {
                  markNotificationHandled(notifKey)
                }
              }
            }
            previousAcceptedOrders = acceptedNow

            // C. Check for rejections
            val newlyRejected = rejectedNow - previousRejectedOrders
            newlyRejected.forEach { id ->
              val notifKey = "rejected_$id"
              val ord = orders.firstOrNull { it.id == id }
              val isFresh = isWithinFreshWindow(ord?.updatedAt ?: ord?.createdAt)
              if (isFresh && tryMarkNotificationHandled(notifKey)) {
                val reasonSuffix = if (!ord?.rejectionReason.isNullOrBlank()) " Reason: ${ord?.rejectionReason}" else ""
                val msg = "Partner rejected your order ${ord?.orderNumber ?: ""}.$reasonSuffix"
                val delivered = deliverOrderNotification("Order Rejected", msg, msg, notifKey.hashCode())
                if (!delivered) {
                  unmarkNotificationHandled(notifKey)
                } else {
                  NotificationInboxStore.addOrUpdate(
                    context = appContext,
                    uid = uid,
                    item = InboxNotificationItem(
                      id = notifKey,
                      title = "Order Rejected",
                      message = msg,
                      type = "order_rejected",
                      orderId = id,
                      createdAtMs = (ord?.updatedAt ?: ord?.createdAt)?.toDate()?.time ?: System.currentTimeMillis(),
                      isRead = false,
                      isBroadcast = false
                    )
                  )
                  rebuildInboxNotifications()
                }
              }
            }
            previousRejectedOrders = rejectedNow

            // D. Check for incomplete
            val newlyIncomplete = incompleteNow - previousIncompleteOrders
            newlyIncomplete.forEach { id ->
              val notifKey = "incomplete_$id"
              val ord = orders.firstOrNull { it.id == id }
              val isFresh = isWithinFreshWindow(ord?.updatedAt ?: ord?.createdAt)
              if (isFresh && tryMarkNotificationHandled(notifKey)) {
                val reasonSuffix = if (!ord?.incompleteReason.isNullOrBlank()) " Reason: ${ord?.incompleteReason}" else ""
                val msg = "Order ${ord?.orderNumber ?: ""} was marked Incomplete.$reasonSuffix"
                val delivered = deliverOrderNotification("Order Incomplete", msg, msg, notifKey.hashCode())
                if (!delivered) {
                  unmarkNotificationHandled(notifKey)
                } else {
                  NotificationInboxStore.addOrUpdate(
                    context = appContext,
                    uid = uid,
                    item = InboxNotificationItem(
                      id = notifKey,
                      title = "Order Incomplete",
                      message = msg,
                      type = "order_incomplete",
                      orderId = id,
                      createdAtMs = (ord?.updatedAt ?: ord?.createdAt)?.toDate()?.time ?: System.currentTimeMillis(),
                      isRead = false,
                      isBroadcast = false
                    )
                  )
                  rebuildInboxNotifications()
                }
              }
            }
            previousIncompleteOrders = incompleteNow

            // E. Check for expired orders
            val newlyExpired = expiredNow - previousExpiredOrders
            newlyExpired.forEach { id ->
              val notifKey = OrderLifecycleManager.expiredKey(id)
              val ord = orders.firstOrNull { it.id == id }
              val ageSinceExpireMs = if ((ord?.effectiveCreatedAtMs ?: 0L) > 0L) {
                System.currentTimeMillis() - (ord!!.effectiveCreatedAtMs + Order.EXPIRE_WINDOW_MS)
              } else 0L
              if (ageSinceExpireMs in -60_000L..(12 * 60 * 60_000L) && tryMarkNotificationHandled(notifKey)) {
                val partner = ord?.partnerName?.ifBlank { "Partner" } ?: "Partner"
                val msg = "Your order ${ord?.orderNumber ?: ""} expired automatically because $partner did not respond within 30 mins."
                val delivered = deliverOrderNotification("Order Expired", msg, msg, notifKey.hashCode())
                if (!delivered) {
                  unmarkNotificationHandled(notifKey)
                } else {
                  NotificationInboxStore.addOrUpdate(
                    context = appContext,
                    uid = uid,
                    item = InboxNotificationItem(
                      id = notifKey,
                      title = "Order Expired",
                      message = msg,
                      type = "order_expired",
                      orderId = id,
                      createdAtMs = (ord?.updatedAt ?: ord?.createdAt)?.toDate()?.time ?: System.currentTimeMillis(),
                      isRead = false,
                      isBroadcast = false
                    )
                  )
                  rebuildInboxNotifications()
                }
              } else {
                markNotificationHandled(notifKey)
              }
            }
            previousExpiredOrders = expiredNow
          }

          val sortedOrders = orders.sortedByDescending { order ->
            order.createdAt?.toDate()?.time ?: System.currentTimeMillis()
          }
          _uiState.update { it.copy(myOrders = sortedOrders, hasLoadedMyOrders = true) }
          evaluateLifecycleTimersNow()
        }
    }

    partnerOrdersJob = viewModelScope.launch {
      Log.d(TAG, "loadOrders: observing partner orders")
      repository.observePartnerOrders(partnerUid)
        .distinctUntilChanged()
        .catch { error ->
          Log.e(TAG, "loadOrders: error observing partner orders", error)
          if (Firebase.auth.currentUser != null && !error.message.orEmpty().contains("PERMISSION_DENIED")) {
            _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Failed to load partner orders") }
          }
        }
        .collect { orders ->
          Log.d(TAG, "loadOrders: partner orders collected: count=${orders.size}")
          val sortedPartnerOrders = orders.sortedByDescending { order ->
            order.createdAt?.toDate()?.time ?: System.currentTimeMillis()
          }
          if (isFirstPartnerOrdersSnapshot) {
            isFirstPartnerOrdersSnapshot = false
            // Never flood notifications on initial snapshot; unread notifications arrive via observeIncomingNotifications()
            sortedPartnerOrders.forEach { ord ->
              markNotificationHandled("new_order_${ord.id}")
            }
            getPrefs().edit().putBoolean("partner_orders_baseline_done", true).commit()
            previousPartnerOrderCount = sortedPartnerOrders.size
          } else {
            val newOrders = sortedPartnerOrders.filter {
              (it.status == "pending" || it.status == "accepted") && !isNotificationHandled("new_order_${it.id}")
            }
            newOrders.forEach { newOrder ->
              val notifKey = "new_order_${newOrder.id}"
              if (isWithinFreshWindow(newOrder.createdAt) &&
                !NotificationDeduplicator.wasRecentLegacyNotifShown(appContext, uid, "new_order")
              ) {
                if (tryMarkNotificationHandled(notifKey)) {
                  val sender = newOrder.userName.ifBlank { "Partner" }
                  val count = newOrder.typedItems.size
                  val title = "New Order from $sender"
                  val msg = "$sender sent a new order ${newOrder.orderNumber} with $count item(s)."
                  val delivered = deliverOrderNotification(
                    title = title,
                    message = msg,
                    bannerMessage = "$title: $msg",
                    notificationId = notifKey.hashCode()
                  )
                  if (!delivered) {
                    unmarkNotificationHandled(notifKey)
                  } else {
                    NotificationDeduplicator.recordOrderEventShown(appContext, uid, "new_order")
                    NotificationInboxStore.addOrUpdate(
                      context = appContext,
                      uid = uid,
                      item = InboxNotificationItem(
                        id = notifKey,
                        title = title,
                        message = msg,
                        type = "new_order",
                        orderId = newOrder.id,
                        createdAtMs = newOrder.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        isRead = false,
                        isBroadcast = false
                      )
                    )
                    rebuildInboxNotifications()
                  }
                }
              } else {
                markNotificationHandled(notifKey)
              }
            }
            previousPartnerOrderCount = sortedPartnerOrders.size
          }
          _uiState.update { it.copy(partnerOrders = sortedPartnerOrders, hasLoadedPartnerOrders = true) }
          evaluateLifecycleTimersNow()
        }
    }
  }

  fun evaluateLifecycleTimersNow() {
    val uid = Firebase.auth.currentUser?.uid ?: return
    val state = _uiState.value
    val toExpire = com.example.service.OrderLifecycleManager.syncOrdersAndScheduleAlarms(
      context = appContext,
      uid = uid,
      myOrders = state.myOrders,
      partnerOrders = state.partnerOrders
    )
    rebuildInboxNotifications()
    if (toExpire.isNotEmpty()) {
      toExpire.forEach { exp ->
        if (sessionExpiredRequests.add(exp.id)) {
          viewModelScope.launch {
            repository.expireOrderIfPending(exp.id)
          }
        }
      }
    }
  }

  fun connectPartnerWithNumericId(numericId: String) {
    val cleanId = InputSanitizer.filterDigitsOnly(numericId.trim(), InputSanitizer.NUMERIC_ID_MAX_LEN)
    Log.d(TAG, "connectPartnerWithNumericId: numericId=$cleanId")
    if (!InputSanitizer.isValidNumericId(cleanId)) {
      _uiState.update { it.copy(errorMessage = "Please enter a valid 5–6 digit numeric ID") }
      return
    }
    if (cleanId == _uiState.value.myNumericId) {
      _uiState.update { it.copy(errorMessage = "You cannot link with your own ID! Enter your partner's ID.") }
      return
    }
    if (!NetworkMonitor.isOnlineNow(appContext)) {
      _uiState.update {
        it.copy(
          isOnline = false,
          errorMessage = "No internet connection. Connect to the internet to link with your partner."
        )
      }
      return
    }

    viewModelScope.launch {
      try {
        _uiState.update { it.copy(isLoading = true, isConnectingPartner = true) }
        val result = repository.getUserByNumericId(cleanId)
        if (result.isSuccess) {
          val profile = result.getOrNull()
          if (profile != null) {
            Log.d(TAG, "connectPartnerWithNumericId: partner found: ${profile.uid}")
            val partnerName = profile.displayName.ifBlank { profile.email }
            saveAndApplyPartner(
              partnerId = cleanId,
              partnerUid = profile.uid,
              partnerName = partnerName,
              showBanner = true
            )

            // Send a connection notification to the partner via Firestore
            val currentUser = Firebase.auth.currentUser
            val myName = currentUser?.displayName ?: currentUser?.email?.substringBefore("@") ?: "Partner"
            val myUid = currentUser?.uid ?: ""
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
              repository.sendConnectionNotification(profile.uid, myName, _uiState.value.myNumericId, myUid)
            }
          } else {
            Log.w(TAG, "connectPartnerWithNumericId: partner not found")
            _uiState.update {
              it.copy(
                errorMessage = "No partner found with ID $cleanId. Ask them to open Paircart!"
              )
            }
          }
        } else {
          Log.e(TAG, "connectPartnerWithNumericId: error", result.exceptionOrNull())
          _uiState.update {
            it.copy(
              errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Partner search failed"
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "connectPartnerWithNumericId: exception", e)
        _uiState.update { it.copy(errorMessage = "Search error: ${e.message}") }
      } finally {
        _uiState.update { it.copy(isLoading = false, isConnectingPartner = false) }
      }
    }
  }

  fun disconnectPartner() {
    Log.d(TAG, "disconnectPartner: starting")
    val myId = _uiState.value.myNumericId
    getPrefs().edit()
      .remove("partner_numeric_id")
      .remove("partner_uid")
      .remove("partner_name")
      .apply()

    partnerProfileJob?.cancel()
    _uiState.update {
      it.copy(
        partnerNumericId = "",
        partnerUid = "",
        partnerDisplayName = "",
        partnerPhotoData = "",
        partnerProfession = "",
        partnerBirthday = "",
        partnerAddress = "",
        hasLoadedPartnerProfile = true
      )
    }

    viewModelScope.launch {
      try {
        repository.clearConnectedPartner(myId)
      } catch (e: Exception) {
        Log.e(TAG, "disconnectPartner: failed to clear partner in Firestore", e)
      }
    }

    loadOrders()
  }

  fun setSelectedTab(index: Int) {
    _uiState.update { it.copy(selectedTab = index) }
  }

  // Create Invoice Order
  fun cancelPendingOrderSend() {
    pendingOfflineOrder = null
    _uiState.update {
      it.copy(
        isLoading = false,
        isSendingOrder = false,
        isWaitingOfflineToSendOrder = false
      )
    }
  }

  fun sendInvoiceOrder(
    items: List<OrderItemDetail>,
    onSuccess: () -> Unit = {}
  ) {
    val sanitizedItems = items
      .take(InputSanitizer.MAX_ITEMS_PER_ORDER)
      .mapNotNull { item ->
        val cleanName = InputSanitizer.sanitizeItemName(item.name)
        val cleanNotes = InputSanitizer.sanitizeNotes(item.notes)
        if (cleanName.isBlank() || cleanNotes.isBlank()) null
        else item.copy(
          name = cleanName,
          quantity = item.quantity.coerceIn(1, InputSanitizer.MAX_ITEM_QUANTITY),
          notes = cleanNotes,
          productUrl = com.example.util.UrlSecurityHelper.sanitizeUrl(item.productUrl) ?: "",
          imageUrl = com.example.util.UrlSecurityHelper.sanitizeUrl(item.imageUrl) ?: ""
        )
      }
    if (sanitizedItems.isEmpty()) {
      _uiState.update { it.copy(errorMessage = "Please add at least one valid item with note.") }
      return
    }
    if (Firebase.auth.currentUser == null) return

    if (!NetworkMonitor.isOnlineNow(appContext)) {
      pendingOfflineOrder = sanitizedItems to onSuccess
      _uiState.update {
        it.copy(
          isOnline = false,
          isLoading = true,
          isSendingOrder = true,
          isWaitingOfflineToSendOrder = true,
          errorMessage = null
        )
      }
      return
    }

    performSendInvoiceOrder(sanitizedItems, onSuccess)
  }

  private fun performSendInvoiceOrder(
    sanitizedItems: List<OrderItemDetail>,
    onSuccess: () -> Unit
  ) {
    var partnerUid = _uiState.value.partnerUid
    val partnerNumericId = _uiState.value.partnerNumericId
    var partnerName = _uiState.value.partnerDisplayName

    val currentUser = Firebase.auth.currentUser ?: return
    val displayName = InputSanitizer.sanitizePersonName(
      currentUser.displayName ?: currentUser.email?.substringBefore("@") ?: "User"
    ).ifBlank { "User" }

    viewModelScope.launch {
      try {
        _uiState.update {
          it.copy(
            isLoading = true,
            isSendingOrder = true,
            isWaitingOfflineToSendOrder = false
          )
        }

        if (!NetworkMonitor.isOnlineNow(appContext)) {
          pendingOfflineOrder = sanitizedItems to onSuccess
          _uiState.update {
            it.copy(
              isOnline = false,
              isLoading = true,
              isSendingOrder = true,
              isWaitingOfflineToSendOrder = true,
              errorMessage = null
            )
          }
          return@launch
        }

        val result = withTimeoutOrNull(15_000L) {
          // If partnerUid is blank or not a Firebase UID yet while partnerNumericId is present, resolve it
          if ((partnerUid.isBlank() || partnerUid.length < 15) && partnerNumericId.isNotBlank()) {
            try {
              val userResult = repository.getUserByNumericId(partnerNumericId)
              val profile = userResult.getOrNull()
              if (profile != null && profile.uid.isNotBlank()) {
                partnerUid = profile.uid
                if (partnerName.isBlank()) partnerName = profile.displayName.ifBlank { profile.email }
                saveAndApplyPartner(partnerNumericId, profile.uid, partnerName)
              }
            } catch (e: Exception) {
              Log.w(TAG, "sendInvoiceOrder: could not resolve partner UID: ${e.message}")
            }
          }

          val effectivePartnerId = if (partnerUid.isNotBlank()) partnerUid else partnerNumericId
          val effectivePartnerName = InputSanitizer.sanitizePersonName(partnerName).ifBlank { "Partner" }
          val orderNumber = "#INV-${Random.nextInt(1000, 9999)}"

          kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val orderResult = repository.createInvoiceOrder(
              items = sanitizedItems,
              partnerUid = effectivePartnerId,
              partnerName = effectivePartnerName,
              userName = displayName,
              orderNumber = orderNumber
            )
            if (orderResult.isSuccess) {
              val orderId = orderResult.getOrNull() ?: ""
              Log.d(TAG, "sendInvoiceOrder: order created: $orderId")
              if (effectivePartnerId.isNotBlank()) {
                repository.sendOrderNotification(
                  targetUid = effectivePartnerId,
                  senderName = displayName,
                  orderNumber = orderNumber,
                  itemCount = sanitizedItems.size,
                  orderId = orderId
                )
              }
            }
            orderResult
          }
        }

        if (result == null || !NetworkMonitor.isOnlineNow(appContext)) {
          pendingOfflineOrder = sanitizedItems to onSuccess
          _uiState.update {
            it.copy(
              isOnline = NetworkMonitor.isOnlineNow(appContext),
              isLoading = true,
              isSendingOrder = true,
              isWaitingOfflineToSendOrder = true,
              errorMessage = null
            )
          }
          return@launch
        }

        if (result.isSuccess) {
          pendingOfflineOrder = null
          _uiState.update {
            it.copy(
              isLoading = false,
              isSendingOrder = false,
              isWaitingOfflineToSendOrder = false,
              bannerNotification = "Order Sent!"
            )
          }
          onSuccess()
        } else {
          Log.e(TAG, "sendInvoiceOrder: failed", result.exceptionOrNull())
          pendingOfflineOrder = null
          _uiState.update {
            it.copy(
              isLoading = false,
              isSendingOrder = false,
              isWaitingOfflineToSendOrder = false,
              errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to send order"
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "sendInvoiceOrder: exception", e)
        _uiState.update {
          it.copy(
            isLoading = false,
            isSendingOrder = false,
            isWaitingOfflineToSendOrder = false,
            errorMessage = "Order error: ${e.message}"
          )
        }
      }
    }
  }

  private fun setOrderBusy(orderId: String, busy: Boolean) {
    _uiState.update { state ->
      val next = if (busy) state.busyOrderIds + orderId else state.busyOrderIds - orderId
      state.copy(busyOrderIds = next)
    }
  }

  private fun requireOnlineForAction(): Boolean {
    if (!NetworkMonitor.isOnlineNow(appContext)) {
      _uiState.update {
        it.copy(
          isOnline = false,
          errorMessage = "No internet connection. Please connect to the internet and try again."
        )
      }
      return false
    }
    return true
  }

  fun acceptOrder(orderId: String, shoppingDurationMinutes: Int = 30) {
    if (!requireOnlineForAction()) return
    val safeDuration = shoppingDurationMinutes.coerceIn(InputSanitizer.MIN_DURATION_MINS, InputSanitizer.MAX_DURATION_MINS)
    Log.d(TAG, "acceptOrder: orderId=$orderId, duration=${safeDuration}m")
    com.example.service.OrderLifecycleManager.cancelPendingAlarmsForOrder(appContext, orderId)
    viewModelScope.launch {
      setOrderBusy(orderId, true)
      try {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
          val acceptResult = repository.acceptOrder(orderId, safeDuration)
          if (acceptResult.isSuccess) {
            val order = _uiState.value.partnerOrders.firstOrNull { it.id == orderId }
            if (order != null) {
              val currentUser = Firebase.auth.currentUser
              val myName = currentUser?.displayName?.takeIf { it.isNotBlank() }
                ?: currentUser?.email?.substringBefore("@")
                ?: "Partner"
              repository.sendOrderAcceptedNotification(
                targetUid = order.userId,
                partnerName = myName,
                orderNumber = order.orderNumber,
                shoppingDurationMinutes = safeDuration,
                orderId = orderId
              )
            }
          }
          acceptResult
        }
        if (result.isFailure) {
          Log.e(TAG, "acceptOrder: failed", result.exceptionOrNull())
          _uiState.update {
            it.copy(errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to accept order")
          }
        } else {
          _uiState.update {
            it.copy(bannerNotification = "Order accepted! Shopping timer set for $safeDuration mins.")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "acceptOrder: exception", e)
      } finally {
        setOrderBusy(orderId, false)
      }
    }
  }

  fun rejectOrder(orderId: String, reason: String) {
    if (!requireOnlineForAction()) return
    val cleanReason = InputSanitizer.sanitizeReason(reason).ifBlank { "Unable to fulfill right now" }
    Log.d(TAG, "rejectOrder: orderId=$orderId, reason=$cleanReason")
    com.example.service.OrderLifecycleManager.cancelAllAlarmsForOrder(appContext, orderId)
    viewModelScope.launch {
      setOrderBusy(orderId, true)
      try {
        val order = _uiState.value.partnerOrders.firstOrNull { it.id == orderId }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
          val rejectRes = repository.rejectOrder(orderId, cleanReason)
          if (rejectRes.isSuccess && order != null) {
            val currentUser = Firebase.auth.currentUser
            val myName = currentUser?.displayName?.takeIf { it.isNotBlank() }
              ?: currentUser?.email?.substringBefore("@")
              ?: "Partner"
            repository.sendOrderRejectedNotification(
              targetUid = order.userId,
              partnerName = myName,
              orderNumber = order.orderNumber,
              reason = cleanReason,
              orderId = orderId
            )
          }
          rejectRes
        }
        if (result.isFailure) {
          _uiState.update {
            it.copy(errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to reject order")
          }
        } else {
          _uiState.update { it.copy(bannerNotification = "Order rejected.") }
        }
      } catch (e: Exception) {
        Log.e(TAG, "rejectOrder: exception", e)
      } finally {
        setOrderBusy(orderId, false)
      }
    }
  }

  fun markOrderIncomplete(orderId: String, reason: String) {
    if (!requireOnlineForAction()) return
    val cleanReason = InputSanitizer.sanitizeReason(reason).ifBlank { "Could not complete shopping" }
    Log.d(TAG, "markOrderIncomplete: orderId=$orderId, reason=$cleanReason")
    com.example.service.OrderLifecycleManager.cancelAllAlarmsForOrder(appContext, orderId)
    viewModelScope.launch {
      setOrderBusy(orderId, true)
      try {
        val order = _uiState.value.partnerOrders.firstOrNull { it.id == orderId }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
          val incRes = repository.markOrderIncomplete(orderId, cleanReason)
          if (incRes.isSuccess && order != null) {
            val currentUser = Firebase.auth.currentUser
            val myName = currentUser?.displayName?.takeIf { it.isNotBlank() }
              ?: currentUser?.email?.substringBefore("@")
              ?: "Partner"
            repository.sendOrderIncompleteNotification(
              targetUid = order.userId,
              partnerName = myName,
              orderNumber = order.orderNumber,
              reason = cleanReason,
              orderId = orderId
            )
          }
          incRes
        }
        if (result.isFailure) {
          _uiState.update {
            it.copy(errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to update order")
          }
        } else {
          _uiState.update { it.copy(bannerNotification = "Order marked as Incomplete.") }
        }
      } catch (e: Exception) {
        Log.e(TAG, "markOrderIncomplete: exception", e)
      } finally {
        setOrderBusy(orderId, false)
      }
    }
  }

  fun markItemBought(
    orderId: String,
    currentItems: List<OrderItemDetail>,
    itemIndex: Int,
    pricePaid: Double,
    marketName: String = ""
  ) {
    if (!requireOnlineForAction()) return
    val safePrice = if (pricePaid.isFinite()) pricePaid.coerceIn(0.0, InputSanitizer.MAX_ITEM_PRICE) else 0.0
    val safeMarket = InputSanitizer.sanitizeMarketName(marketName)
    Log.d(TAG, "markItemBought: orderId=$orderId, itemIndex=$itemIndex")
    viewModelScope.launch {
      setOrderBusy(orderId, true)
      try {
        val order = _uiState.value.partnerOrders.firstOrNull { it.id == orderId }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
          val boughtResult = repository.markItemBought(
            orderId = orderId,
            currentItems = currentItems,
            itemIndex = itemIndex,
            pricePaid = safePrice,
            marketName = safeMarket,
            orderNumber = order?.orderNumber.orEmpty(),
            requesterId = order?.userId.orEmpty()
          )
          if (boughtResult.isSuccess) {
            val markResult = boughtResult.getOrNull()
            if (markResult?.orderCompleted == true && order != null) {
              com.example.service.OrderLifecycleManager.cancelAllAlarmsForOrder(appContext, orderId)
              val currentUser = Firebase.auth.currentUser
              val myName = currentUser?.displayName?.takeIf { it.isNotBlank() }
                ?: currentUser?.email?.substringBefore("@")
                ?: "Partner"
              val unavailCount = currentItems.count { it.isUnavailable }
              repository.sendOrderCompletedNotification(
                targetUid = order.userId,
                partnerName = myName,
                orderNumber = order.orderNumber,
                amount = markResult.totalCost,
                unavailableCount = unavailCount,
                orderId = orderId
              )
            }
          }
          boughtResult
        }
        if (result.isFailure) {
          Log.e(TAG, "markItemBought: failed", result.exceptionOrNull())
          _uiState.update {
            it.copy(errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to record item price")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "markItemBought: exception", e)
      } finally {
        setOrderBusy(orderId, false)
      }
    }
  }

  fun markItemUnavailable(
    orderId: String,
    currentItems: List<OrderItemDetail>,
    itemIndex: Int,
    reason: String,
    marketNameIfCompleting: String = ""
  ) {
    if (!requireOnlineForAction()) return
    val cleanReason = InputSanitizer.sanitizeReason(reason, InputSanitizer.MAX_UNAVAILABLE_REASON_LEN)
      .ifBlank { "Not available in market" }
    val cleanMarket = InputSanitizer.sanitizeMarketName(marketNameIfCompleting)
    Log.d(TAG, "markItemUnavailable: orderId=$orderId, itemIndex=$itemIndex")
    viewModelScope.launch {
      setOrderBusy(orderId, true)
      try {
        val order = _uiState.value.partnerOrders.firstOrNull { it.id == orderId }
        val itemName = currentItems.getOrNull(itemIndex)?.name.orEmpty().ifBlank { "Item" }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
          val unavailRes = repository.markItemUnavailable(
            orderId = orderId,
            currentItems = currentItems,
            itemIndex = itemIndex,
            reason = cleanReason,
            marketNameIfCompleting = cleanMarket,
            orderNumber = order?.orderNumber.orEmpty(),
            requesterId = order?.userId.orEmpty()
          )
          if (unavailRes.isSuccess && order != null) {
            val markRes = unavailRes.getOrNull()
            val currentUser = Firebase.auth.currentUser
            val myName = currentUser?.displayName?.takeIf { it.isNotBlank() }
              ?: currentUser?.email?.substringBefore("@")
              ?: "Partner"
            when {
              markRes?.orderCompleted == true -> {
                com.example.service.OrderLifecycleManager.cancelAllAlarmsForOrder(appContext, orderId)
                val unavailCount = currentItems.count { it.isUnavailable } + 1
                repository.sendOrderCompletedNotification(
                  targetUid = order.userId,
                  partnerName = myName,
                  orderNumber = order.orderNumber,
                  amount = markRes.totalCost,
                  unavailableCount = unavailCount,
                  orderId = orderId
                )
              }
              markRes?.orderMarkedIncomplete == true -> {
                com.example.service.OrderLifecycleManager.cancelAllAlarmsForOrder(appContext, orderId)
                repository.sendOrderIncompleteNotification(
                  targetUid = order.userId,
                  partnerName = myName,
                  orderNumber = order.orderNumber,
                  reason = "All items unavailable in market",
                  orderId = orderId
                )
              }
              else -> {
                repository.sendItemUnavailableNotification(
                  targetUid = order.userId,
                  partnerName = myName,
                  orderNumber = order.orderNumber,
                  itemName = itemName,
                  reason = reason,
                  orderId = orderId
                )
              }
            }
          }
          unavailRes
        }
        if (result.isFailure) {
          _uiState.update {
            it.copy(errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to mark item unavailable")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "markItemUnavailable: exception", e)
      } finally {
        setOrderBusy(orderId, false)
      }
    }
  }

  fun deleteOrder(orderId: String) {
    if (!requireOnlineForAction()) return
    Log.d(TAG, "deleteOrder: orderId=$orderId")
    com.example.service.OrderLifecycleManager.cancelAllAlarmsForOrder(appContext, orderId)
    viewModelScope.launch {
      setOrderBusy(orderId, true)
      try {
        val result = repository.deleteOrder(orderId)
        if (result.isFailure) {
          Log.e(TAG, "deleteOrder: failed", result.exceptionOrNull())
          _uiState.update {
            it.copy(errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to delete order")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "deleteOrder: exception", e)
      } finally {
        setOrderBusy(orderId, false)
      }
    }
  }

  fun clearError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  fun clearBannerNotification() {
    _uiState.update { it.copy(bannerNotification = null) }
  }

  fun collectDailyCoin() {
    if (!_uiState.value.coinFeatureEnabled) {
      _uiState.update { it.copy(showCoinAnimation = false, errorMessage = "Coin system is currently disabled by Admin.") }
      return
    }
    val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    if (_uiState.value.lastCoinDate == todayDate) {
      Log.d(TAG, "collectDailyCoin: Already collected today, skipping.")
      _uiState.update { it.copy(showCoinAnimation = false) }
      return
    }

    val currentCoins = (_uiState.value.coins + 1).coerceIn(0, InputSanitizer.MAX_COINS)
    
    // Commit synchronously so it's guaranteed saved on disk
    getPrefs().edit()
      .putInt("user_coins", currentCoins)
      .putString("last_coin_date", todayDate)
      .commit()

    _uiState.update {
      it.copy(
        coins = currentCoins,
        lastCoinDate = todayDate,
        showCoinAnimation = false,
        bannerNotification = "+1 Daily Coin Collected! Total Coins: $currentCoins"
      )
    }

    val numericId = _uiState.value.myNumericId
    if (numericId.isNotBlank()) {
      viewModelScope.launch {
        repository.updateUserCoins(numericId, currentCoins, todayDate)
      }
    }
  }

  fun dismissCoinDialog() {
    _uiState.update { it.copy(showCoinAnimation = false) }
  }

  private var incomingNotificationsJob: kotlinx.coroutines.Job? = null
  private var myProfileJob: kotlinx.coroutines.Job? = null
  private var partnerProfileJob: kotlinx.coroutines.Job? = null
  private var myGiftClaimJob: kotlinx.coroutines.Job? = null
  private var broadcastNotificationsJob: kotlinx.coroutines.Job? = null
  private var adminUsersJob: kotlinx.coroutines.Job? = null
  private var adminOrdersJob: kotlinx.coroutines.Job? = null
  private var adminGiftClaimsJob: kotlinx.coroutines.Job? = null
  private var adminMarketsJob: kotlinx.coroutines.Job? = null
  private var adminNotificationsJob: kotlinx.coroutines.Job? = null
  private var latestBroadcasts: List<com.example.data.model.NotificationData> = emptyList()

  private fun rebuildInboxNotifications() {
    val uid = Firebase.auth.currentUser?.uid ?: return
    val localItems = NotificationInboxStore.getAll(appContext, uid)
    val dismissed = NotificationInboxStore.getDismissedBroadcastIds(appContext, uid)
    val readBroadcasts = NotificationInboxStore.getReadBroadcastIds(appContext, uid)

    val broadcastItems = latestBroadcasts
      .map { bc ->
        val bId = "broadcast_${bc.id}"
        InboxNotificationItem(
          id = bId,
          title = bc.title.ifBlank { "Paircart Notice" },
          message = bc.message,
          type = bc.type.ifBlank { "admin_broadcast" },
          orderId = "",
          createdAtMs = bc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
          isRead = bId in readBroadcasts,
          isBroadcast = true
        )
      }
      .filter { it.id !in dismissed && it.message.isNotBlank() }

    val merged = (broadcastItems + localItems)
      .distinctBy { it.id }
      .sortedByDescending { it.createdAtMs }

    _uiState.update { it.copy(inboxNotifications = merged) }
  }

  private fun observeBroadcastNotifications() {
    broadcastNotificationsJob?.cancel()
    val uid = Firebase.auth.currentUser?.uid ?: return
    broadcastNotificationsJob = viewModelScope.launch {
      repository.observeBroadcastNotifications()
        .catch { e -> Log.w(TAG, "observeBroadcastNotifications warning: ${e.message}") }
        .collect { broadcasts ->
          latestBroadcasts = broadcasts
          val serverItems = broadcasts
            .filter { it.message.isNotBlank() }
            .map { bc ->
              InboxNotificationItem(
                id = "broadcast_${bc.id}",
                title = bc.title.ifBlank { "Paircart Notice" },
                message = bc.message,
                type = bc.type.ifBlank { "admin_broadcast" },
                orderId = "",
                createdAtMs = bc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                isRead = false,
                isBroadcast = true
              )
            }
            .take(10)
          _uiState.update { it.copy(serverAnnouncements = serverItems) }

          val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
          broadcasts.forEach { bc ->
            if (bc.message.isNotBlank() && isWithinFreshWindow(bc.createdAt, windowMs = sevenDaysMs)) {
              val bKey = "broadcast_${bc.id}"
              if (tryMarkNotificationHandled(bKey)) {
                val delivered = deliverOrderNotification(
                  title = bc.title.ifBlank { "Paircart Notice" },
                  message = bc.message,
                  bannerMessage = "${bc.title.ifBlank { "Notice" }}: ${bc.message}",
                  notificationId = bKey.hashCode()
                )
                if (!delivered) {
                  unmarkNotificationHandled(bKey)
                }
              }
            }
          }
          rebuildInboxNotifications()
        }
    }
  }

  fun markNotificationRead(item: InboxNotificationItem) {
    val uid = Firebase.auth.currentUser?.uid ?: return
    NotificationInboxStore.markRead(appContext, uid, item.id, item.isBroadcast)
    rebuildInboxNotifications()
  }

  fun markAllNotificationsRead() {
    val uid = Firebase.auth.currentUser?.uid ?: return
    val broadcastIds = _uiState.value.inboxNotifications.filter { it.isBroadcast }.map { it.id }
    NotificationInboxStore.markAllRead(appContext, uid, broadcastIds)
    rebuildInboxNotifications()
  }

  fun deleteInboxNotification(item: InboxNotificationItem) {
    val uid = Firebase.auth.currentUser?.uid ?: return
    NotificationInboxStore.dismiss(appContext, uid, item.id, item.isBroadcast)
    rebuildInboxNotifications()
  }

  fun clearAllInboxNotifications() {
    val uid = Firebase.auth.currentUser?.uid ?: return
    val broadcastIds = latestBroadcasts.map { "broadcast_${it.id}" }
    NotificationInboxStore.clearAll(appContext, uid, broadcastIds)
    rebuildInboxNotifications()
  }

  private var appConfigJob: kotlinx.coroutines.Job? = null

  private fun observeAppConfig() {
    appConfigJob?.cancel()
    appConfigJob = viewModelScope.launch {
      repository.observeAppConfig()
        .catch { e -> Log.w(TAG, "observeAppConfig warning: ${e.message}") }
        .collect { config ->
          _uiState.update {
            it.copy(
              coinFeatureEnabled = config.coinFeatureEnabled,
              appEnabled = config.appEnabled
            )
          }
        }
    }
  }

  private fun observeMyProfile(numericId: String) {
    myProfileJob?.cancel()
    myProfileJob = viewModelScope.launch {
      repository.observeMyProfile(numericId)
        .catch { e -> Log.w(TAG, "observeMyProfile warning: ${e.message}") }
        .collect { profile ->
          if (profile != null) {
            getPrefs().edit()
              .putInt("user_coins", profile.coins)
              .putString("last_coin_date", profile.lastCoinDate)
              .putString("profile_photo_data", profile.profilePhotoData)
              .putString("profile_birthday", profile.birthday)
              .putString("profile_profession", profile.profession)
              .putString("profile_address", profile.address)
              .putString("last_profile_edit_month", profile.lastProfileEditMonth)
              .putLong("last_profile_edit_at_ms", profile.lastProfileEditAtMs)
              .apply()
            _uiState.update { state ->
              state.copy(
                hasLoadedProfile = true,
                coins = profile.coins,
                lastCoinDate = profile.lastCoinDate.ifBlank { state.lastCoinDate },
                profilePhotoData = profile.profilePhotoData,
                profileBirthday = profile.birthday,
                profileProfession = profile.profession,
                profileAddress = profile.address,
                lastProfileEditMonth = profile.lastProfileEditMonth,
                lastProfileEditAtMs = profile.lastProfileEditAtMs
              )
            }
          }
        }
    }
  }

  private fun observePartnerProfile(partnerNumericId: String) {
    partnerProfileJob?.cancel()
    val cleanId = partnerNumericId.trim()
    if (cleanId.isBlank()) {
      _uiState.update {
        it.copy(
          partnerPhotoData = "",
          partnerProfession = "",
          partnerBirthday = "",
          partnerAddress = "",
          hasLoadedPartnerProfile = true
        )
      }
      return
    }
    _uiState.update { it.copy(hasLoadedPartnerProfile = false) }
    partnerProfileJob = viewModelScope.launch {
      repository.observeMyProfile(cleanId)
        .catch { e ->
          Log.w(TAG, "observePartnerProfile warning: ${e.message}")
          _uiState.update { it.copy(hasLoadedPartnerProfile = true) }
        }
        .collect { partnerProfile ->
          _uiState.update { state ->
            state.copy(
              partnerDisplayName = partnerProfile?.displayName?.takeIf { it.isNotBlank() } ?: state.partnerDisplayName,
              partnerPhotoData = partnerProfile?.profilePhotoData ?: "",
              partnerProfession = partnerProfile?.profession ?: "",
              partnerBirthday = partnerProfile?.birthday ?: "",
              partnerAddress = partnerProfile?.address ?: "",
              hasLoadedPartnerProfile = true
            )
          }
        }
    }
  }

  fun updateExtendedProfile(
    profilePhotoData: String,
    birthday: String,
    profession: String,
    address: String,
    onComplete: (Boolean, String?) -> Unit = { _, _ -> }
  ) {
    val state = _uiState.value
    val currentMonth = InputSanitizer.currentYearMonth()
    val cleanPhoto = profilePhotoData.trim()
    val cleanBirthday = InputSanitizer.sanitizeBirthday(birthday)
    val cleanProfession = InputSanitizer.sanitizeProfession(profession)
    val cleanAddress = InputSanitizer.sanitizeProfileAddress(address)

    val isLegacyPhotoUpgrade = state.profilePhotoData.isNotBlank() &&
      !state.profilePhotoData.startsWith("data:image/webp;base64,") &&
      cleanPhoto.startsWith("data:image/webp;base64,") &&
      cleanBirthday == state.profileBirthday &&
      cleanProfession == state.profileProfession &&
      cleanAddress == state.profileAddress

    if (state.lastProfileEditMonth == currentMonth && !isLegacyPhotoUpgrade) {
      val nextDate = InputSanitizer.nextEligibleEditDateLabel(currentMonth)
      val msg = "You can only edit your profile once per month. Next edit available from $nextDate."
      _uiState.update { it.copy(errorMessage = msg) }
      onComplete(false, msg)
      return
    }

    if (!InputSanitizer.isValidProfilePhotoData(cleanPhoto)) {
      val msg = "Profile picture size is too large or invalid."
      _uiState.update { it.copy(errorMessage = msg) }
      onComplete(false, msg)
      return
    }
    if (cleanProfession.isNotEmpty() && !InputSanitizer.isValidProfession(cleanProfession)) {
      val msg = "Please enter a valid profession (2-60 characters)."
      _uiState.update { it.copy(errorMessage = msg) }
      onComplete(false, msg)
      return
    }
    if (cleanAddress.isNotEmpty() && !InputSanitizer.isValidProfileAddress(cleanAddress)) {
      val msg = "Please enter a valid address (3-200 characters)."
      _uiState.update { it.copy(errorMessage = msg) }
      onComplete(false, msg)
      return
    }
    if (cleanPhoto.isBlank() && cleanBirthday.isBlank() && cleanProfession.isBlank() && cleanAddress.isBlank()) {
      val msg = "Please add at least one profile detail (photo, birthday, profession, or address)."
      _uiState.update { it.copy(errorMessage = msg) }
      onComplete(false, msg)
      return
    }

    if (!NetworkMonitor.isOnlineNow(appContext)) {
      val msg = "No internet connection. Please connect to save your profile."
      _uiState.update { it.copy(isOnline = false, errorMessage = msg) }
      onComplete(false, msg)
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(isSavingProfile = true) }
      val result = repository.updateExtendedProfile(
        myNumericId = state.myNumericId,
        profilePhotoData = cleanPhoto,
        birthday = cleanBirthday,
        profession = cleanProfession,
        address = cleanAddress
      )
      if (result.isSuccess) {
        val (savedMonth, savedAtMs) = result.getOrNull() ?: (currentMonth to System.currentTimeMillis())
        getPrefs().edit()
          .putString("profile_photo_data", cleanPhoto)
          .putString("profile_birthday", cleanBirthday)
          .putString("profile_profession", cleanProfession)
          .putString("profile_address", cleanAddress)
          .putString("last_profile_edit_month", savedMonth)
          .putLong("last_profile_edit_at_ms", savedAtMs)
          .commit()

        _uiState.update {
          it.copy(
            isSavingProfile = false,
            profilePhotoData = cleanPhoto,
            profileBirthday = cleanBirthday,
            profileProfession = cleanProfession,
            profileAddress = cleanAddress,
            lastProfileEditMonth = savedMonth,
            lastProfileEditAtMs = savedAtMs,
            bannerNotification = "Profile saved successfully!"
          )
        }
        onComplete(true, null)
      } else {
        val errMsg = result.exceptionOrNull()?.localizedMessage ?: "Failed to update profile"
        _uiState.update { it.copy(isSavingProfile = false, errorMessage = errMsg) }
        onComplete(false, errMsg)
      }
    }
  }

  private fun observeMyGiftClaim() {
    myGiftClaimJob?.cancel()
    myGiftClaimJob = viewModelScope.launch {
      repository.observeMyGiftClaim()
        .catch { e -> Log.w(TAG, "observeMyGiftClaim warning: ${e.message}") }
        .collect { claim ->
          _uiState.update { it.copy(myGiftClaim = claim) }
        }
    }
  }

  fun submitGiftClaim(
    recipientName: String,
    phone: String,
    address: String,
    note: String
  ) {
    val state = _uiState.value
    if (!state.coinFeatureEnabled) {
      _uiState.update { it.copy(errorMessage = "Coin & Gift system is currently disabled by Admin.") }
      return
    }
    if (state.coins < AdminConfig.GIFT_COIN_THRESHOLD) {
      _uiState.update { it.copy(errorMessage = "You need ${AdminConfig.GIFT_COIN_THRESHOLD} coins to claim this gift.") }
      return
    }
    if (!requireOnlineForAction()) return
    viewModelScope.launch {
      _uiState.update { it.copy(isSubmittingGiftClaim = true) }
      val result = repository.submitGiftClaim(
        myNumericId = state.myNumericId,
        coins = state.coins,
        recipientName = recipientName,
        phone = phone,
        address = address,
        note = note
      )
      if (result.isSuccess) {
        _uiState.update {
          it.copy(
            isSubmittingGiftClaim = false,
            bannerNotification = "Delivery address submitted!"
          )
        }
      } else {
        _uiState.update {
          it.copy(
            isSubmittingGiftClaim = false,
            errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to submit address"
          )
        }
      }
    }
  }

  private fun observeAdminCollections() {
    adminUsersJob?.cancel()
    adminOrdersJob?.cancel()
    adminGiftClaimsJob?.cancel()
    adminMarketsJob?.cancel()
    adminNotificationsJob?.cancel()

    adminUsersJob = viewModelScope.launch {
      repository.observeAllUsers()
        .catch { e -> Log.w(TAG, "observeAllUsers error: ${e.message}") }
        .collect { users ->
          _uiState.update { it.copy(adminUsers = users, hasLoadedAdminData = true) }
        }
    }
    adminOrdersJob = viewModelScope.launch {
      repository.observeAllOrders()
        .catch { e -> Log.w(TAG, "observeAllOrders error: ${e.message}") }
        .collect { orders ->
          _uiState.update { it.copy(adminOrders = orders, hasLoadedAdminData = true) }
        }
    }
    adminGiftClaimsJob = viewModelScope.launch {
      repository.observeAllGiftClaims()
        .catch { e -> Log.w(TAG, "observeAllGiftClaims error: ${e.message}") }
        .collect { claims ->
          _uiState.update { it.copy(adminGiftClaims = claims, hasLoadedAdminData = true) }
        }
    }
    adminMarketsJob = viewModelScope.launch {
      repository.observeAllMarketPurchases()
        .catch { e -> Log.w(TAG, "observeAllMarketPurchases error: ${e.message}") }
        .collect { markets ->
          _uiState.update { it.copy(adminMarketPurchases = markets, hasLoadedAdminData = true) }
        }
    }
    adminNotificationsJob = viewModelScope.launch {
      repository.observeAllNotifications()
        .catch { e -> Log.w(TAG, "observeAllNotifications error: ${e.message}") }
        .collect { notifs ->
          _uiState.update {
            it.copy(
              adminNotifications = AdminConfig.filterAdminNotices(notifs),
              hasLoadedAdminData = true
            )
          }
        }
    }
  }

  fun adminUpdateUser(numericId: String, displayName: String, coins: Int, clearPartner: Boolean) {
    viewModelScope.launch {
      val res = repository.adminUpdateUser(numericId, displayName, coins, clearPartner)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = "User #$numericId updated") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to update user") }
      }
    }
  }

  fun adminDeleteUser(numericId: String) {
    viewModelScope.launch {
      val res = repository.adminDeleteUser(numericId)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = "User #$numericId deleted") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to delete user") }
      }
    }
  }

  fun adminUpdateOrder(orderId: String, status: String, totalCost: Double, marketName: String) {
    viewModelScope.launch {
      val res = repository.adminUpdateOrder(orderId, status, totalCost, marketName)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = "Order updated ($status)") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to update order") }
      }
    }
  }

  fun adminUpdateGiftClaim(
    claimId: String,
    status: String,
    recipientName: String,
    phone: String,
    address: String,
    note: String
  ) {
    viewModelScope.launch {
      val res = repository.adminUpdateGiftClaim(claimId, status, recipientName, phone, address, note)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = "Gift claim updated ($status)") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to update gift claim") }
      }
    }
  }

  fun adminDeleteGiftClaim(claimId: String) {
    viewModelScope.launch {
      val res = repository.adminDeleteGiftClaim(claimId)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = "Gift claim removed") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to delete gift claim") }
      }
    }
  }

  fun adminUpdateMarketPurchase(purchaseId: String, itemName: String, marketName: String, boughtPrice: Double) {
    viewModelScope.launch {
      val res = repository.adminUpdateMarketPurchase(purchaseId, itemName, marketName, boughtPrice)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = "Market record updated") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to update market record") }
      }
    }
  }

  fun adminDeleteMarketPurchase(purchaseId: String) {
    viewModelScope.launch {
      val res = repository.adminDeleteMarketPurchase(purchaseId)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = "Market record deleted") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to delete market record") }
      }
    }
  }

  fun adminSendNotification(targetUid: String, title: String, message: String) {
    viewModelScope.launch {
      val res = repository.adminSendNotification(targetUid, title, message)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = "Notification sent!") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to send notification") }
      }
    }
  }

  fun adminBroadcastNotification(title: String, message: String) {
    if (message.isBlank()) return
    viewModelScope.launch {
      val res = repository.adminBroadcastNotification(title, message)
      _uiState.update {
        if (res.isSuccess) it.copy(bannerNotification = "Notification sent to all users")
        else it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Broadcast failed")
      }
    }
  }

  fun adminUpdateNotification(notificationId: String, title: String, message: String) {
    if (message.isBlank()) return
    viewModelScope.launch {
      val res = repository.adminUpdateNotification(notificationId, title, message)
      _uiState.update {
        if (res.isSuccess) it.copy(bannerNotification = "Announcement updated successfully")
        else it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to update announcement")
      }
    }
  }

  fun adminToggleCoinFeature(enabled: Boolean) {
    viewModelScope.launch {
      val res = repository.adminUpdateCoinFeatureEnabled(enabled)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = if (enabled) "Coin system enabled" else "Coin system disabled") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to update coin feature") }
      }
    }
  }

  fun adminToggleAppEnabled(enabled: Boolean) {
    viewModelScope.launch {
      val res = repository.adminUpdateAppEnabled(enabled)
      if (res.isSuccess) {
        _uiState.update {
          it.copy(
            bannerNotification = if (enabled) {
              "App turned ON — users can now sign in"
            } else {
              "App turned OFF — non-admin user logins disabled"
            }
          )
        }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to update app status") }
      }
    }
  }

  fun adminDeleteNotification(notificationId: String) {
    viewModelScope.launch {
      val res = repository.adminDeleteNotification(notificationId)
      if (res.isSuccess) {
        _uiState.update { it.copy(bannerNotification = "Notification removed") }
      } else {
        _uiState.update { it.copy(errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to remove notification") }
      }
    }
  }

  fun onSignOut() {
    Log.d(TAG, "onSignOut: canceling jobs and resetting state")
    entryLoadingJob?.cancel()
    incomingNotificationsJob?.cancel()
    myOrdersJob?.cancel()
    partnerOrdersJob?.cancel()
    myProfileJob?.cancel()
    partnerProfileJob?.cancel()
    myGiftClaimJob?.cancel()
    appConfigJob?.cancel()
    broadcastNotificationsJob?.cancel()
    adminUsersJob?.cancel()
    adminOrdersJob?.cancel()
    adminGiftClaimsJob?.cancel()
    adminMarketsJob?.cancel()
    adminNotificationsJob?.cancel()
    pendingOfflineOrder = null
    hasStartedInitialProfileSync = false
    latestBroadcasts = emptyList()
    sessionExpiredRequests.clear()
    _uiState.value.myOrders.forEach { com.example.service.OrderLifecycleManager.cancelAllAlarmsForOrder(appContext, it.id) }
    _uiState.value.partnerOrders.forEach { com.example.service.OrderLifecycleManager.cancelAllAlarmsForOrder(appContext, it.id) }
    _uiState.update {
      it.copy(
        myOrders = emptyList(),
        partnerOrders = emptyList(),
        partnerPhotoData = "",
        partnerProfession = "",
        partnerBirthday = "",
        partnerAddress = "",
        hasLoadedPartnerProfile = false,
        myGiftClaim = null,
        isAdmin = false,
        adminUsers = emptyList(),
        adminOrders = emptyList(),
        adminGiftClaims = emptyList(),
        adminMarketPurchases = emptyList(),
        adminNotifications = emptyList(),
        inboxNotifications = emptyList(),
        serverAnnouncements = emptyList(),
        errorMessage = null,
        bannerNotification = null,
        showCoinAnimation = false,
        isLoading = false,
        isAppEntryLoading = false,
        hasLoadedMyOrders = false,
        hasLoadedPartnerOrders = false,
        hasLoadedProfile = false,
        hasLoadedAdminData = false,
        isSendingOrder = false,
        isWaitingOfflineToSendOrder = false,
        isConnectingPartner = false,
        busyOrderIds = emptySet()
      )
    }
  }

  fun onSessionResumed() {
    Log.d(TAG, "onSessionResumed: refreshing session & inbox")
    clearError()
    if (_uiState.value.myNumericId.isBlank() || myOrdersJob?.isActive != true) {
      initCurrentUser()
      return
    }
    val prefs = getPrefs()
    val savedPartnerUid = prefs.getString("partner_uid", "") ?: ""
    val savedPartnerId = prefs.getString("partner_numeric_id", "") ?: ""
    val savedPartnerName = prefs.getString("partner_name", "") ?: ""
    if (savedPartnerUid.isNotBlank() && _uiState.value.partnerUid != savedPartnerUid) {
      saveAndApplyPartner(
        partnerId = savedPartnerId,
        partnerUid = savedPartnerUid,
        partnerName = savedPartnerName,
        showBanner = true
      )
    }
    if (incomingNotificationsJob?.isActive != true) {
      observeIncomingNotifications()
    }
    if (broadcastNotificationsJob?.isActive != true) {
      observeBroadcastNotifications()
    }
    if (partnerOrdersJob?.isActive != true) {
      loadOrders()
    }
    rebuildInboxNotifications()
    evaluateLifecycleTimersNow()
  }

  class Factory(
    private val repository: OrderRepository,
    private val context: Context
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return OrderViewModel(repository, context) as T
    }
  }
}
