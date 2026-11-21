const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";
const ADMIN_UID = "admin_joy_001";
const ADMIN_EMAIL = "zahidhasan2000joy@gmail.com";

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: "127.0.0.1",
      port: 8085,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or create orders", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("orders").get());
  await assertFails(
    unauthDb.collection("orders").doc("ord_1").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [{ name: "Milk", quantity: 1, isBought: false, boughtPrice: 0.0 }],
      totalCost: 0.0,
      createdAt: new Date(),
    })
  );
});

test("Authenticated Alice can create invoice order for buddy Bob", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("orders").doc("ord_alice_1").set({
      orderNumber: "#INV-1001",
      userId: ALICE_UID,
      userName: "Alice",
      buddyId: BOB_UID,
      partnerName: "Bob",
      status: "pending",
      items: [
        { name: "Apples", quantity: 3, notes: "Red ones", isBought: false, boughtPrice: 0.0 },
        { name: "Bread", quantity: 1, notes: "Whole wheat", isBought: false, boughtPrice: 0.0 }
      ],
      totalCost: 0.0,
      formattedDate: "31 Oct, 2026",
      formattedTime: "05:30 PM",
      createdAt: new Date(),
    })
  );
});

test("Charlie cannot read Alice and Bob's order", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("orders").doc("ord_private").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [{ name: "Coffee", quantity: 2, isBought: false, boughtPrice: 0.0 }],
      totalCost: 0.0,
      createdAt: new Date(),
    });
  });

  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  await assertFails(charlieDb.collection("orders").doc("ord_private").get());
});

test("Buddy Bob can read order, accept it, and update item purchased price", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("orders").doc("ord_to_fulfill").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [{ name: "Eggs", quantity: 1, isBought: false, boughtPrice: 0.0 }],
      totalCost: 0.0,
      createdAt: new Date(),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(bobDb.collection("orders").doc("ord_to_fulfill").get());

  await assertSucceeds(
    bobDb.collection("orders").doc("ord_to_fulfill").update({
      status: "completed",
      items: [{ name: "Eggs", quantity: 1, isBought: true, boughtPrice: 3.50 }],
      totalCost: 3.50,
      updatedAt: new Date(),
    })
  );
});

test("Buddy Bob can reject with reason, accept with shopping deadline, mark incomplete, expire, and rejects invalid status", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("orders").doc("ord_lifecycle").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      createdAtMs: 1700000000000,
      items: [
        { name: "Milk", quantity: 1, isBought: false, isUnavailable: false, boughtPrice: 0.0 },
        { name: "Sugar", quantity: 1, isBought: false, isUnavailable: false, boughtPrice: 0.0 }
      ],
      totalCost: 0.0,
      createdAt: new Date(),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // Invalid status must be rejected
  await assertFails(
    bobDb.collection("orders").doc("ord_lifecycle").update({
      status: "unknown_status",
      updatedAt: new Date(),
    })
  );

  // Accept with shopping duration & deadline
  await assertSucceeds(
    bobDb.collection("orders").doc("ord_lifecycle").update({
      status: "accepted",
      acceptedAtMs: 1700000300000,
      shoppingDurationMinutes: 45,
      shoppingDeadlineMs: 1700003000000,
      updatedAt: new Date(),
    })
  );

  // Mark one item unavailable & mark incomplete with reason
  await assertSucceeds(
    bobDb.collection("orders").doc("ord_lifecycle").update({
      status: "incomplete",
      incompleteReason: "Market closed due to rain",
      items: [
        { name: "Milk", quantity: 1, isBought: false, isUnavailable: true, unavailableReason: "Out of stock", boughtPrice: 0.0 },
        { name: "Sugar", quantity: 1, isBought: false, isUnavailable: false, boughtPrice: 0.0 }
      ],
      updatedAt: new Date(),
    })
  );

  // Reject with reason
  await assertSucceeds(
    bobDb.collection("orders").doc("ord_lifecycle").update({
      status: "rejected",
      rejectionReason: "Busy at office",
      updatedAt: new Date(),
    })
  );

  // Sender can mark expired after 30 mins
  await assertSucceeds(
    aliceDb.collection("orders").doc("ord_lifecycle").update({
      status: "expired",
      updatedAt: new Date(),
    })
  );
});

test("Unauthenticated user cannot read or write market_purchases", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("market_purchases").get());
  await assertFails(
    unauthDb.collection("market_purchases").doc("mp_1").set({
      userId: BOB_UID,
      orderId: "ord_1",
      itemName: "Milk",
      boughtPrice: 90.0,
      marketSource: "manual",
      marketName: "Karwan Bazar",
      createdAt: new Date(),
    })
  );
});

