# Security Specification: Paircart

## 1. Data Invariants
- An order item cannot be created without a valid authenticated user ID that matches `request.auth.uid`.
- An order item's `createdAt` timestamp is immutable once written and must not be set in the future.
- An order item's `userId` (creator) is immutable once written.
- Only the creator (`userId`) or the assigned partner (`buddyId`) can read an order item.
- Only the creator can delete their own order item.
- Status must follow the allowed lifecycle: `['pending', 'accepted', 'completed', 'cancelled']`.
- Quantities must be positive integers (`quantity >= 1`).
- Prices and total costs must be non-negative numbers (`unitPrice >= 0` and `totalCost >= 0`).
- String fields like `itemName` must be bounded to prevent denial-of-wallet payload attacks.

## 2. The Dirty Dozen Payloads
1. **Unauthenticated Read**: Attempting to read `/order_items/{id}` without signing in -> DENIED.
2. **Unauthenticated Create**: Attempting to create an item without auth token -> DENIED.
3. **Cross-User Snooping**: Bob attempting to read Alice's order item where Bob is not `buddyId` -> DENIED.
4. **Identity Spoofing**: Alice creating an item with `userId: "bob_uid"` -> DENIED.
5. **Negative Quantity**: Creating an item with `quantity: -5` -> DENIED.
6. **Negative Price**: Creating an item with `totalCost: -20.0` -> DENIED.
7. **Oversized String (DOW)**: Injecting a 2MB string into `itemName` -> DENIED.
8. **Invalid Status Transition**: Setting `status: "hacked"` -> DENIED.
9. **Future Timestamp Attack**: Writing `createdAt` set to year 2099 -> DENIED.
10. **Immutable Field Tampering**: Modifying `createdAt` or `userId` on update -> DENIED.
11. **Malicious Ghost Fields**: Sending extra undocumented fields on update -> DENIED via `affectedKeys()`.
12. **Unauthorized Deletion**: Bob attempting to delete Alice's order item -> DENIED.
