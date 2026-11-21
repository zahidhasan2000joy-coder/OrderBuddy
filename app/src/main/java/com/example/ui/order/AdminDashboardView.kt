package com.example.ui.order

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GiftClaim
import com.example.data.model.MarketPurchase
import com.example.data.model.NotificationData
import com.example.data.model.Order
import com.example.data.model.UserProfile
import com.example.ui.components.FacebookFeedSkeletonList
import com.example.ui.theme.OrderGreen
import com.example.util.AdminConfig
import com.example.util.InputSanitizer
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AdminDashboardView(
  uiState: OrderUiState,
  onUpdateUser: (numericId: String, displayName: String, coins: Int, clearPartner: Boolean) -> Unit,
  onDeleteUser: (numericId: String) -> Unit,
  onUpdateOrder: (orderId: String, status: String, totalCost: Double, marketName: String) -> Unit,
  onDeleteOrder: (orderId: String) -> Unit,
  onUpdateGiftClaim: (claimId: String, status: String, recipientName: String, phone: String, address: String, note: String) -> Unit,
  onDeleteGiftClaim: (claimId: String) -> Unit,
  onUpdateMarketPurchase: (purchaseId: String, itemName: String, marketName: String, boughtPrice: Double) -> Unit,
  onDeleteMarketPurchase: (purchaseId: String) -> Unit,
  onSendNotification: (targetUid: String, title: String, message: String) -> Unit,
  onBroadcastNotification: (title: String, message: String) -> Unit,
  onUpdateNotification: (notificationId: String, title: String, message: String) -> Unit = { _, _, _ -> },
  onDeleteNotification: (notificationId: String) -> Unit,
  onToggleCoinFeature: (Boolean) -> Unit,
  onToggleAppEnabled: (Boolean) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var selectedSection by remember { mutableIntStateOf(0) } // 0: Gift Claims, 1: Users, 2: Orders, 3: Notices, 4: Markets
  var searchQuery by remember { mutableStateOf("") }
  var orderStatusFilter by remember { mutableStateOf("all") }

  // Dialog states
  var editingUser by remember { mutableStateOf<UserProfile?>(null) }
  var deletingUser by remember { mutableStateOf<UserProfile?>(null) }
  var editingOrder by remember { mutableStateOf<Order?>(null) }
  var deletingOrder by remember { mutableStateOf<Order?>(null) }
  var editingClaim by remember { mutableStateOf<GiftClaim?>(null) }
  var deletingClaim by remember { mutableStateOf<GiftClaim?>(null) }
  var editingMarket by remember { mutableStateOf<MarketPurchase?>(null) }
  var deletingMarket by remember { mutableStateOf<MarketPurchase?>(null) }
  var notifyTargetUser by remember { mutableStateOf<UserProfile?>(null) }
  var showBroadcastDialog by remember { mutableStateOf(false) }
  var editingNotification by remember { mutableStateOf<NotificationData?>(null) }

  val q = searchQuery.trim().lowercase()
  val visibleAdminNotices = remember(uiState.adminNotifications) {
    AdminConfig.filterAdminNotices(uiState.adminNotifications)
  }

  val giftEligibleUsersWithoutClaim = remember(uiState.adminUsers, uiState.adminGiftClaims) {
    val claimedUids = uiState.adminGiftClaims.map { it.userId }.toSet()
    uiState.adminUsers.filter { it.coins >= AdminConfig.GIFT_COIN_THRESHOLD && it.uid !in claimedUids }
  }

  val filteredClaims = remember(uiState.adminGiftClaims, q) {
    if (q.isBlank()) uiState.adminGiftClaims
    else uiState.adminGiftClaims.filter {
      it.recipientName.contains(q, ignoreCase = true) ||
        it.userName.contains(q, ignoreCase = true) ||
        it.userEmail.contains(q, ignoreCase = true) ||
        it.userNumericId.contains(q, ignoreCase = true) ||
        it.phone.contains(q, ignoreCase = true) ||
        it.address.contains(q, ignoreCase = true)
    }
  }

  val filteredUsers = remember(uiState.adminUsers, q) {
    if (q.isBlank()) uiState.adminUsers
    else uiState.adminUsers.filter {
      it.displayName.contains(q, ignoreCase = true) ||
        it.email.contains(q, ignoreCase = true) ||
        it.numericId.contains(q, ignoreCase = true) ||
        it.connectedPartnerName.contains(q, ignoreCase = true)
    }
  }

  val filteredOrders = remember(uiState.adminOrders, q, orderStatusFilter) {
    uiState.adminOrders.filter { order ->
      val matchesStatus = orderStatusFilter == "all" || order.status == orderStatusFilter
      val matchesSearch = q.isBlank() ||
        order.orderNumber.contains(q, ignoreCase = true) ||
        order.userName.contains(q, ignoreCase = true) ||
        order.partnerName.contains(q, ignoreCase = true) ||
        order.purchaseMarketName.contains(q, ignoreCase = true) ||
        order.typedItems.any { it.name.contains(q, ignoreCase = true) }
      matchesStatus && matchesSearch
    }
  }

  val filteredMarkets = remember(uiState.adminMarketPurchases, q) {
    if (q.isBlank()) uiState.adminMarketPurchases
    else uiState.adminMarketPurchases.filter {
      it.marketName.contains(q, ignoreCase = true) ||
        it.itemName.contains(q, ignoreCase = true) ||
        it.orderNumber.contains(q, ignoreCase = true)
    }
  }

  val totalRevenue = remember(uiState.adminOrders) {
    uiState.adminOrders.sumOf { it.totalCost }
  }
  val total180Users = remember(uiState.adminUsers, uiState.adminGiftClaims) {
    val uids = (uiState.adminUsers.filter { it.coins >= AdminConfig.GIFT_COIN_THRESHOLD }.map { it.uid } +
      uiState.adminGiftClaims.map { it.userId }).toSet()
    uids.size
  }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .testTag("admin_dashboard_view"),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // --- ADMIN HEADER CARD ---
    item {
      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth()
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(
              Brush.linearGradient(
                colors = listOf(Color(0xFF0B3B1E), Color(0xFF146C38))
              )
            )
            .padding(18.dp)
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text(
                  text = "Admin Control Panel",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = Color.White
                )
                Text(
                  text = AdminConfig.ADMIN_EMAIL,
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFFA7F3D0)
                )
              }
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.16f)
              ) {
                Text(
                  text = "৳${String.format(Locale.US, "%.0f", totalRevenue)}",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFFFFD54F),
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              AdminStatPill(
                label = "180+ Gift",
                value = "${uiState.adminGiftClaims.size}/$total180Users",
                highlight = uiState.adminGiftClaims.any { it.status == "pending" },
                onClick = { selectedSection = 0 },
                modifier = Modifier.weight(1f)
              )
              AdminStatPill(
                label = "Users",
                value = "${uiState.adminUsers.size}",
                highlight = false,
                onClick = { selectedSection = 1 },
                modifier = Modifier.weight(1f)
              )
              AdminStatPill(
                label = "Orders",
                value = "${uiState.adminOrders.size}",
                highlight = false,
                onClick = { selectedSection = 2 },
                modifier = Modifier.weight(1f)
              )
              AdminStatPill(
                label = "Notices",
                value = "${visibleAdminNotices.size}",
                highlight = false,
                onClick = { selectedSection = 3 },
                modifier = Modifier.weight(1f)
              )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
              onClick = { showBroadcastDialog = true },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFD54F),
                contentColor = Color(0xFF1B5E20)
              ),
              modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("admin_broadcast_button")
            ) {
              Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Add New Notice (Broadcast)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
              )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (uiState.appEnabled) Color.White.copy(alpha = 0.16f) else Color(0xFFB91C1C).copy(alpha = 0.85f),
              border = if (uiState.appEnabled) null else BorderStroke(1.dp, Color(0xFFFCA5A5)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.weight(1f)
                ) {
                  Icon(
                    imageVector = if (uiState.appEnabled) Icons.Default.CheckCircle else Icons.Default.Lock,
                    contentDescription = if (uiState.appEnabled) "App ON" else "App OFF",
                    tint = if (uiState.appEnabled) Color(0xFF4ADE80) else Color(0xFFFEE2E2),
                    modifier = Modifier.size(20.dp)
                  )
                  Spacer(modifier = Modifier.width(10.dp))
                  Column {
                    Text(
                      text = if (uiState.appEnabled) "App Status: ON" else "App Status: OFF (Closed)",
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold,
                      color = if (uiState.appEnabled) Color(0xFF86EFAC) else Color.White
                    )
                    Text(
                      text = if (uiState.appEnabled) {
                        "Users can sign in and access Paircart"
                      } else {
                        "All non-admin logins & access are blocked"
                      },
                      style = MaterialTheme.typography.labelSmall,
                      color = if (uiState.appEnabled) Color(0xFFA7F3D0) else Color(0xFFFEE2E2)
                    )
                  }
                }
                Switch(
                  checked = uiState.appEnabled,
                  onCheckedChange = { onToggleAppEnabled(it) },
                  colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF065F46),
                    checkedTrackColor = Color(0xFF4ADE80),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF7F1D1D)
                  ),
                  modifier = Modifier.testTag("admin_app_enabled_switch")
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = Color.White.copy(alpha = 0.16f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = if (uiState.coinFeatureEnabled) "Coin & Gift System: Active" else "Coin & Gift System: Off",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (uiState.coinFeatureEnabled) Color(0xFFFFD54F) else Color(0xFFFFCDD2)
                  )
                  Text(
                    text = if (uiState.coinFeatureEnabled) "Coins, gifts & link posting are visible" else "Coin cards & link posting are hidden",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFA7F3D0)
                  )
                }
                Switch(
                  checked = uiState.coinFeatureEnabled,
                  onCheckedChange = { onToggleCoinFeature(it) },
                  colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1B5E20),
                    checkedTrackColor = Color(0xFFFFD54F),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.Gray.copy(alpha = 0.6f)
                  ),
                  modifier = Modifier.testTag("admin_coin_system_switch")
                )
              }
            }
          }
        }
      }
    }

    // --- SEARCH INPUT ---
    item {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = InputSanitizer.sanitizeOnType(it, 100) },
        placeholder = { Text("Search users, 180+ addresses, orders, markets...", fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = OrderGreen) },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Default.Close, contentDescription = "Clear")
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = OrderGreen,
          unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
          focusedContainerColor = MaterialTheme.colorScheme.surface,
          unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("admin_search_input")
      )
    }

    // --- SECTION TABS ---
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        val sections = listOf(
          0 to "180+ Gift (${uiState.adminGiftClaims.size})",
          1 to "Users (${uiState.adminUsers.size})",
          2 to "Orders (${uiState.adminOrders.size})",
          3 to "Notices (${visibleAdminNotices.size})",
          4 to "Markets (${uiState.adminMarketPurchases.size})"
        )
        sections.forEach { (idx, label) ->
          FilterChip(
            selected = selectedSection == idx,
            onClick = { selectedSection = idx },
            label = { Text(label, fontWeight = FontWeight.Bold) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = OrderGreen,
              selectedLabelColor = Color.White
            ),
            modifier = Modifier.testTag("admin_section_chip_$idx")
          )
        }
      }
    }

    // --- SECTION CONTENT ---
    val isAdminEmpty = uiState.adminUsers.isEmpty() && uiState.adminOrders.isEmpty() && uiState.adminGiftClaims.isEmpty()
    if (!uiState.hasLoadedAdminData || (!uiState.isOnline && isAdminEmpty)) {
      item {
        AdminLoadingCard(isOnline = uiState.isOnline)
      }
    } else when (selectedSection) {
      0 -> {
        if (filteredClaims.isEmpty() && giftEligibleUsersWithoutClaim.isEmpty()) {
          item {
            AdminEmptyCard("No users have reached 180 coins and submitted a delivery address yet.")
          }
        } else {
          items(filteredClaims, key = { "claim_${it.id}" }) { claim ->
            AdminGiftClaimCard(
              claim = claim,
              onCopyText = { label, text ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, text))
                Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
              },
              onSetStatus = { newStatus ->
                onUpdateGiftClaim(
                  claim.id,
                  newStatus,
                  claim.recipientName,
                  claim.phone,
                  claim.address,
                  claim.note
                )
              },
              onEdit = { editingClaim = claim },
              onDelete = { deletingClaim = claim }
            )
          }

          if (giftEligibleUsersWithoutClaim.isNotEmpty()) {
            item {
              Text(
                text = "Users with 180+ Coins (Address Not Submitted Yet)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
              )
            }
            items(giftEligibleUsersWithoutClaim, key = { "eligible_${it.numericId}" }) { user ->
              Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                border = BorderStroke(1.dp, Color(0xFFFFB300)),
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "${user.displayName.ifBlank { "User" }} (ID: ${user.numericId})",
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = user.email,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                      text = "${user.coins} Coins • Waiting for delivery address",
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFFB26A00)
                    )
                  }
                  OutlinedButton(
                    onClick = { notifyTargetUser = user },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                  ) {
                    Text("Notify", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                  }
                }
              }
            }
          }
        }
      }

      1 -> {
        if (filteredUsers.isEmpty()) {
          item { AdminEmptyCard("No matching users found.") }
        } else {
          items(filteredUsers, key = { "user_${it.numericId}_${it.uid}" }) { user ->
            AdminUserCard(
              user = user,
              onEdit = { editingUser = user },
              onNotify = { notifyTargetUser = user },
              onDelete = { deletingUser = user }
            )
          }
        }
      }

      2 -> {
        item {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            listOf("all", "pending", "accepted", "completed", "rejected", "incomplete", "expired", "cancelled").forEach { st ->
              FilterChip(
                selected = orderStatusFilter == st,
                onClick = { orderStatusFilter = st },
                label = { Text(st.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) }
              )
            }
          }
        }

        if (filteredOrders.isEmpty()) {
          item { AdminEmptyCard("No orders found.") }
        } else {
          items(filteredOrders, key = { "ord_${it.id}" }) { order ->
            AdminOrderCard(
              order = order,
              onEdit = { editingOrder = order },
              onDelete = { deletingOrder = order }
            )
          }
        }
      }

      3 -> {
        val filteredNotifications = if (q.isBlank()) visibleAdminNotices else {
          visibleAdminNotices.filter {
            it.title.contains(q, ignoreCase = true) || it.message.contains(q, ignoreCase = true)
          }
        }
        item {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "App Notices & Alerts (${filteredNotifications.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold
              )
            }
            Button(
              onClick = { showBroadcastDialog = true },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(containerColor = OrderGreen, contentColor = Color.White),
              modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .testTag("admin_add_new_notice_button")
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Add New Notice", fontWeight = FontWeight.Bold)
            }
          }
        }
        if (filteredNotifications.isEmpty()) {
          item { AdminEmptyCard("No notices or notifications yet. Tap 'Add New Notice' above to create one.") }
        } else {
          items(filteredNotifications, key = { "notif_${it.id}" }) { notif ->
            val isAll = notif.targetUid == "ALL"
            Card(
              shape = RoundedCornerShape(14.dp),
              colors = CardDefaults.cardColors(
                containerColor = if (isAll) Color(0xFFFFFBF0) else MaterialTheme.colorScheme.surface
              ),
              border = BorderStroke(
                1.dp,
                if (isAll) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
              ),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Text(
                    text = notif.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                  )
                  if (isAll) {
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = Color(0xFFFFB300).copy(alpha = 0.25f)
                    ) {
                      Text(
                        text = "BANNER (ALL)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF795548),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                      )
                    }
                  }
                }
                Text(
                  text = notif.message,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = if (isAll) "Shown in Notice Banner & Notifications" else "To UID: ${notif.targetUid.take(12)}... • Type: ${notif.type}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  OutlinedButton(
                    onClick = { editingNotification = notif },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                      .height(34.dp)
                      .testTag("admin_edit_notif_${notif.id}"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                  ) {
                    Icon(Icons.Default.Edit, contentDescription = "Update Notice", tint = OrderGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Update", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = OrderGreen)
                  }
                  OutlinedButton(
                    onClick = { onDeleteNotification(notif.id) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFD93025)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD93025)),
                    modifier = Modifier
                      .height(34.dp)
                      .testTag("admin_delete_notif_${notif.id}"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                  ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Notice", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                  }
                }
              }
            }
          }
        }
      }

      4 -> {
        item {
          Text(
            text = "Market / Bazar Purchase Logs (${filteredMarkets.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold
          )
        }
        if (filteredMarkets.isEmpty()) {
          item { AdminEmptyCard("No market purchase records yet.") }
        } else {
          items(filteredMarkets, key = { "mp_${it.id}" }) { mp ->
            Card(
              shape = RoundedCornerShape(14.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(14.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Text(
                    text = "${mp.itemName} (x${mp.quantity})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = "৳${String.format(Locale.US, "%.2f", mp.boughtPrice)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = OrderGreen
                  )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "${mp.marketName} (${mp.marketSource}) • ${mp.orderNumber}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = "${mp.formattedDate} ${mp.formattedTime}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  OutlinedButton(
                    onClick = { editingMarket = mp },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                  ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit", style = MaterialTheme.typography.labelSmall)
                  }
                  OutlinedButton(
                    onClick = { deletingMarket = mp },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFD93025)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD93025)),
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                  ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelSmall)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  // --- DIALOGS FOR FULL ADMIN DATA CONTROL ---

  editingUser?.let { user ->
    var nameInput by remember(user.numericId) { mutableStateOf(user.displayName) }
    var coinsInput by remember(user.numericId) { mutableStateOf(user.coins.toString()) }
    var clearPartner by remember(user.numericId) { mutableStateOf(false) }

    AlertDialog(
      onDismissRequest = { editingUser = null },
      title = { Text("Edit User #${user.numericId}", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(user.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_PERSON_NAME_LEN) },
            label = { Text("Display Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = coinsInput,
            onValueChange = { coinsInput = InputSanitizer.filterDigitsOnly(it, 6) },
            label = { Text("Coins Balance") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          if (user.connectedPartnerId.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Checkbox(checked = clearPartner, onCheckedChange = { clearPartner = it })
              Text("Disconnect partner (${user.connectedPartnerId})", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onUpdateUser(
              user.numericId,
              InputSanitizer.sanitizePersonName(nameInput),
              (coinsInput.toIntOrNull() ?: user.coins).coerceIn(0, InputSanitizer.MAX_COINS),
              clearPartner
            )
            editingUser = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen)
        ) {
          Text("Save", fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { editingUser = null }) { Text("Cancel") }
      }
    )
  }

  deletingUser?.let { user ->
    AlertDialog(
      onDismissRequest = { deletingUser = null },
      title = { Text("Delete User Profile?", fontWeight = FontWeight.Bold) },
      text = { Text("Delete user ${user.displayName} (ID: ${user.numericId}) from Firestore?") },
      confirmButton = {
        Button(
          onClick = {
            onDeleteUser(user.numericId)
            deletingUser = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025))
        ) {
          Text("Delete", color = Color.White)
        }
      },
      dismissButton = {
        TextButton(onClick = { deletingUser = null }) { Text("Cancel") }
      }
    )
  }

  editingOrder?.let { order ->
    var statusInput by remember(order.id) { mutableStateOf(order.status) }
    var costInput by remember(order.id) { mutableStateOf(String.format(Locale.US, "%.2f", order.totalCost)) }
    var marketInput by remember(order.id) { mutableStateOf(order.purchaseMarketName) }

    AlertDialog(
      onDismissRequest = { editingOrder = null },
      title = { Text("Edit Order ${order.orderNumber}", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Status:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            listOf("pending", "accepted", "completed", "rejected", "incomplete", "expired", "cancelled").forEach { st ->
              FilterChip(
                selected = statusInput == st,
                onClick = { statusInput = st },
                label = { Text(st) }
              )
            }
          }
          OutlinedTextField(
            value = costInput,
            onValueChange = { costInput = InputSanitizer.filterDecimalInput(it, 8, 2) },
            label = { Text("Total Cost (৳)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = marketInput,
            onValueChange = { marketInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_MARKET_NAME_LEN) },
            label = { Text("Market / Bazar Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onUpdateOrder(
              order.id,
              statusInput,
              InputSanitizer.parseValidPrice(costInput, InputSanitizer.MAX_ORDER_COST, allowBlankAsZero = true)
                ?: order.totalCost,
              InputSanitizer.sanitizeMarketName(marketInput)
            )
            editingOrder = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen)
        ) {
          Text("Save", fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { editingOrder = null }) { Text("Cancel") }
      }
    )
  }

  deletingOrder?.let { order ->
    AlertDialog(
      onDismissRequest = { deletingOrder = null },
      title = { Text("Delete Order?", fontWeight = FontWeight.Bold) },
      text = { Text("Permanently delete ${order.orderNumber}?") },
      confirmButton = {
        Button(
          onClick = {
            onDeleteOrder(order.id)
            deletingOrder = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025))
        ) { Text("Delete", color = Color.White) }
      },
      dismissButton = {
        TextButton(onClick = { deletingOrder = null }) { Text("Cancel") }
      }
    )
  }

  editingClaim?.let { claim ->
    var statusInput by remember(claim.id) { mutableStateOf(claim.status) }
    var nameInput by remember(claim.id) { mutableStateOf(claim.recipientName) }
    var phoneInput by remember(claim.id) { mutableStateOf(claim.phone) }
    var addressInput by remember(claim.id) { mutableStateOf(claim.address) }
    var noteInput by remember(claim.id) { mutableStateOf(claim.note) }

    AlertDialog(
      onDismissRequest = { editingClaim = null },
      title = { Text("Edit Gift Claim", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("pending", "approved", "delivered").forEach { st ->
              FilterChip(
                selected = statusInput == st,
                onClick = { statusInput = st },
                label = { Text(st) }
              )
            }
          }
          OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_PERSON_NAME_LEN) },
            label = { Text("Recipient Name") },
            singleLine = true
          )
          OutlinedTextField(
            value = phoneInput,
            onValueChange = { phoneInput = InputSanitizer.filterPhoneInput(it, InputSanitizer.MAX_PHONE_LEN) },
            label = { Text("Phone") },
            singleLine = true
          )
          OutlinedTextField(
            value = addressInput,
            onValueChange = { addressInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_ADDRESS_LEN, allowNewlines = true) },
            label = { Text("Delivery Address") },
            maxLines = 3
          )
          OutlinedTextField(
            value = noteInput,
            onValueChange = { noteInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_GIFT_NOTE_LEN) },
            label = { Text("Note") },
            singleLine = true
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onUpdateGiftClaim(
              claim.id,
              statusInput,
              InputSanitizer.sanitizePersonName(nameInput),
              InputSanitizer.sanitizePhone(phoneInput),
              InputSanitizer.sanitizeAddress(addressInput),
              InputSanitizer.sanitizeForSubmit(noteInput, InputSanitizer.MAX_GIFT_NOTE_LEN)
            )
            editingClaim = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen)
        ) { Text("Save") }
      },
      dismissButton = {
        TextButton(onClick = { editingClaim = null }) { Text("Cancel") }
      }
    )
  }

  deletingClaim?.let { claim ->
    AlertDialog(
      onDismissRequest = { deletingClaim = null },
      title = { Text("Delete Gift Claim?", fontWeight = FontWeight.Bold) },
      text = { Text("Remove gift address submission from ${claim.recipientName}?") },
      confirmButton = {
        Button(
          onClick = {
            onDeleteGiftClaim(claim.id)
            deletingClaim = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025))
        ) { Text("Delete", color = Color.White) }
      },
      dismissButton = {
        TextButton(onClick = { deletingClaim = null }) { Text("Cancel") }
      }
    )
  }

  editingMarket?.let { mp ->
    var itemInput by remember(mp.id) { mutableStateOf(mp.itemName) }
    var marketInput by remember(mp.id) { mutableStateOf(mp.marketName) }
    var priceInput by remember(mp.id) { mutableStateOf(String.format(Locale.US, "%.2f", mp.boughtPrice)) }

    AlertDialog(
      onDismissRequest = { editingMarket = null },
      title = { Text("Edit Market Purchase", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = itemInput,
            onValueChange = { itemInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_ITEM_NAME_LEN) },
            label = { Text("Item Name") },
            singleLine = true
          )
          OutlinedTextField(
            value = marketInput,
            onValueChange = { marketInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_MARKET_NAME_LEN) },
            label = { Text("Market / Bazar Name") },
            singleLine = true
          )
          OutlinedTextField(
            value = priceInput,
            onValueChange = { priceInput = InputSanitizer.filterDecimalInput(it) },
            label = { Text("Price (৳)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onUpdateMarketPurchase(
              mp.id,
              InputSanitizer.sanitizeItemName(itemInput),
              InputSanitizer.sanitizeMarketName(marketInput),
              InputSanitizer.parseValidPrice(priceInput, InputSanitizer.MAX_ITEM_PRICE, allowBlankAsZero = true)
                ?: mp.boughtPrice
            )
            editingMarket = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen)
        ) { Text("Save") }
      },
      dismissButton = {
        TextButton(onClick = { editingMarket = null }) { Text("Cancel") }
      }
    )
  }

  deletingMarket?.let { mp ->
    AlertDialog(
      onDismissRequest = { deletingMarket = null },
      title = { Text("Delete Market Record?", fontWeight = FontWeight.Bold) },
      text = { Text("Delete market purchase '${mp.itemName}' from '${mp.marketName}'?") },
      confirmButton = {
        Button(
          onClick = {
            onDeleteMarketPurchase(mp.id)
            deletingMarket = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025))
        ) { Text("Delete", color = Color.White) }
      },
      dismissButton = {
        TextButton(onClick = { deletingMarket = null }) { Text("Cancel") }
      }
    )
  }

  notifyTargetUser?.let { target ->
    var titleInput by remember(target.uid) { mutableStateOf("Paircart Notice") }
    var msgInput by remember(target.uid) { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { notifyTargetUser = null },
      title = { Text("Notify ${target.displayName.ifBlank { target.numericId }}", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = titleInput,
            onValueChange = { titleInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_ADMIN_TITLE_LEN) },
            label = { Text("Title") },
            singleLine = true
          )
          OutlinedTextField(
            value = msgInput,
            onValueChange = { msgInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_ADMIN_MESSAGE_LEN, allowNewlines = true) },
            label = { Text("Message *") },
            maxLines = 4
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val cleanMsg = InputSanitizer.sanitizeForSubmit(msgInput, InputSanitizer.MAX_ADMIN_MESSAGE_LEN, allowNewlines = true)
            if (cleanMsg.isNotBlank()) {
              onSendNotification(
                target.uid,
                InputSanitizer.sanitizeForSubmit(titleInput, InputSanitizer.MAX_ADMIN_TITLE_LEN),
                cleanMsg
              )
              notifyTargetUser = null
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen)
        ) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Send")
        }
      },
      dismissButton = {
        TextButton(onClick = { notifyTargetUser = null }) { Text("Cancel") }
      }
    )
  }

  if (showBroadcastDialog) {
    var titleInput by remember { mutableStateOf("Paircart Notice") }
    var msgInput by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { showBroadcastDialog = false },
      title = { Text("Add New Notice", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "This notice will appear on every user's home banner slider and notifications page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          OutlinedTextField(
            value = titleInput,
            onValueChange = { titleInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_ADMIN_TITLE_LEN) },
            label = { Text("Notice Title") },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("admin_broadcast_title_input")
          )
          OutlinedTextField(
            value = msgInput,
            onValueChange = { msgInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_ADMIN_MESSAGE_LEN, allowNewlines = true) },
            label = { Text("Notice Message *") },
            maxLines = 4,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("admin_broadcast_message_input")
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val cleanMsg = InputSanitizer.sanitizeForSubmit(msgInput, InputSanitizer.MAX_ADMIN_MESSAGE_LEN, allowNewlines = true)
            if (cleanMsg.isNotBlank()) {
              onBroadcastNotification(
                InputSanitizer.sanitizeForSubmit(titleInput, InputSanitizer.MAX_ADMIN_TITLE_LEN),
                cleanMsg
              )
              showBroadcastDialog = false
            }
          },
          enabled = msgInput.isNotBlank(),
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen),
          modifier = Modifier.testTag("admin_broadcast_confirm_button")
        ) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Publish Notice", fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showBroadcastDialog = false }) { Text("Cancel") }
      }
    )
  }

  editingNotification?.let { notif ->
    var titleInput by remember(notif.id) { mutableStateOf(notif.title) }
    var msgInput by remember(notif.id) { mutableStateOf(notif.message) }

    AlertDialog(
      onDismissRequest = { editingNotification = null },
      title = { Text("Update Notice", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "Updating this notice will immediately refresh the banner for all users.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          OutlinedTextField(
            value = titleInput,
            onValueChange = { titleInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_ADMIN_TITLE_LEN) },
            label = { Text("Notice Title") },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("admin_edit_announcement_title_input")
          )
          OutlinedTextField(
            value = msgInput,
            onValueChange = { msgInput = InputSanitizer.sanitizeOnType(it, InputSanitizer.MAX_ADMIN_MESSAGE_LEN, allowNewlines = true) },
            label = { Text("Notice Message *") },
            maxLines = 4,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("admin_edit_announcement_message_input")
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val cleanMsg = InputSanitizer.sanitizeForSubmit(msgInput, InputSanitizer.MAX_ADMIN_MESSAGE_LEN, allowNewlines = true)
            if (cleanMsg.isNotBlank()) {
              onUpdateNotification(
                notif.id,
                InputSanitizer.sanitizeForSubmit(titleInput, InputSanitizer.MAX_ADMIN_TITLE_LEN),
                cleanMsg
              )
              editingNotification = null
            }
          },
          enabled = msgInput.isNotBlank(),
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen),
          modifier = Modifier.testTag("admin_update_announcement_confirm_button")
        ) {
          Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Update Notice", fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { editingNotification = null }) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
private fun AdminStatPill(
  label: String,
  value: String,
  highlight: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    color = if (highlight) Color(0xFFFFB300) else Color.White.copy(alpha = 0.14f),
    modifier = modifier
  ) {
    Column(
      modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.ExtraBold,
        color = if (highlight) Color(0xFF3E2723) else Color.White
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = if (highlight) Color(0xFF3E2723) else Color.White.copy(alpha = 0.85f)
      )
    }
  }
}