test("Authenticated buyer can create and read own market_purchase, rejects blank marketName, and blocks other users", async () => {
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(
    bobDb.collection("market_purchases").doc("mp_bob_1").set({
      userId: BOB_UID,
      orderId: "ord_to_fulfill",
      orderNumber: "#INV-2020",
      requesterId: ALICE_UID,
      itemName: "Milk",
      quantity: 2,
      boughtPrice: 180.0,
      marketSource: "manual",
      marketName: "Karwan Bazar",
      createdAt: new Date(),
    })
  );

  await assertSucceeds(
    bobDb.collection("market_purchases").where("userId", "==", BOB_UID).get()
  );

  await assertFails(
    bobDb.collection("market_purchases").doc("mp_blank_market").set({
      userId: BOB_UID,
      orderId: "ord_to_fulfill",
      itemName: "Milk",
      boughtPrice: 180.0,
      marketSource: "manual",
      marketName: "",
      createdAt: new Date(),
    })
  );

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("market_purchases").doc("mp_bob_1").get());

  await assertFails(
    bobDb.collection("market_purchases").doc("mp_spoof").set({
      userId: ALICE_UID,
      orderId: "ord_1",
      itemName: "Milk",
      boughtPrice: 90.0,
      marketSource: "manual",
      marketName: "Fake Market",
      createdAt: new Date(),
    })
  );
});

test("Gift claims: enforces 180 coins server-side, isolates PII from non-owners, and grants verified Admin access", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc("123456").set({
      uid: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      numericId: "123456",
      coins: 50,
    });
    await context.firestore().collection("users").doc("654321").set({
      uid: BOB_UID,
      email: "bob@example.com",
      displayName: "Bob",
      numericId: "654321",
      coins: 180,
    });
    await context.firestore().collection("orders").doc("ord_shared").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [{ name: "Tea", quantity: 1, isBought: false, boughtPrice: 0.0 }],
      totalCost: 0.0,
      createdAt: new Date(),
    });
  });

  // Alice with only 50 coins CANNOT submit a gift claim even if spoofing coins: 180
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("gift_claims").doc(ALICE_UID).set({
      userId: ALICE_UID,
      userNumericId: "123456",
      userEmail: "alice@example.com",
      userName: "Alice",
      coins: 180,
      recipientName: "Alice",
      phone: "01711111111",
      address: "House 1, Road 2, Dhaka",
      status: "pending",
      createdAt: new Date(),
    })
  );

  // Bob with 180 coins CAN submit his own gift claim
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(
    bobDb.collection("gift_claims").doc(BOB_UID).set({
      userId: BOB_UID,
      userNumericId: "654321",
      userEmail: "bob@example.com",
      userName: "Bob",
      coins: 180,
      recipientName: "Bob Rahman",
      phone: "01812345678",
      address: "House 45, Mirpur 10, Dhaka",
      note: "Call before delivery",
      status: "pending",
      createdAt: new Date(),
    })
  );

  // Regular users CANNOT read Bob's private delivery address
  await assertFails(aliceDb.collection("gift_claims").doc(BOB_UID).get());
  await assertFails(aliceDb.collection("gift_claims").get());

  // Spoofed Admin with unverified email MUST FAIL
  const spoofAdminDb = testEnv.authenticatedContext(CHARLIE_UID, {
    email: ADMIN_EMAIL,
    email_verified: false,
  }).firestore();
  await assertFails(spoofAdminDb.collection("gift_claims").get());
  await assertFails(spoofAdminDb.collection("orders").get());

  // Verified Admin (zahidhasan2000joy@gmail.com) CAN read & control all collections
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  await assertSucceeds(adminDb.collection("gift_claims").get());
  await assertSucceeds(adminDb.collection("orders").get());
  await assertSucceeds(adminDb.collection("users").get());
  await assertSucceeds(adminDb.collection("market_purchases").get());

  // Admin can approve/deliver Bob's gift claim and adjust Alice's coins to 180
  await assertSucceeds(
    adminDb.collection("gift_claims").doc(BOB_UID).update({
      status: "delivered",
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(
    adminDb.collection("users").doc("123456").update({
      coins: 180,
    })
  );
  await assertSucceeds(
    adminDb.collection("orders").doc("ord_shared").update({
      status: "completed",
      totalCost: 220.0,
    })
  );
});

test("Notifications broadcast: only verified Admin can broadcast to ALL, all signed-in users can read, non-admin cannot delete", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  // Regular user cannot spoof broadcast notification to ALL
  await assertFails(
    aliceDb.collection("notifications").doc("spoof_all").set({
      targetUid: "ALL",
      title: "Fake",
      message: "Spam",
      type: "admin_broadcast",
      createdAt: new Date(),
    })
  );

  // Verified Admin can create broadcast notification with targetUid: "ALL"
  await assertSucceeds(
    adminDb.collection("notifications").doc("admin_bc_1").set({
      targetUid: "ALL",
      title: "Eid Offer!",
      message: "Collect daily coins to unlock special rewards!",
      type: "admin_broadcast",
      createdAt: new Date(),
    })
  );

  // All signed-in users can query targetUid == "ALL"
  await assertSucceeds(
    aliceDb.collection("notifications").where("targetUid", "==", "ALL").get()
  );
  await assertSucceeds(
    bobDb.collection("notifications").where("targetUid", "==", "ALL").get()
  );

  // Unauthenticated user cannot read ALL broadcasts
  await assertFails(
    unauthDb.collection("notifications").where("targetUid", "==", "ALL").get()
  );

  // Regular user cannot delete the shared broadcast document
  await assertFails(
    aliceDb.collection("notifications").doc("admin_bc_1").delete()
  );
});

