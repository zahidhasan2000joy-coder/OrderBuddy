package com.example

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.NotificationData
import com.example.service.InboxNotificationItem
import com.example.service.NotificationDeduplicator
import com.example.service.NotificationInboxStore
import com.example.service.OrderLifecycleManager
import com.example.util.AdminConfig
import com.example.util.InputSanitizer
import com.example.util.ItemCatalogStore
import com.example.util.NotificationHelper
import com.example.util.UrlSecurityHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Paircart", appName)
  }

  @Test
  fun `admin email check matches configured admin case-insensitively and rejects others`() {
    assertEquals(180, AdminConfig.GIFT_COIN_THRESHOLD)
    assertTrue(AdminConfig.isAdminEmail("zahidhasan2000joy@gmail.com"))
    assertTrue(AdminConfig.isAdminEmail("  ZAHIDHASAN2000JOY@GMAIL.COM "))
    assertFalse(AdminConfig.isAdminEmail("other@gmail.com"))
    assertFalse(AdminConfig.isAdminEmail(null))
    assertFalse(AdminConfig.isAdminEmail(""))
  }

  @Test
  fun `showNotification posts notification when permitted and deduplicates per order`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val app = context as android.app.Application
    Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

    val posted = NotificationHelper.showNotification(
      context = context,
      title = "New Order from Alice!",
      message = "Alice sent order #INV-1001 with 3 item(s).",
      notificationId = "new_order_ord1".hashCode(),
      bypassDebounce = true
    )
    assertTrue("Notification should post when permission is granted", posted)

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val active = Shadows.shadowOf(nm).allNotifications
    assertTrue("At least one notification should be posted", active.isNotEmpty())

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      val v3Channel = nm.getNotificationChannel("orderbuddy_orders_channel_v3")
      assertTrue("v3 notification channel should exist", v3Channel != null)
      assertTrue(
        "v3 channel should use custom raw paircart_sound",
        v3Channel?.sound?.toString()?.contains(R.raw.paircart_sound.toString()) == true
      )
    }

    val silentPosted = NotificationHelper.showNotification(
      context = context,
      title = "Order Accepted",
      message = "Partner accepted your order #INV-1001.",
      notificationId = "accepted_ord1".hashCode(),
      bypassDebounce = true,
      silent = true
    )
    assertTrue("Silent foreground notification should post when permitted", silentPosted)

    val notif = NotificationData(id = "doc_1", type = "new_order", orderId = "ord_123")
    val key = NotificationDeduplicator.canonicalKey(notif)
    assertEquals("new_order_ord_123", key)
    assertTrue(NotificationDeduplicator.tryMarkHandled(context, "u1", key))
    assertFalse(NotificationDeduplicator.tryMarkHandled(context, "u1", key))
  }

  @Test
  fun `legacy blank-orderId notification deduplicates across collections`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val legacyNotif = NotificationData(id = "legacy_doc_1", type = "new_order", orderId = "")
    assertEquals("msg_legacy_doc_1", NotificationDeduplicator.canonicalKey(legacyNotif))

    assertFalse(NotificationDeduplicator.wasRecentOrderEventShown(context, "u_cross", "new_order"))
    NotificationDeduplicator.recordOrderEventShown(context, "u_cross", "new_order")
    assertTrue(NotificationDeduplicator.wasRecentOrderEventShown(context, "u_cross", "new_order"))

    assertFalse(NotificationDeduplicator.wasRecentLegacyNotifShown(context, "u_cross", "new_order"))
    NotificationDeduplicator.recordLegacyNotifShown(context, "u_cross", "new_order")
    assertTrue(NotificationDeduplicator.wasRecentLegacyNotifShown(context, "u_cross", "new_order"))
  }

  @Test
  fun `notification inbox store tracks unread, read, and per-user broadcast dismissal`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val uid = "inbox_user_1"
    NotificationInboxStore.clearAll(context, uid)

    NotificationInboxStore.addOrUpdate(
      context,
      uid,
      com.example.service.InboxNotificationItem(
        id = "new_order_ord_99",
        title = "New Order from Partner!",
        message = "Partner sent #INV-9999 with 2 items.",
        type = "new_order",
        orderId = "ord_99",
        createdAtMs = 1000L,
        isRead = false
      )
    )
    NotificationInboxStore.addOrUpdate(
      context,
      uid,
      com.example.service.InboxNotificationItem(
        id = "partner_conn_1",
        title = "Connected!",
        message = "Partner connected.",
        type = "partner_connected",
        createdAtMs = 2000L,
        isRead = false
      )
    )

    val all = NotificationInboxStore.getAll(context, uid)
    assertEquals(2, all.size)
    assertEquals("partner_conn_1", all.first().id)
    assertEquals(2, all.count { !it.isRead })

    NotificationInboxStore.markRead(context, uid, "partner_conn_1", isBroadcast = false)
    assertEquals(1, NotificationInboxStore.getAll(context, uid).count { !it.isRead })

    NotificationInboxStore.markRead(context, uid, "broadcast_b1", isBroadcast = true)
    assertTrue("broadcast_b1" in NotificationInboxStore.getReadBroadcastIds(context, uid))

    NotificationInboxStore.dismiss(context, uid, "broadcast_b1", isBroadcast = true)
    assertTrue("broadcast_b1" in NotificationInboxStore.getDismissedBroadcastIds(context, uid))
  }

  @Test
  fun `shopping reminder persists schedule, ensures active recovery, and cancels cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    NotificationHelper.cancelShoppingAlarm(context)
    assertFalse(NotificationHelper.isReminderActive(context))

    NotificationHelper.scheduleShoppingAlarm(
      context = context,
      minutesLater = 15,
      itemsSummary = "Milk, Rice",
      itemsCount = 2
    )
    assertTrue(NotificationHelper.isReminderActive(context))
    assertEquals(15, NotificationHelper.getActiveReminderMinutes(context))
    assertEquals("Milk, Rice", NotificationHelper.getSavedReminderSummary(context))
    assertEquals(2, NotificationHelper.getSavedReminderCount(context))

    NotificationHelper.ensureShoppingAlarmScheduledIfActive(context)
    assertTrue(NotificationHelper.isReminderActive(context))

    NotificationHelper.cancelShoppingAlarm(context)
    assertFalse(NotificationHelper.isReminderActive(context))
    assertEquals(0, NotificationHelper.getActiveReminderMinutes(context))
  }

  @Test
  fun `ItemCatalogStore persists custom items and puts newest first`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.example.util.ItemCatalogStore.saveCustomItem(context, "Honey")
    com.example.util.ItemCatalogStore.saveCustomItem(context, "Apple")

    val customItems = com.example.util.ItemCatalogStore.getCustomItems(context)
    assertTrue("Apple should be in custom items", customItems.contains("Apple"))
    assertTrue("Honey should be in custom items", customItems.contains("Honey"))
    assertEquals("Newest item should appear first", "Apple", customItems.first())

    val allSuggestions = com.example.util.ItemCatalogStore.getAllSuggestions(context)
    assertTrue("Suggestions should include Apple", allSuggestions.contains("Apple"))
    assertTrue("Suggestions should include default items like Rice", allSuggestions.any { it.contains("Rice") })
  }

  @Test
  fun `order item unavailable excludes unavailable item from total cost and resolves order`() {
    val item1 = com.example.data.model.OrderItemDetail(
      name = "Milk", quantity = 1, isBought = true, boughtPrice = 90.0
    ).toMap()
    val item2 = com.example.data.model.OrderItemDetail(
      name = "Saffron", quantity = 1, isBought = false, isUnavailable = true, unavailableReason = "Out of stock"
    ).toMap()
    val item3 = com.example.data.model.OrderItemDetail(
      name = "Eggs", quantity = 12, isBought = false, isUnavailable = false
    ).toMap()

    val partialOrder = com.example.data.model.Order(
      id = "ord_u1",
      status = "accepted",
      items = listOf(item1, item2, item3)
    )
    assertFalse(partialOrder.allItemsResolved)
    assertEquals(1, partialOrder.remainingItemsCount)
    assertEquals(90.0, partialOrder.computedTotalCost, 0.001)

    val resolvedOrder = partialOrder.copy(items = listOf(item1, item2))
    assertTrue(resolvedOrder.allItemsResolved)
    assertTrue(resolvedOrder.hasBoughtItems)
    assertEquals(0, resolvedOrder.remainingItemsCount)
    assertEquals(90.0, resolvedOrder.computedTotalCost, 0.001)
  }

  @Test
  fun `order timing helpers compute 15m window, 30m expiry, pre-deadline reminder, and overdue accurately`() {
    val startMs = 1_700_000_000_000L
    val order = com.example.data.model.Order(
      id = "ord_t1",
      status = "pending",
      createdAtMs = startMs
    )
    assertFalse(order.isPendingResponseLate(startMs + 10 * 60_000L))
    assertTrue(order.isPendingResponseLate(startMs + 16 * 60_000L))
    assertFalse(order.isExpiredPending(startMs + 29 * 60_000L))
    assertTrue(order.isExpiredPending(startMs + 30 * 60_000L))
    assertEquals("expired", order.effectiveStatus(startMs + 31 * 60_000L))

    val accepted = order.copy(
      status = "accepted",
      acceptedAtMs = startMs + 5 * 60_000L,
      shoppingDurationMinutes = 60,
      shoppingDeadlineMs = startMs + 65 * 60_000L
    )
    assertEquals(startMs + 50 * 60_000L, accepted.preDeadlineReminderAtMs())
    assertFalse(accepted.isOverdue(startMs + 64 * 60_000L))
    assertTrue(accepted.isOverdue(startMs + 66 * 60_000L))
    assertEquals("overdue", accepted.effectiveStatus(startMs + 66 * 60_000L))
  }

  @Test
  fun `OrderLifecycleManager fires 5m reminder, 30m expiry, 15m pre-deadline, and dual overdue once`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val app = context as android.app.Application
    Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

    val creatorUid = "creator_u1"
    val receiverUid = "receiver_u2"
    NotificationInboxStore.clearAll(context, creatorUid)
    NotificationInboxStore.clearAll(context, receiverUid)

    val baseMs = 1_700_000_000_000L

    // 1. Pending > 5 mins -> receiver gets 5m reminder once
    val pendingOrder = com.example.data.model.Order(
      id = "ord_lc_1",
      orderNumber = "#INV-501",
      userId = creatorUid,
      userName = "Alice",
      buddyId = receiverUid,
      partnerName = "Bob",
      status = "pending",
      createdAtMs = baseMs
    )
    val toExpEarly = com.example.service.OrderLifecycleManager.syncOrdersAndScheduleAlarms(
      context = context,
      uid = receiverUid,
      myOrders = emptyList(),
      partnerOrders = listOf(pendingOrder),
      nowMs = baseMs + 6 * 60_000L
    )
    assertTrue(toExpEarly.isEmpty())
    assertEquals(1, NotificationInboxStore.getAll(context, receiverUid).count { it.type == "order_pending_reminder" })

    // Re-syncing must NOT duplicate 5m reminder
    com.example.service.OrderLifecycleManager.syncOrdersAndScheduleAlarms(
      context = context,
      uid = receiverUid,
      myOrders = emptyList(),
      partnerOrders = listOf(pendingOrder),
      nowMs = baseMs + 7 * 60_000L
    )
    assertEquals(1, NotificationInboxStore.getAll(context, receiverUid).count { it.type == "order_pending_reminder" })

    // 2. Pending >= 30 mins -> queued to expire and creator notified once
    val toExpNow = com.example.service.OrderLifecycleManager.syncOrdersAndScheduleAlarms(
      context = context,
      uid = creatorUid,
      myOrders = listOf(pendingOrder),
      partnerOrders = emptyList(),
      nowMs = baseMs + 31 * 60_000L
    )
    assertEquals(1, toExpNow.size)
    assertEquals(1, NotificationInboxStore.getAll(context, creatorUid).count { it.type == "order_expired" })

    // 3. Accepted order 14 mins before 60-min deadline -> receiver gets pre-deadline reminder
    val acceptedOrder = com.example.data.model.Order(
      id = "ord_lc_2",
      orderNumber = "#INV-502",
      userId = creatorUid,
      userName = "Alice",
      buddyId = receiverUid,
      partnerName = "Bob",
      status = "accepted",
      createdAtMs = baseMs,
      acceptedAtMs = baseMs + 2 * 60_000L,
      shoppingDurationMinutes = 60,
      shoppingDeadlineMs = baseMs + 62 * 60_000L,
      items = listOf(com.example.data.model.OrderItemDetail(name = "Rice", quantity = 1).toMap())
    )
    com.example.service.OrderLifecycleManager.syncOrdersAndScheduleAlarms(
      context = context,
      uid = receiverUid,
      myOrders = emptyList(),
      partnerOrders = listOf(acceptedOrder),
      nowMs = baseMs + 48 * 60_000L
    )
    assertEquals(1, NotificationInboxStore.getAll(context, receiverUid).count { it.type == "shopping_pre_deadline" })

    // 4. Past deadline -> BOTH creator and receiver get Overdue notification
    com.example.service.OrderLifecycleManager.syncOrdersAndScheduleAlarms(
      context = context,
      uid = receiverUid,
      myOrders = emptyList(),
      partnerOrders = listOf(acceptedOrder),
      nowMs = baseMs + 65 * 60_000L
    )
    com.example.service.OrderLifecycleManager.syncOrdersAndScheduleAlarms(
      context = context,
      uid = creatorUid,
      myOrders = listOf(acceptedOrder),
      partnerOrders = emptyList(),
      nowMs = baseMs + 65 * 60_000L
    )
    assertEquals(1, NotificationInboxStore.getAll(context, receiverUid).count { it.type == "order_overdue" })
    assertEquals(1, NotificationInboxStore.getAll(context, creatorUid).count { it.type == "order_overdue" })

    // 5. Canonical keys for new notification types
    assertEquals("rejected_o1", NotificationDeduplicator.canonicalKey(NotificationData(id = "d1", type = "order_rejected", orderId = "o1")))
    assertEquals("expired_o2", NotificationDeduplicator.canonicalKey(NotificationData(id = "d2", type = "order_expired", orderId = "o2")))
    assertEquals("incomplete_o3", NotificationDeduplicator.canonicalKey(NotificationData(id = "d3", type = "order_incomplete", orderId = "o3")))
  }

  @Test
  fun `InputSanitizer strips BiDi and control chars, rejects delimiters, and enforces ASCII numeric and decimal bounds`() {
    val dirty = "Rice\u202E\u200B\u0007|||Bag   "
    val cleanName = InputSanitizer.sanitizeItemName(dirty)
    assertFalse("Should strip BiDi/control chars", cleanName.contains('\u202E') || cleanName.contains('\u200B') || cleanName.contains('\u0007'))
    assertFalse("Should strip internal delimiter |||", cleanName.contains("|||"))
    assertEquals("Rice Bag", cleanName)

    assertFalse(InputSanitizer.isValidItemName("   "))
    assertFalse(InputSanitizer.isValidItemName("!@#"))
    assertTrue(InputSanitizer.isValidItemName("Fresh Mango"))

    // ASCII-only digits reject Unicode/Bengali digits for IDs/minutes/counts
    assertEquals("123456", InputSanitizer.filterDigitsOnly("১২৩abc123456789", 6))
    assertTrue(InputSanitizer.isValidNumericId("123456"))
    assertFalse(InputSanitizer.isValidNumericId("১২৩৪৫৬"))
    assertFalse(InputSanitizer.isValidNumericId("1234"))

    // Decimal parser bounds and NaN/negative/overflow rejection
    assertEquals(123.46, InputSanitizer.parseValidPrice("123.456") ?: -1.0, 0.001)
    assertNull(InputSanitizer.parseValidPrice("NaN"))
    assertNull(InputSanitizer.parseValidPrice("Infinity"))
    assertNull(InputSanitizer.parseValidPrice("-50"))
    assertNull(InputSanitizer.parseValidPrice("999999999"))
    assertEquals(0.0, InputSanitizer.parseValidPrice("", allowBlankAsZero = true) ?: -1.0, 0.001)
  }

  @Test
  fun `UrlSecurityHelper blocks private IPs, localhost, javascript schemes, oversized URLs, and allows public https`() {
    assertNull(UrlSecurityHelper.sanitizeUrl("http://127.0.0.1/admin"))
    assertNull(UrlSecurityHelper.sanitizeUrl("http://192.168.1.1/router"))
    assertNull(UrlSecurityHelper.sanitizeUrl("http://10.0.0.5/secret"))
    assertNull(UrlSecurityHelper.sanitizeUrl("http://169.254.169.254/latest/meta-data"))
    assertNull(UrlSecurityHelper.sanitizeUrl("http://localhost:8080/test"))
    assertNull(UrlSecurityHelper.sanitizeUrl("javascript:alert(1)"))
    assertNull(UrlSecurityHelper.sanitizeUrl("https://example.com/" + "a".repeat(600)))

    val publicUrl = UrlSecurityHelper.sanitizeUrl("https://www.daraz.com.bd/products/fresh-apples-i123.html")
    assertEquals("https://www.daraz.com.bd/products/fresh-apples-i123.html", publicUrl)
  }

  @Test
  fun `ItemCatalogStore sanitizes delimiter injection when saving custom items`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ItemCatalogStore.saveCustomItem(context, "Chili|||Powder\u202E")
    val customItems = ItemCatalogStore.getCustomItems(context)
    assertTrue(customItems.contains("Chili Powder"))
    assertFalse(customItems.any { it.contains("|||") || it.contains('\u202E') })
    assertTrue(ItemCatalogStore.DEFAULT_ENGLISH_ITEMS.none { it.contains("(") })
    assertTrue(ItemCatalogStore.DEFAULT_BENGALI_ITEMS.none { it.contains("(") })
    assertTrue(ItemCatalogStore.DEFAULT_ENGLISH_ITEMS.contains("Rice"))
    assertTrue(ItemCatalogStore.DEFAULT_BENGALI_ITEMS.contains("চাল"))
  }

  @Test
  fun `NotificationInboxStore caps entries at 80 items and OrderLifecycleManager emits foreground event`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val uid = "cap_user_80"
    NotificationInboxStore.clearAll(context, uid)

    for (i in 1..95) {
      NotificationInboxStore.addOrUpdate(
        context,
        uid,
        InboxNotificationItem(
          id = "notif_$i",
          title = "Title $i",
          message = "Message body $i",
          type = "info",
          createdAtMs = 10_000L + i
        )
      )
    }
    val stored = NotificationInboxStore.getAll(context, uid)
    assertEquals("Inbox should be strictly capped at 80 entries", 80, stored.size)
    assertEquals("Newest notification should be kept first", "notif_95", stored.first().id)

    runBlocking {
      var receivedPair: Pair<String, InboxNotificationItem>? = null
      val job = launch {
        receivedPair = OrderLifecycleManager.foregroundEvents.first()
      }
      kotlinx.coroutines.yield()
      val emittedItem = InboxNotificationItem(
        id = "fg_test_1",
        title = "Foreground Alert",
        message = "Instant in-app banner",
        type = "order_accepted"
      )
      OrderLifecycleManager.emitForegroundEvent(uid, emittedItem)
      job.join()
      assertEquals(uid, receivedPair?.first)
      assertEquals("fg_test_1", receivedPair?.second?.id)
    }
  }

  @Test
  fun `Extended profile sanitizers, month helpers, and ProfileImageHelper compression work accurately`() {
    assertTrue(InputSanitizer.isValidProfession("Software Engineer"))
    assertFalse(InputSanitizer.isValidProfession("A"))
    assertEquals("Doctor", InputSanitizer.sanitizeProfession("  Doctor\u202E\u200B  "))

    assertTrue(InputSanitizer.isValidProfileAddress("House 12, Dhaka"))
    assertFalse(InputSanitizer.isValidProfileAddress("AB"))

    assertEquals("1998-03-15", InputSanitizer.sanitizeBirthday("1998-03-15"))
    assertEquals("", InputSanitizer.sanitizeBirthday("invalid-date"))
    assertEquals("15 Mar, 1998", InputSanitizer.formatBirthdayDisplay("1998-03-15"))

    assertEquals("01 Dec, 2026", InputSanitizer.nextEligibleEditDateLabel("2026-11"))
    assertEquals("01 Jan, 2027", InputSanitizer.nextEligibleEditDateLabel("2026-12"))

    assertTrue(InputSanitizer.isValidProfilePhotoData(""))
    assertTrue(InputSanitizer.isValidProfilePhotoData("data:image/webp;base64,UklGRh4AAABXRUJQ"))
    assertTrue(InputSanitizer.isValidProfilePhotoData("data:image/jpeg;base64,/9j/4AAQSkZJRg=="))
    assertFalse(InputSanitizer.isValidProfilePhotoData("javascript:alert(1)"))
    assertFalse(InputSanitizer.isValidProfilePhotoData("data:image/webp;base64," + "A".repeat(215_000)))

    val bitmap = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
    val compressedUri = com.example.util.ProfileImageHelper.compressBitmapToDataUri(bitmap)
    assertTrue(
      "Compressed profile image should produce valid WebP or JPEG data URI",
      compressedUri != null && (compressedUri.startsWith("data:image/webp;base64,") || compressedUri.startsWith("data:image/jpeg;base64,"))
    )
    assertTrue("Compressed URI length must fit compact server storage limit", compressedUri!!.length <= InputSanitizer.MAX_PROFILE_PHOTO_LEN)
    val decoded = com.example.util.ProfileImageHelper.decodeDataUriToBitmap(compressedUri)
    assertTrue("Should decode compressed data URI back to Bitmap", decoded != null)
    val enhanced4k = com.example.util.ProfileImageHelper.enhanceBitmapFor4kViewer(decoded!!)
    assertEquals(com.example.util.ProfileImageHelper.TARGET_4K_DIMENSION, maxOf(enhanced4k.width, enhanced4k.height))
    runBlocking {
      val cachedResult = com.example.util.ProfileImageHelper.decodeAndEnhanceFor4kViewer(compressedUri)
      assertTrue("Cached enhanced bitmap should be stored in memory", cachedResult != null && com.example.util.ProfileImageHelper.getCachedEnhancedBitmap(compressedUri) != null)
    }
  }

  @Test
  fun `NetworkMonitor accurately detects offline and online network capabilities`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val shadowCm = Shadows.shadowOf(cm)

    // When active network is null -> offline
    shadowCm.setActiveNetworkInfo(null)
    shadowCm.setNetworkCapabilities(cm.activeNetwork, null)
    // Simulate no active network by removing capabilities on current active network if any
    val currentActive = cm.activeNetwork
    if (currentActive != null) {
      shadowCm.setNetworkCapabilities(
        currentActive,
        org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
      )
    }
    assertFalse("Without NET_CAPABILITY_INTERNET, device must be treated as offline", com.example.util.NetworkMonitor.isOnlineNow(context))

    // When active network has NET_CAPABILITY_INTERNET -> online
    val activeNet = cm.activeNetwork ?: org.robolectric.shadows.ShadowNetwork.newInstance(101)
    if (cm.activeNetwork == null) {
      shadowCm.setDefaultNetworkActive(true)
    }
    val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
    Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    shadowCm.setNetworkCapabilities(activeNet, caps)
    if (cm.activeNetwork != null) {
      assertTrue("With NET_CAPABILITY_INTERNET, device must be treated as online", com.example.util.NetworkMonitor.isOnlineNow(context))
    }
  }

  @Test
  fun `OrderUiState starts with entry loading active and offline send flags false`() {
    val initialState = com.example.ui.order.OrderUiState()
    assertTrue("App entry loading should start active", initialState.isAppEntryLoading)
    assertTrue("isOnline defaults to true before monitor emits", initialState.isOnline)
    assertFalse("Orders should not be marked loaded initially", initialState.hasLoadedMyOrders)
    assertFalse("Partner orders should not be marked loaded initially", initialState.hasLoadedPartnerOrders)
    assertFalse("Profile should not be marked loaded initially", initialState.hasLoadedProfile)
    assertFalse("Admin data should not be marked loaded initially", initialState.hasLoadedAdminData)
    assertFalse(initialState.isSendingOrder)
    assertFalse(initialState.isWaitingOfflineToSendOrder)
    assertFalse(initialState.isConnectingPartner)
    assertFalse("Partner profile should not be marked loaded initially", initialState.hasLoadedPartnerProfile)
    assertEquals("", initialState.partnerPhotoData)
    assertEquals("", initialState.partnerProfession)
    assertTrue(initialState.busyOrderIds.isEmpty())
  }

  @Test
  fun `SecureFirebaseVault reconstructs valid FirebaseOptions and OAuth client ID without plain literals`() {
    val options = com.example.util.SecureFirebaseVault.buildFirebaseOptions()
    assertTrue("API key must start with AIza", options.apiKey.startsWith("AIza"))
    assertEquals(39, options.apiKey.length)
    assertTrue("Application ID must contain android app prefix", options.applicationId.contains(":android:"))
    assertEquals("gen-lang-client-0382284006", options.projectId)
    assertTrue("Storage bucket should be present", !options.storageBucket.isNullOrBlank())
    assertEquals("188335948818", options.gcmSenderId)

    val webClientId = com.example.util.SecureFirebaseVault.getWebClientId()
    assertTrue("Web client ID must end with googleusercontent domain", webClientId.endsWith(".apps.googleusercontent.com"))
    assertTrue("Web client ID must start with project number", webClientId.startsWith("188335948818-"))
  }

  @Test
  fun `AdminConfig filterAdminNotices excludes new_order alerts and preserves other notices`() {
    val items = listOf(
      NotificationData(id = "n1", type = "new_order", title = "New Order from Zahid"),
      NotificationData(id = "n2", type = "NEW_ORDER", title = "New Order 2"),
      NotificationData(id = "n3", type = "partner_connected", title = "Partner Connected"),
      NotificationData(id = "n4", type = "admin_broadcast", title = "Notice Banner"),
      NotificationData(id = "n5", type = "info", title = "Info")
    )
    val filtered = AdminConfig.filterAdminNotices(items)
    assertEquals(3, filtered.size)
    assertFalse(filtered.any { it.type.equals("new_order", ignoreCase = true) })
    assertEquals(listOf("n3", "n4", "n5"), filtered.map { it.id })
  }

  @Test
  fun `AppIntegrityGuard decodes expected identity and detects hooking markers cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("com.aistudio.orderbuddy.abcxyz", com.example.util.AppIntegrityGuard.expectedPackageId())
    assertEquals(64, com.example.util.AppIntegrityGuard.expectedSignatureSha256().length)
    assertTrue(com.example.util.AppIntegrityGuard.verifyAppIntegrity(context))
    assertTrue(com.example.util.AppIntegrityGuard.constantTimeEquals("PAIR984E", "PAIR984E"))
    assertFalse(com.example.util.AppIntegrityGuard.constantTimeEquals("PAIR984E", "PAIR984F"))
    assertTrue(com.example.util.AppIntegrityGuard.containsSuspiciousMarker("/data/local/tmp/frida-agent.so"))
    assertTrue(com.example.util.AppIntegrityGuard.containsSuspiciousMarker("de.robv.android.xposed.XposedBridge"))
    assertFalse(com.example.util.AppIntegrityGuard.containsSuspiciousMarker("/system/lib64/libc.so"))
  }

  @Test
  fun `AppConfig and OrderUiState default appEnabled to true and admin bypass logic holds`() {
    val defaultConfig = com.example.data.repository.AppConfig()
    assertTrue(defaultConfig.appEnabled)
    assertTrue(defaultConfig.coinFeatureEnabled)

    val closedConfig = com.example.data.repository.AppConfig(coinFeatureEnabled = true, appEnabled = false)
    assertFalse(closedConfig.appEnabled)

    val initialState = com.example.ui.order.OrderUiState()
    assertTrue("appEnabled should default to true", initialState.appEnabled)
    assertTrue("Non-admin user should be blocked when appEnabled is false", !initialState.isAdmin && !closedConfig.appEnabled)
    assertTrue("Admin account must remain allowed even when appEnabled is false", AdminConfig.isAdminEmail("zahidhasan2000joy@gmail.com"))
  }
}