@Composable
private fun AdminLoadingCard(isOnline: Boolean) {
  FacebookFeedSkeletonList(
    count = 3,
    modifier = Modifier.testTag("admin_loading_card")
  )
}

@Composable
private fun AdminEmptyCard(message: String) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(24.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun AdminGiftClaimCard(
  claim: GiftClaim,
  onCopyText: (label: String, value: String) -> Unit,
  onSetStatus: (String) -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit
) {
  val statusColor = when (claim.status) {
    "delivered" -> Color(0xFF137333)
    "approved" -> Color(0xFF0284C7)
    else -> Color(0xFFE37400)
  }
  val dateStr = remember(claim.createdAt) {
    claim.createdAt?.toDate()?.let { SimpleDateFormat("dd MMM, yyyy hh:mm a", Locale.US).format(it) } ?: "Just now"
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.5.dp, statusColor.copy(alpha = 0.45f)),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_gift_claim_${claim.id}")
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = claim.recipientName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = "${claim.userEmail.ifBlank { claim.userName }} • ID: ${claim.userNumericId} • ${claim.coins} Coins",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = statusColor.copy(alpha = 0.14f)
        ) {
          Text(
            text = claim.status.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = statusColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }

      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp), tint = OrderGreen)
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "User ID: ${claim.userNumericId}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1B5E20)
              )
            }
            TextButton(
              onClick = { onCopyText("User ID", claim.userNumericId) },
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
              modifier = Modifier.height(28.dp)
            ) {
              Text("Copy ID", fontSize = 11.sp, color = OrderGreen)
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(14.dp), tint = OrderGreen)
              Spacer(modifier = Modifier.width(6.dp))
              Text(claim.phone, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            TextButton(
              onClick = { onCopyText("Phone", claim.phone) },
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
              modifier = Modifier.height(28.dp)
            ) {
              Text("Copy", fontSize = 11.sp, color = OrderGreen)
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
          ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) {
              Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(15.dp), tint = OrderGreen)
              Spacer(modifier = Modifier.width(6.dp))
              Text(claim.address, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
              onClick = { onCopyText("Address", claim.address) },
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
              modifier = Modifier.height(28.dp)
            ) {
              Text("Copy", fontSize = 11.sp, color = OrderGreen)
            }
          }

          if (claim.note.isNotBlank()) {
            Text("Note: ${claim.note}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Text("Submitted: $dateStr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        if (claim.status != "approved") {
          OutlinedButton(
            onClick = { onSetStatus("approved") },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
          ) {
            Text("Approve", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
          }
        }
        if (claim.status != "delivered") {
          Button(
            onClick = { onSetStatus("delivered") },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OrderGreen),
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
          ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Mark Delivered", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
          }
        }
        OutlinedButton(
          onClick = onEdit,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.height(34.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
          Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Edit", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(
          onClick = onDelete,
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, Color(0xFFD93025)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD93025)),
          modifier = Modifier.height(34.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
          Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
        }
      }
    }
  }
}