test("Storage bloat & tamper protection: rejects oversized text, >25 items, arbitrary extra fields, negative/huge prices, self-minting coins, and oversized notifications", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc("123456").set({
      uid: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      numericId: "123456",
      coins: 5,
      lastCoinDate: "2026-11-17",
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // 1. User cannot mint 180 coins directly, only +1 per new day
  await assertFails(
    aliceDb.collection("users").doc("123456").update({
      coins: 180,
      lastCoinDate: "2026-11-18",
    })
  );
  // Same date coin bump rejected
  await assertFails(
    aliceDb.collection("users").doc("123456").update({
      coins: 6,
      lastCoinDate: "2026-11-17",
    })
  );
  // Legitimate daily +1 coin succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc("123456").update({
      coins: 6,
      lastCoinDate: "2026-11-18",
    })
  );

  // 2. Arbitrary extra bloat field on order is rejected
  await assertFails(
    aliceDb.collection("orders").doc("ord_bloat_key").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [{ name: "Milk", quantity: 1, isBought: false, boughtPrice: 0.0 }],
      totalCost: 0.0,
      extraGarbageBlob: "x".repeat(5000),
      createdAt: new Date(Date.now() - 1000),
    })
  );

  // 3. Order with >25 items or 0 items is rejected
  const tooManyItems = Array.from({ length: 26 }, (_, i) => ({
    name: `Item ${i}`,
    quantity: 1,
    isBought: false,
    boughtPrice: 0.0,
  }));
  await assertFails(
    aliceDb.collection("orders").doc("ord_too_many").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: tooManyItems,
      totalCost: 0.0,
      createdAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("orders").doc("ord_empty_items").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [],
      totalCost: 0.0,
      createdAt: new Date(Date.now() - 1000),
    })
  );

  // 4. Negative or absurdly large totalCost rejected
  await assertFails(
    aliceDb.collection("orders").doc("ord_bad_cost").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [{ name: "Milk", quantity: 1, isBought: false, boughtPrice: 0.0 }],
      totalCost: 999999999.0,
      createdAt: new Date(Date.now() - 1000),
    })
  );

  // 5. Oversized notification message (>600 chars) or unknown type rejected
  await assertFails(
    aliceDb.collection("notifications").doc("notif_bloat").set({
      targetUid: BOB_UID,
      title: "Spam",
      message: "A".repeat(650),
      type: "new_order",
      createdAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("notifications").doc("notif_bad_type").set({
      targetUid: BOB_UID,
      title: "Fake Admin",
      message: "Hello",
      type: "admin_message",
      createdAt: new Date(Date.now() - 1000),
    })
  );
});

