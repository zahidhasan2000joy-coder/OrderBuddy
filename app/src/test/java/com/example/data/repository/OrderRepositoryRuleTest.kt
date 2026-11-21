package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.MarketPurchase
import com.example.data.model.NotificationData
import com.example.data.model.OrderItemDetail
import com.example.service.NotificationDeduplicator
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class OrderRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun createOrderAndCompleteWithManualMarket_savesMarketPurchaseForBuyerOnly() = runBlocking {
    val runTag = UUID.randomUUID().toString().take(8)
    val aliceEmail = "alice_$runTag@example.com"
    val bobEmail = "bob_$runTag@example.com"
    val charlieEmail = "charlie_$runTag@example.com"

    val aliceUid = signInTestUser(aliceEmail)
    val repo = OrderRepository(firestore)

    auth.signOut()
    val bobUid = signInTestUser(bobEmail)
    auth.signOut()
    signInTestUser(aliceEmail)

    val items = listOf(
      OrderItemDetail(name = "Fresh Milk", quantity = 2, notes = "1L pack"),
      OrderItemDetail(name = "Wireless Mouse", quantity = 1, productUrl = "https://www.daraz.com.bd/products/mouse")
    )
    val createRes = withTimeout(5000L) {
      repo.createInvoiceOrder(
        items = items,
        partnerUid = bobUid,
        partnerName = "Bob",
        userName = "Alice",
        orderNumber = "#INV-5501"
      )
    }
    assertTrue("Order creation must succeed: ${createRes.exceptionOrNull()}", createRes.isSuccess)
    val orderId = createRes.getOrThrow()

    // Send single order notification with orderId and verify canonical dedupe key matches order fallback key
    withTimeout(5000L) {
      repo.sendOrderNotification(
        targetUid = bobUid,
        senderName = "Alice",
        orderNumber = "#INV-5501",
        itemCount = items.size,
        orderId = orderId
      )
    }

    // Sign in as Bob (assigned buddy): verify exactly 1 notification exists with canonical key new_order_<orderId>
    auth.signOut()
    signInTestUser(bobEmail)

    val notifDocs = withTimeout(5000L) {
      firestore.collection("notifications")
        .whereEqualTo("targetUid", bobUid)
        .get()
        .await()
    }
    assertEquals(1, notifDocs.size())
    val notifData = notifDocs.documents.first().toObject(NotificationData::class.java)!!
      .copy(id = notifDocs.documents.first().id)
    assertEquals("new_order_$orderId", NotificationDeduplicator.canonicalKey(notifData))

    val acceptRes = withTimeout(5000L) { repo.acceptOrder(orderId) }
    assertTrue("acceptOrder must succeed: ${acceptRes.exceptionOrNull()}", acceptRes.isSuccess)
    assertEquals("accepted", firestore.collection("orders").document(orderId).get().await().getString("status"))

    // Buying first item keeps status "accepted" and orderCompleted = false
    val buyFirstRes = repo.markItemBought(
      orderId = orderId,
      currentItems = items,
      itemIndex = 0,
      pricePaid = 190.0,
      marketName = "",
      orderNumber = "#INV-5501",
      requesterId = aliceUid
    )
    assertTrue("markItemBought item 0 must succeed: ${buyFirstRes.exceptionOrNull()}", buyFirstRes.isSuccess)
    assertFalse(buyFirstRes.getOrThrow().orderCompleted)
    assertEquals("accepted", firestore.collection("orders").document(orderId).get().await().getString("status"))

    // Last item bought with manual market name auto-completes the order (without separate Complete button)
    val updatedAfterFirst = items.toMutableList().also {
      it[0] = it[0].copy(isBought = true, boughtPrice = 190.0)
    }
    val buyLastRes = repo.markItemBought(
      orderId = orderId,
      currentItems = updatedAfterFirst,
      itemIndex = 1,
      pricePaid = 850.0,
      marketName = "Karwan Bazar & Daraz",
      orderNumber = "#INV-5501",
      requesterId = aliceUid
    )
    assertTrue("markItemBought final item must succeed: ${buyLastRes.exceptionOrNull()}", buyLastRes.isSuccess)
    assertTrue(buyLastRes.getOrThrow().orderCompleted)
    assertEquals(1040.0, buyLastRes.getOrThrow().totalCost, 0.01)
    val completedDoc = firestore.collection("orders").document(orderId).get().await()
    assertEquals("completed", completedDoc.getString("status"))
    assertEquals("Karwan Bazar & Daraz", completedDoc.getString("purchaseMarketName"))

    // Verify Bob can read his market_purchases records and marketName matches manual input
    val bobPurchases = firestore.collection("market_purchases")
      .whereEqualTo("userId", bobUid)
      .whereEqualTo("orderId", orderId)
      .get()
      .await()
    assertEquals(2, bobPurchases.size())
    assertTrue(bobPurchases.documents.all { it.getString("marketName") == "Karwan Bazar & Daraz" })
    val firstDocId = bobPurchases.documents.first().id

    // Verify Alice (requester) cannot read Bob's market_purchases
    auth.signOut()
    signInTestUser(aliceEmail)
    try {
      firestore.collection("market_purchases").document(firstDocId).get().await()
      fail("Alice must not be allowed to read Bob's market_purchases document")
    } catch (e: Throwable) {
      val fsErr = e.unwrapFirestoreException()
      assertNotNull("Expected FirebaseFirestoreException", fsErr)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    // Verify Charlie (unrelated third party) cannot read the shared order
    auth.signOut()
    signInTestUser(charlieEmail)
    try {
      firestore.collection("orders").document(orderId).get().await()
      fail("Charlie must not be allowed to read Alice/Bob order")
    } catch (e: Throwable) {
      val fsErr = e.unwrapFirestoreException()
      assertNotNull("Expected FirebaseFirestoreException", fsErr)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }
  }

  @Test
  fun saveMarketPurchase_deniesUnauthenticatedBlankMarketAndSpoofedUserId() = runBlocking {
    val repo = OrderRepository(firestore)
    // Unauthenticated call fails cleanly
    val unauthRes = repo.saveMarketPurchase(
      MarketPurchase(
        userId = "fake_uid",
        orderId = "ord_123",
        itemName = "Rice",
        boughtPrice = 75.0,
        marketSource = "manual",
        marketName = "New Market"
      )
    )
    assertTrue("Unauthenticated saveMarketPurchase must fail", unauthRes.isFailure)

    // Blank marketName fails cleanly
    val bobUid = signInTestUser("bob@example.com")
    val blankMarketRes = repo.saveMarketPurchase(
      MarketPurchase(
        userId = bobUid,
        orderId = "ord_123",
        itemName = "Rice",
        boughtPrice = 75.0,
        marketSource = "manual",
        marketName = "   "
      )
    )
    assertTrue("Blank marketName must be rejected", blankMarketRes.isFailure)

    // Authenticated user cannot write market_purchases with another user's UID
    try {
      firestore.collection("market_purchases").document("spoof_doc").set(
        mapOf(
          "userId" to "other_user_999",
          "orderId" to "ord_123",
          "itemName" to "Rice",
          "boughtPrice" to 75.0,
          "marketSource" to "manual",
          "marketName" to "New Market",
          "createdAt" to FieldValue.serverTimestamp()
        )
      ).await()
      fail("Writing market_purchases with spoofed userId must be denied")
    } catch (e: Throwable) {
      val fsErr = e.unwrapFirestoreException()
      assertNotNull("Expected FirebaseFirestoreException", fsErr)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    // Valid record owned by Bob succeeds
    val validRes = repo.saveMarketPurchase(
      MarketPurchase(
        userId = bobUid,
        orderId = "ord_123",
        itemName = "Rice",
        boughtPrice = 75.0,
        marketSource = "manual",
        marketName = "New Market"
      )
    )
    assertTrue("Valid saveMarketPurchase must succeed: ${validRes.exceptionOrNull()}", validRes.isSuccess)
  }

  @Test
  fun submitGiftClaim_enforces180CoinsIsolatesPiiAndBlocksUnverifiedAdminSpoof() = runBlocking {
    val repo = OrderRepository(firestore)

    // 1. User with < 180 coins cannot submit gift claim (rejected both by repository and by rules)
    val lowCoinEmail = "lowcoin_gift@example.com"
    val lowCoinUid = signInTestUser(lowCoinEmail)
    val lowNumericId = com.example.data.model.UserProfile.generateNumericId(lowCoinEmail)
    withTimeout(5000L) {
      firestore.collection("users").document(lowNumericId).set(
        mapOf(
          "uid" to lowCoinUid,
          "email" to lowCoinEmail,
          "displayName" to "Low Coin",
          "numericId" to lowNumericId,
          "coins" to 120
        )
      ).await()
    }

    val lowRes = withTimeout(5000L) {
      repo.submitGiftClaim(
        myNumericId = lowNumericId,
        coins = 120,
        recipientName = "Low Coin",
        phone = "01711111111",
        address = "Road 1, Dhaka",
        note = ""
      )
    }
    assertTrue("submitGiftClaim with <180 coins must fail", lowRes.isFailure)

    // Even if client tries to spoof coins = 180 directly against Firestore, rule cross-checks /users/{numericId}.coins
    try {
      withTimeout(5000L) {
        firestore.collection("gift_claims").document(lowCoinUid).set(
          mapOf(
            "userId" to lowCoinUid,
            "userNumericId" to lowNumericId,
            "userEmail" to lowCoinEmail,
            "userName" to "Low Coin",
            "coins" to 180,
            "recipientName" to "Low Coin",
            "phone" to "01711111111",
            "address" to "Road 1, Dhaka",
            "status" to "pending",
            "createdAt" to FieldValue.serverTimestamp()
          )
        ).await()
      }
      fail("Spoofed gift_claim for user with <180 coins in /users must be denied by security rules")
    } catch (e: Throwable) {
      val fsErr = e.unwrapFirestoreException()
      assertNotNull("Expected FirebaseFirestoreException", fsErr)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    // 2. User with >= 180 coins submits and updates gift claim successfully
    auth.signOut()
    val winnerEmail = "winner180@example.com"
    val winnerUid = signInTestUser(winnerEmail)
    val winnerNumericId = com.example.data.model.UserProfile.generateNumericId(winnerEmail)
    withTimeout(5000L) {
      firestore.collection("users").document(winnerNumericId).set(
        mapOf(
          "uid" to winnerUid,
          "email" to winnerEmail,
          "displayName" to "Winner User",
          "numericId" to winnerNumericId,
          "coins" to 180
        )
      ).await()
    }

    val submitRes = withTimeout(5000L) {
      repo.submitGiftClaim(
        myNumericId = winnerNumericId,
        coins = 180,
        recipientName = "Winner Rahman",
        phone = "01812345678",
        address = "House 12, Dhanmondi, Dhaka",
        note = "Call before coming"
      )
    }
    assertTrue("submitGiftClaim with 180 coins must succeed: ${submitRes.exceptionOrNull()}", submitRes.isSuccess)

    val savedClaim = withTimeout(5000L) {
      firestore.collection("gift_claims").document(winnerUid).get().await()
    }
    assertEquals(winnerUid, savedClaim.getString("userId"))
    assertEquals(winnerNumericId, savedClaim.getString("userNumericId"))
    assertEquals("Winner Rahman", savedClaim.getString("recipientName"))
    assertEquals("pending", savedClaim.getString("status"))

    // 3. Another user cannot read Winner's private delivery address
    auth.signOut()
    signInTestUser(lowCoinEmail)
    try {
      withTimeout(5000L) {
        firestore.collection("gift_claims").document(winnerUid).get().await()
      }
      fail("Non-owner must be denied reading another user's gift_claim")
    } catch (e: Throwable) {
      val fsErr = e.unwrapFirestoreException()
      assertNotNull("Expected FirebaseFirestoreException", fsErr)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    // 4. Auth emulator createUserWithEmailAndPassword produces email_verified=false;
    // verify unverified admin email CANNOT list all gift_claims
    auth.signOut()
    signInTestUser("zahidhasan2000joy@gmail.com")
    try {
      withTimeout(5000L) {
        firestore.collection("gift_claims").get().await()
      }
      fail("Unverified email token must not gain admin list access")
    } catch (e: Throwable) {
      val fsErr = e.unwrapFirestoreException()
      assertNotNull("Expected FirebaseFirestoreException", fsErr)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }
  }

  @Test
  fun broadcastNotifications_deniesNonAdminCreateAndAllowsSignedInQuery() = runBlocking {
    val runTag = UUID.randomUUID().toString().take(8)
    val userEmail = "broadcast_reader_$runTag@example.com"
    val userUid = signInTestUser(userEmail)
    val repo = OrderRepository(firestore)

    // Non-admin user must be denied creating a broadcast (targetUid = ALL)
    val nonAdminBroadcastRes = withTimeout(5000L) {
      repo.adminBroadcastNotification("Fake Admin", "Should fail")
    }
    assertTrue("Non-admin broadcast must be denied", nonAdminBroadcastRes.isFailure)

    // Non-admin direct Firestore write for targetUid = ALL must also fail with PERMISSION_DENIED
    try {
      withTimeout(5000L) {
        firestore.collection("notifications").document("bc_$runTag").set(
          mapOf(
            "targetUid" to "ALL",
            "title" to "Spoof",
            "message" to "Hello",
            "type" to "admin_broadcast",
            "senderUid" to userUid
          )
        ).await()
      }
      fail("Non-admin must be denied writing targetUid=ALL notification")
    } catch (e: Exception) {
      val fsErr = e.unwrapFirestoreException()
      assertNotNull("Expected FirebaseFirestoreException, got $e", fsErr)
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    // Signed-in user CAN query targetUid == "ALL" without permission error
    val docs = withTimeout(5000L) {
      firestore.collection("notifications")
        .whereEqualTo("targetUid", "ALL")
        .get()
        .await()
    }
    assertNotNull(docs)
  }
}