@Composable
private fun AdminUserCard(
  user: UserProfile,
  onEdit: () -> Unit,
  onNotify: () -> Unit,
  onDelete: () -> Unit
) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(
      1.dp,
      if (user.coins >= AdminConfig.GIFT_COIN_THRESHOLD) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    ),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = user.displayName.ifBlank { "Unnamed User" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = user.email,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (user.coins >= AdminConfig.GIFT_COIN_THRESHOLD) Color(0xFFFFD54F) else OrderGreen.copy(alpha = 0.14f)
        ) {
          Text(
            text = "${user.coins} Coins",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            color = if (user.coins >= AdminConfig.GIFT_COIN_THRESHOLD) Color(0xFF3E2723) else OrderGreen,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "ID: ${user.numericId}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (user.connectedPartnerId.isNotBlank()) {
          Text(
            text = "• Partner: ${user.connectedPartnerName.ifBlank { user.connectedPartnerId }} (${user.connectedPartnerId})",
            style = MaterialTheme.typography.labelSmall,
            color = OrderGreen,
            fontWeight = FontWeight.SemiBold
          )
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onEdit,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.height(34.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
          Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Edit / Coins", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
          onClick = onNotify,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.height(34.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
          Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Notify", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(
          onClick = onDelete,
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, Color(0xFFD93025)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD93025)),
          modifier = Modifier.height(34.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) {
          Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
        }
      }
    }
  }
}