test("Extended profile edit: allows once per month, blocks second edit in same month, and rejects oversized/spoofed data", async () => {
  const now = new Date();
  const currentMonth = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, "0")}`;

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc("654321").set({
      uid: ALICE_UID,
      numericId: "654321",
      displayName: "Alice",
      coins: 10,
      lastCoinDate: "2026-11-18",
      profilePhotoData: "",
      birthday: "",
      profession: "",
      address: "",
      lastProfileEditMonth: "",
      lastProfileEditAtMs: 0,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // 1. Oversized photo payload (>210,000 chars) rejected
  await assertFails(
    aliceDb.collection("users").doc("654321").update({
      profilePhotoData: "data:image/webp;base64," + "A".repeat(212000),
      lastProfileEditMonth: currentMonth,
      lastProfileEditAtMs: Date.now(),
    })
  );

  // 2. Spoofed future month rejected
  await assertFails(
    aliceDb.collection("users").doc("654321").update({
      profession: "Designer",
      lastProfileEditMonth: "2099-05",
      lastProfileEditAtMs: Date.now(),
    })
  );

  // 3. First valid edit in current month succeeds (compact WebP)
  await assertSucceeds(
    aliceDb.collection("users").doc("654321").update({
      profilePhotoData: "data:image/webp;base64,UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA=",
      birthday: "1998-03-15",
      profession: "Software Engineer",
      address: "House 12, Dhanmondi, Dhaka",
      lastProfileEditMonth: currentMonth,
      lastProfileEditAtMs: Date.now(),
    })
  );

  // 4. Second edit in the SAME month is strictly blocked by Firestore rules
  await assertFails(
    aliceDb.collection("users").doc("654321").update({
      profession: "Doctor",
      lastProfileEditMonth: currentMonth,
      lastProfileEditAtMs: Date.now(),
    })
  );

  // 5. Daily coin claim still works in the same month without modifying extended profile
  await assertSucceeds(
    aliceDb.collection("users").doc("654321").update({
      coins: 11,
      lastCoinDate: "2026-11-19",
    })
  );
});

test("Admin can toggle appEnabled ON/OFF; when OFF non-admin logins/writes/gets are blocked while Admin retains full access", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc("111222").set({
      uid: ALICE_UID,
      numericId: "111222",
      displayName: "Alice",
      coins: 10,
      lastCoinDate: "2026-11-18",
    });
    await context.firestore().collection("orders").doc("ord_gate_1").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [{ name: "Milk", quantity: 1, isBought: false, boughtPrice: 0.0 }],
      totalCost: 0.0,
      createdAt: new Date(Date.now() - 1000),
    });
  });

  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  // Unauthenticated client can get app_config/global to inspect ON/OFF status before login, but cannot list or write
  await assertSucceeds(unauthDb.collection("app_config").doc("global").get());
  await assertFails(unauthDb.collection("app_config").get());
  await assertFails(
    unauthDb.collection("app_config").doc("global").set({ appEnabled: false })
  );

  // Non-admin cannot change appEnabled
  await assertFails(
    aliceDb.collection("app_config").doc("global").set({ appEnabled: false })
  );

  // Admin invalid type or unknown field rejected
  await assertFails(
    adminDb.collection("app_config").doc("global").set({ appEnabled: "off" })
  );
  await assertFails(
    adminDb.collection("app_config").doc("global").set({ appEnabled: false, hackKey: true })
  );

  // Admin turns app OFF
  await assertSucceeds(
    adminDb.collection("app_config").doc("global").set({ appEnabled: false }, { merge: true })
  );

  // Pre-login check still reads appEnabled == false
  const globalDoc = await unauthDb.collection("app_config").doc("global").get();
  if (!globalDoc.exists || globalDoc.data().appEnabled !== false) {
    throw new Error("Expected app_config/global appEnabled to be false");
  }

  // Non-admin single-doc reads & writes are denied while app is OFF
  await assertFails(aliceDb.collection("users").doc("111222").get());
  await assertFails(
    aliceDb.collection("users").doc("999888").set({
      uid: ALICE_UID,
      numericId: "999888",
      displayName: "New Alice",
      coins: 0,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc("111222").update({ displayName: "Alice2" })
  );
  await assertFails(aliceDb.collection("orders").doc("ord_gate_1").get());
  await assertFails(
    aliceDb.collection("orders").doc("ord_gate_2").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [{ name: "Bread", quantity: 1, isBought: false, boughtPrice: 0.0 }],
      totalCost: 0.0,
      createdAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("notifications").doc("notif_gate_1").set({
      targetUid: BOB_UID,
      title: "Hi",
      message: "Blocked when off",
      type: "new_order",
      createdAt: new Date(Date.now() - 1000),
    })
  );

  // Admin STILL has full read/write access when app is OFF
  await assertSucceeds(adminDb.collection("users").doc("111222").get());
  await assertSucceeds(adminDb.collection("orders").doc("ord_gate_1").get());
  await assertSucceeds(
    adminDb.collection("users").doc("111222").update({ coins: 25 })
  );

  // Admin turns app back ON
  await assertSucceeds(
    adminDb.collection("app_config").doc("global").set({ appEnabled: true }, { merge: true })
  );

  // Non-admin regains normal access immediately
  await assertSucceeds(aliceDb.collection("users").doc("111222").get());
  await assertSucceeds(
    aliceDb.collection("orders").doc("ord_gate_2").set({
      userId: ALICE_UID,
      buddyId: BOB_UID,
      status: "pending",
      items: [{ name: "Bread", quantity: 1, isBought: false, boughtPrice: 0.0 }],
      totalCost: 0.0,
      createdAt: new Date(Date.now() - 1000),
    })
  );
});