@Composable
private fun AdminOrderCard(
  order: Order,
  onEdit: () -> Unit,
  onDelete: () -> Unit
) {
  val statusColor = when (order.status) {
    "completed" -> Color(0xFF137333)
    "accepted" -> Color(0xFF0284C7)
    "rejected", "cancelled" -> Color(0xFFD93025)
    "incomplete" -> Color(0xFFB45309)
    "expired" -> Color(0xFF64748B)
    else -> Color(0xFFE37400)
  }

  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = order.orderNumber.ifBlank { order.id.take(8) },
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.ExtraBold,
          fontFamily = FontFamily.Monospace
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "৳${String.format(Locale.US, "%.2f", order.totalCost)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = OrderGreen
          )
          Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.14f)) {
            Text(
              text = order.status.uppercase(),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = statusColor,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
          }
        }
      }

      Text(
        text = "From: ${order.userName.ifBlank { "User" }}  →  Partner: ${order.partnerName.ifBlank { "Partner" }}",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold
      )
      Text(
        text = "${order.formattedDate} • ${order.formattedTime}" +
          if (order.purchaseMarketName.isNotBlank()) " • ${order.purchaseMarketName}" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      val itemPreview = order.typedItems.joinToString(", ") {
        val tag = when {
          it.isBought -> " ✓৳${it.boughtPrice}"
          it.isUnavailable -> " ✕Unavail"
          else -> ""
        }
        "${it.name} (${it.quantity})$tag"
      }
      if (itemPreview.isNotBlank()) {
        Text(
          text = "Items (${order.typedItems.size}): $itemPreview",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onEdit,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.height(34.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
        ) {
          Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Edit Status / Total", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
          onClick = onDelete,
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, Color(0xFFD93025)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD93025)),
          modifier = Modifier.height(34.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
        ) {
          Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Delete", style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}
