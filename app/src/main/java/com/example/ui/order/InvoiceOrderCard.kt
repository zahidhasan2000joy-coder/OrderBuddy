package com.example.ui.order

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.example.ui.components.FacebookSlimShimmerBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.Order
import com.example.data.model.OrderItemDetail
import com.example.service.OrderLifecycleManager
import com.example.ui.theme.OrderGreen
import com.example.util.InputSanitizer
import com.example.util.LocalAppStrings
import com.example.util.UrlSecurityHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InvoiceOrderCard(
  order: Order,
  isMyOrder: Boolean,
  nowMs: Long = System.currentTimeMillis(),
  isBusy: Boolean = false,
  onAcceptOrder: (shoppingDurationMinutes: Int) -> Unit,
  onRejectOrder: (reason: String) -> Unit = {},
  onMarkOrderIncomplete: (reason: String) -> Unit = {},
  onMarkItemBought: (itemIndex: Int, price: Double, marketName: String) -> Unit,
  onMarkItemUnavailable: (itemIndex: Int, reason: String, marketNameIfCompleting: String) -> Unit = { _, _, _ -> },
  onDeleteOrder: () -> Unit,
  modifier: Modifier = Modifier
) {
  val strings = LocalAppStrings.current
  var selectedItemForPrice by remember { mutableStateOf<Pair<Int, OrderItemDetail>?>(null) }
  var priceInput by remember { mutableStateOf("") }
  var priceError by remember { mutableStateOf(false) }
  var itemMarketInput by remember { mutableStateOf("") }
  var itemMarketError by remember { mutableStateOf(false) }

  var selectedItemForUnavailable by remember { mutableStateOf<Pair<Int, OrderItemDetail>?>(null) }
  var showAcceptTimeDialog by remember { mutableStateOf(false) }
  var showRejectDialog by remember { mutableStateOf(false) }
  var showIncompleteDialog by remember { mutableStateOf(false) }

  var pendingActionIndices by remember(order.id, order.items) { mutableStateOf(setOf<Int>()) }

  val effectiveStatus = order.effectiveStatus(nowMs)

  val (statusColor, statusLabel) = when (effectiveStatus) {
    "overdue" -> Pair(Color(0xFFD93025), "Overdue")
    "accepted" -> Pair(Color(0xFF0284C7), "Accepted • Shopping")
    "completed" -> Pair(Color(0xFF137333), "Completed & Bought")
    "rejected" -> Pair(Color(0xFFD93025), "Rejected")
    "expired" -> Pair(Color(0xFF64748B), "Expired")
    "incomplete" -> Pair(Color(0xFFB45309), "Incomplete")
    "cancelled" -> Pair(Color(0xFFD93025), "Cancelled")
    else -> {
      if (order.isPendingResponseLate(nowMs)) {
        Pair(Color(0xFFB45309), "Pending (Late)")
      } else {
        Pair(Color(0xFFE37400), "Pending")
      }
    }
  }

  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(
      width = if (effectiveStatus == "overdue") 1.5.dp else 1.dp,
      color = if (effectiveStatus == "overdue") Color(0xFFD93025).copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = modifier
      .fillMaxWidth()
      .testTag("invoice_order_card_${order.id}")
  ) {
    Column(
      modifier = Modifier.fillMaxWidth()
    ) {
      if (isBusy) {
        FacebookSlimShimmerBar(
          modifier = Modifier.testTag("order_card_busy_${order.id}")
        )
      }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(18.dp)
      ) {
      // 1. Invoice Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            painter = painterResource(id = R.drawable.ic_receipt),
            contentDescription = null,
            tint = OrderGreen,
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = order.orderNumber.ifBlank { "INVOICE" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
          )
        }

        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(statusColor.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("order_status_badge_${order.id}")
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = statusLabel,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = statusColor
            )
          }
        }
      }

      // Date & Time line
      Spacer(modifier = Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          painter = painterResource(id = R.drawable.ic_access_time),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = "${order.formattedDate} • ${order.formattedTime}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "• ${if (isMyOrder) "Requested by You" else "From: ${order.userName.ifBlank { "Partner" }}"}",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      // Lifecycle Info / Countdown / Reason Banners
      val createdMs = order.effectiveCreatedAtMs
      when (effectiveStatus) {
        "pending" -> if (createdMs > 0L) {
          val acceptRemainingMs = (createdMs + Order.ACCEPT_WINDOW_MS) - nowMs
          val expireRemainingMs = (createdMs + Order.EXPIRE_WINDOW_MS) - nowMs
          val bannerColor = if (acceptRemainingMs > 0L) Color(0xFFE37400) else Color(0xFFD93025)
          val text = if (acceptRemainingMs > 0L) {
            if (!isMyOrder) {
              "Accept or Reject within ${OrderLifecycleManager.formatRemainingDuration(acceptRemainingMs)} • Expires in ${OrderLifecycleManager.formatRemainingDuration(expireRemainingMs)}"
            } else {
              "Waiting for partner response (${OrderLifecycleManager.formatRemainingDuration(acceptRemainingMs)} window)"
            }
          } else {
            "15m window passed • Auto-expires in ${OrderLifecycleManager.formatRemainingDuration(expireRemainingMs)}"
          }
          Spacer(modifier = Modifier.height(8.dp))
          LifecycleBanner(color = bannerColor, text = text, isAlert = acceptRemainingMs <= 0L)
        }

        "accepted" -> if (order.shoppingDeadlineMs > 0L) {
          val remainingMs = order.shoppingDeadlineMs - nowMs
          val dueStr = remember(order.shoppingDeadlineMs) {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(order.shoppingDeadlineMs))
          }
          Spacer(modifier = Modifier.height(8.dp))
          LifecycleBanner(
            color = Color(0xFF0284C7),
            text = "Shopping due by $dueStr (${OrderLifecycleManager.formatRemainingDuration(remainingMs)} left • ${order.shoppingDurationMinutes}m goal)",
            isAlert = false
          )
        }

        "overdue" -> {
          val overdueByMs = (nowMs - order.shoppingDeadlineMs).coerceAtLeast(60_000L)
          Spacer(modifier = Modifier.height(8.dp))
          LifecycleBanner(
            color = Color(0xFFD93025),
            text = "OVERDUE by ${OrderLifecycleManager.formatRemainingDuration(overdueByMs)} (Scheduled ${order.shoppingDurationMinutes}m passed)",
            isAlert = true
          )
        }

        "rejected" -> {
          Spacer(modifier = Modifier.height(8.dp))
          LifecycleBanner(
            color = Color(0xFFD93025),
            text = "Rejection Reason: ${order.rejectionReason.ifBlank { "No reason specified" }}",
            isAlert = true
          )
        }

        "expired" -> {
          Spacer(modifier = Modifier.height(8.dp))
          LifecycleBanner(
            color = Color(0xFF64748B),
            text = "Expired automatically after 30 mins without response.",
            isAlert = false
          )
        }

        "incomplete" -> {
          Spacer(modifier = Modifier.height(8.dp))
          LifecycleBanner(
            color = Color(0xFFB45309),
            text = "Unable to Complete: ${order.incompleteReason.ifBlank { "Marked incomplete" }}",
            isAlert = true
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
      Spacer(modifier = Modifier.height(12.dp))

      // 2. Invoice Items List
      Text(
        text = "ITEMS ORDERED (${order.typedItems.size}):",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Spacer(modifier = Modifier.height(8.dp))

      order.typedItems.forEachIndexed { index, item ->
        InvoiceItemRow(
          item = item,
          canFulfill = !isMyOrder &&
            !isBusy &&
            (effectiveStatus == "accepted" || effectiveStatus == "overdue") &&
            !pendingActionIndices.contains(index),
          onItemClickToBuy = {
            selectedItemForPrice = Pair(index, item)
            priceInput = if (item.boughtPrice > 0) String.format(Locale.US, "%.2f", item.boughtPrice) else ""
            priceError = false
            itemMarketInput = ""
            itemMarketError = false
          },
          onItemClickUnavailable = {
            selectedItemForUnavailable = Pair(index, item)
          }
        )
        Spacer(modifier = Modifier.height(6.dp))
      }

      Spacer(modifier = Modifier.height(10.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
      Spacer(modifier = Modifier.height(12.dp))

      // 3. Invoice Total & Actions
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.weight(1f)
        ) {
          if (isMyOrder && effectiveStatus == "pending") {
            OutlinedButton(
              onClick = onDeleteOrder,
              enabled = !isBusy,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, Color(0xFFD93025)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD93025)),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
              modifier = Modifier
                .height(34.dp)
                .testTag("delete_order_${order.id}")
            ) {
              Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(3.dp))
              Text("Delete", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
          }

          if (!isMyOrder && effectiveStatus == "pending") {
            Button(
              onClick = { showAcceptTimeDialog = true },
              enabled = !isBusy,
              shape = RoundedCornerShape(8.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = OrderGreen,
                contentColor = Color.White
              ),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              modifier = Modifier
                .height(34.dp)
                .testTag("accept_order_${order.id}")
            ) {
              Icon(Icons.Default.ThumbUp, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Accept", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }

            OutlinedButton(
              onClick = { showRejectDialog = true },
              enabled = !isBusy,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, Color(0xFFD93025)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD93025)),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              modifier = Modifier
                .height(34.dp)
                .testTag("reject_order_${order.id}")
            ) {
              Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Reject", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
          }

          if (!isMyOrder && (effectiveStatus == "accepted" || effectiveStatus == "overdue")) {
            OutlinedButton(
              onClick = { showIncompleteDialog = true },
              enabled = !isBusy,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, Color(0xFFB45309)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB45309)),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              modifier = Modifier
                .height(34.dp)
                .testTag("unable_complete_order_${order.id}")
            ) {
              Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Unable to Complete", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Total Paid Amount on right corner
        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = "Total Amount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
          )
          Text(
            text = "৳${String.format(Locale.US, "%.2f", order.totalCost)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = if (order.totalCost > 0) OrderGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
          )
        }
      }
      }
    }
  }

  // --- DIALOG 1: Accept & Choose Shopping Time ---
  if (showAcceptTimeDialog) {
    val presetMinutes = listOf(15, 30, 45, 60, 90, 120)
    var selectedMinutes by remember { mutableIntStateOf(30) }
    var customMinutesText by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf(false) }

    val effectiveDuration = customMinutesText.trim().toIntOrNull() ?: selectedMinutes

    AlertDialog(
      onDismissRequest = { showAcceptTimeDialog = false },
      shape = RoundedCornerShape(20.dp),
      containerColor = MaterialTheme.colorScheme.surface,
      title = {
        Text(
          text = "Accept & Set Shopping Time",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "Select within how much time you expect to complete this bazar:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            presetMinutes.forEach { mins ->
              val label = when {
                mins == 60 -> "1 hour"
                mins == 90 -> "1.5 hrs"
                mins == 120 -> "2 hours"
                else -> "$mins min"
              }
              val isSelected = customMinutesText.isBlank() && selectedMinutes == mins
              FilterChip(
                selected = isSelected,
                onClick = {
                  selectedMinutes = mins
                  customMinutesText = ""
                  customError = false
                },
                label = { Text(label, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = OrderGreen,
                  selectedLabelColor = Color.White
                )
              )
            }
          }

          OutlinedTextField(
            value = customMinutesText,
            onValueChange = { txt ->
              customMinutesText = InputSanitizer.filterDigitsOnly(txt, 4)
              customError = false
            },
            label = { Text("Or custom time (minutes)") },
            placeholder = { Text("e.g. 40") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = customError,
            supportingText = if (customError) {
              { Text("Enter between 5 and 1440 minutes", color = Color(0xFFD93025)) }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("accept_custom_minutes_input")
          )

          if (effectiveDuration in 5..1440) {
            val estDueStr = SimpleDateFormat("hh:mm a", Locale.getDefault())
              .format(Date(System.currentTimeMillis() + effectiveDuration * 60_000L))
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = OrderGreen.copy(alpha = 0.1f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = "Due by $estDueStr • You'll get a reminder ${if (effectiveDuration > 15) "15 mins before deadline" else "midway"}.",
                style = MaterialTheme.typography.labelMedium,
                color = OrderGreen,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(10.dp)
              )
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (effectiveDuration !in 5..1440) {
              customError = true
            } else {
              showAcceptTimeDialog = false
              onAcceptOrder(effectiveDuration)
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen, contentColor = Color.White),
          modifier = Modifier.testTag("confirm_accept_duration_button")
        ) {
          Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Confirm Accept", fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showAcceptTimeDialog = false }) {
          Text(strings.cancel, color = Color.Gray)
        }
      }
    )
  }

  // --- DIALOG 2: Reject Order with Reason ---
  if (showRejectDialog) {
    val quickReasons = listOf("Busy right now", "Far from market", "Market closed", "Unable to carry")
    var reasonInput by remember { mutableStateOf("") }
    var reasonError by remember { mutableStateOf(false) }

    AlertDialog(
      onDismissRequest = { showRejectDialog = false },
      shape = RoundedCornerShape(20.dp),
      containerColor = MaterialTheme.colorScheme.surface,
      title = {
        Text("Reject Order ${order.orderNumber}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Please select or write why you are rejecting this order:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            quickReasons.forEach { quick ->
              FilterChip(
                selected = reasonInput == quick,
                onClick = {
                  reasonInput = quick
                  reasonError = false
                },
                label = { Text(quick, fontSize = 12.sp) }
              )
            }
          }
          OutlinedTextField(
            value = reasonInput,
            onValueChange = { raw ->
              reasonInput = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_REASON_LEN, allowNewlines = true)
              if (reasonError && reasonInput.isNotBlank()) reasonError = false
            },
            label = { Text("Rejection Reason *") },
            placeholder = { Text("Write reason...") },
            isError = reasonError,
            supportingText = if (reasonError) {
              { Text("Please provide a valid reason", color = Color(0xFFD93025)) }
            } else null,
            maxLines = 3,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("reject_reason_input")
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (!InputSanitizer.isValidReason(reasonInput)) {
              reasonError = true
            } else {
              showRejectDialog = false
              onRejectOrder(InputSanitizer.sanitizeReason(reasonInput))
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025), contentColor = Color.White),
          modifier = Modifier.testTag("confirm_reject_order_button")
        ) {
          Text("Reject Order", fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showRejectDialog = false }) {
          Text(strings.cancel, color = Color.Gray)
        }
      }
    )
  }

  // --- DIALOG 3: Unable to Complete -> Incomplete with Reason ---
  if (showIncompleteDialog) {
    val quickReasons = listOf("Market closed", "Out of time", "Emergency came up", "Items not available")
    var reasonInput by remember { mutableStateOf("") }
    var reasonError by remember { mutableStateOf(false) }

    AlertDialog(
      onDismissRequest = { showIncompleteDialog = false },
      shape = RoundedCornerShape(20.dp),
      containerColor = MaterialTheme.colorScheme.surface,
      title = {
        Text("Unable to Complete Shopping?", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Specify why you cannot finish this order. It will be marked as Incomplete and notify your partner:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            quickReasons.forEach { quick ->
              FilterChip(
                selected = reasonInput == quick,
                onClick = {
                  reasonInput = quick
                  reasonError = false
                },
                label = { Text(quick, fontSize = 12.sp) }
              )
            }
          }
          OutlinedTextField(
            value = reasonInput,
            onValueChange = { raw ->
              reasonInput = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_REASON_LEN, allowNewlines = true)
              if (reasonError && reasonInput.isNotBlank()) reasonError = false
            },
            label = { Text("Reason *") },
            placeholder = { Text("e.g. Heavy rain / store closed") },
            isError = reasonError,
            supportingText = if (reasonError) {
              { Text("Please enter a valid reason", color = Color(0xFFD93025)) }
            } else null,
            maxLines = 3,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("incomplete_reason_input")
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (!InputSanitizer.isValidReason(reasonInput)) {
              reasonError = true
            } else {
              showIncompleteDialog = false
              onMarkOrderIncomplete(InputSanitizer.sanitizeReason(reasonInput))
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB45309), contentColor = Color.White),
          modifier = Modifier.testTag("confirm_incomplete_order_button")
        ) {
          Text("Mark Incomplete", fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showIncompleteDialog = false }) {
          Text(strings.cancel, color = Color.Gray)
        }
      }
    )
  }

  // --- DIALOG 4: Mark Single Item Bought ---
  selectedItemForPrice?.let { (index, item) ->
    val remainingUnresolved = order.typedItems.count { !it.isBought && !it.isUnavailable }
    val willCompleteOrder = !item.isBought && !item.isUnavailable && remainingUnresolved <= 1

    AlertDialog(
      onDismissRequest = { selectedItemForPrice = null },
      shape = RoundedCornerShape(20.dp),
      containerColor = MaterialTheme.colorScheme.surface,
      title = {
        Text(
          text = "Confirm Purchase: ${item.name}",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold
        )
      },
      text = {
        Column {
          if (item.notes.isNotBlank()) {
            Text(
              text = "Note: ${item.notes}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          Spacer(modifier = Modifier.height(14.dp))

          Text(
            text = "How much did you pay in total for this item? (৳):",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
          )

          Spacer(modifier = Modifier.height(8.dp))

          OutlinedTextField(
            value = priceInput,
            onValueChange = { raw ->
              priceInput = InputSanitizer.filterDecimalInput(raw)
              priceError = false
            },
            label = { Text("Price Paid (৳) *") },
            placeholder = { Text("e.g. 150") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = priceError,
            supportingText = if (priceError) {
              { Text("Enter a valid price (0–10,00,000)", color = Color(0xFFD93025)) }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("item_bought_price_input")
          )

          if (willCompleteOrder) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
              text = strings.enterMarketNamePrompt,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
              value = itemMarketInput,
              onValueChange = { raw ->
                itemMarketInput = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_MARKET_NAME_LEN)
                if (itemMarketError && itemMarketInput.isNotBlank()) itemMarketError = false
              },
              label = { Text(strings.marketNameLabel) },
              placeholder = { Text(strings.marketNamePlaceholder) },
              isError = itemMarketError,
              supportingText = if (itemMarketError) {
                { Text(strings.marketNameRequiredError, color = Color(0xFFD93025)) }
              } else null,
              singleLine = true,
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("item_bought_market_input")
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val cleanMarket = InputSanitizer.sanitizeMarketName(itemMarketInput)
            val parsedPrice = InputSanitizer.parseValidPrice(priceInput, InputSanitizer.MAX_ITEM_PRICE, allowBlankAsZero = true)
            if (parsedPrice == null) {
              priceError = true
            } else if (willCompleteOrder && !InputSanitizer.isValidMarketName(cleanMarket)) {
              itemMarketError = true
            } else {
              pendingActionIndices = pendingActionIndices + index
              onMarkItemBought(index, parsedPrice, cleanMarket)
              selectedItemForPrice = null
            }
          },
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen, contentColor = Color.White),
          modifier = Modifier.testTag("confirm_bought_price_button")
        ) {
          Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Mark as Bought", fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { selectedItemForPrice = null }) {
          Text(strings.cancel, color = Color.Gray)
        }
      }
    )
  }

  // --- DIALOG 5: Mark Single Item Unavailable ---
  selectedItemForUnavailable?.let { (index, item) ->
    val quickUnavail = listOf("Not found in market", "Out of stock", "Shop closed", "Too expensive")
    var unavailReason by remember(item.name) { mutableStateOf("Not found in market") }
    var marketIfCompleting by remember(item.name) { mutableStateOf("") }
    var marketError by remember(item.name) { mutableStateOf(false) }

    val remainingUnresolved = order.typedItems.count { !it.isBought && !it.isUnavailable }
    val otherItemsBought = order.typedItems.any { it.isBought }
    val willCompleteWithBoughtItems = remainingUnresolved <= 1 && otherItemsBought

    AlertDialog(
      onDismissRequest = { selectedItemForUnavailable = null },
      shape = RoundedCornerShape(20.dp),
      containerColor = MaterialTheme.colorScheme.surface,
      title = {
        Text("Item Unavailable: ${item.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Only this item will be marked Unavailable. You can still buy the remaining items.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            quickUnavail.forEach { quick ->
              FilterChip(
                selected = unavailReason == quick,
                onClick = { unavailReason = quick },
                label = { Text(quick, fontSize = 12.sp) }
              )
            }
          }
          OutlinedTextField(
            value = unavailReason,
            onValueChange = { raw ->
              unavailReason = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_UNAVAILABLE_REASON_LEN)
            },
            label = { Text("Reason (Optional)") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("item_unavailable_reason_input")
          )

          if (willCompleteWithBoughtItems) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = strings.enterMarketNamePrompt,
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
              value = marketIfCompleting,
              onValueChange = { raw ->
                marketIfCompleting = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_MARKET_NAME_LEN)
                if (marketError && marketIfCompleting.isNotBlank()) marketError = false
              },
              label = { Text(strings.marketNameLabel) },
              placeholder = { Text(strings.marketNamePlaceholder) },
              isError = marketError,
              supportingText = if (marketError) {
                { Text(strings.marketNameRequiredError, color = Color(0xFFD93025)) }
              } else null,
              singleLine = true,
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val cleanMarket = InputSanitizer.sanitizeMarketName(marketIfCompleting)
            if (willCompleteWithBoughtItems && !InputSanitizer.isValidMarketName(cleanMarket)) {
              marketError = true
            } else {
              pendingActionIndices = pendingActionIndices + index
              onMarkItemUnavailable(
                index,
                InputSanitizer.sanitizeReason(unavailReason, InputSanitizer.MAX_UNAVAILABLE_REASON_LEN),
                cleanMarket
              )
              selectedItemForUnavailable = null
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025), contentColor = Color.White),
          modifier = Modifier.testTag("confirm_item_unavailable_button")
        ) {
          Text("Mark Unavailable", fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { selectedItemForUnavailable = null }) {
          Text(strings.cancel, color = Color.Gray)
        }
      }
    )
  }
}

@Composable
private fun LifecycleBanner(
  color: Color,
  text: String,
  isAlert: Boolean
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = color.copy(alpha = if (isAlert) 0.14f else 0.09f),
    border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = if (isAlert) Icons.Default.Warning else Icons.Default.CheckCircle,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(14.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color
      )
    }
  }
}

@Composable
private fun InvoiceItemRow(
  item: OrderItemDetail,
  canFulfill: Boolean,
  onItemClickToBuy: () -> Unit,
  onItemClickUnavailable: () -> Unit,
  modifier: Modifier = Modifier
) {
  val rowBg = when {
    item.isBought -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    item.isUnavailable -> Color(0xFFD93025).copy(alpha = 0.08f)
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(rowBg)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
      if (item.imageUrl.isNotBlank()) {
        AsyncImage(
          model = item.imageUrl,
          contentDescription = item.name,
          modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable {
              if (item.productUrl.isNotBlank()) {
                UrlSecurityHelper.openWebUrlSafely(context, item.productUrl)
              }
            },
          contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(10.dp))
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        // Left: Item Name and Quantity
        Column(modifier = Modifier.weight(1.1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.isBought) {
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Bought",
                tint = OrderGreen,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
            } else if (item.isUnavailable) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Unavailable",
                tint = Color(0xFFD93025),
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
              text = "${item.name} (x${item.quantity})",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              textDecoration = if (item.isUnavailable) TextDecoration.LineThrough else TextDecoration.None,
              color = if (item.isUnavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
          }

          if (item.isUnavailable && item.unavailableReason.isNotBlank()) {
            Text(
              text = item.unavailableReason,
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFFD93025)
            )
          }

          if (item.productUrl.isNotBlank()) {
            val strings = LocalAppStrings.current
            val domain = remember(item.productUrl) { UrlSecurityHelper.extractDomain(item.productUrl) }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable {
                  UrlSecurityHelper.openWebUrlSafely(context, item.productUrl)
                }
                .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
              Icon(
                painter = painterResource(id = R.drawable.ic_receipt),
                contentDescription = null,
                tint = OrderGreen,
                modifier = Modifier.size(12.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = if (domain.isNotBlank()) "🔗 $domain" else strings.viewProductLink,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = OrderGreen
              )
            }
          }
        }

        // Center / Middle: Brand / Size / Notes
        Box(
          modifier = Modifier
            .weight(0.85f)
            .padding(horizontal = 6.dp),
          contentAlignment = Alignment.Center
        ) {
          if (item.notes.isNotBlank()) {
            Text(
              text = item.notes,
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
              maxLines = 2
            )
          }
        }

        // Right: Purchase Status / Price / Buy & Unavailable
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.End,
          modifier = Modifier.weight(1.15f)
        ) {
          when {
            item.isBought -> {
              Text(
                text = "৳${String.format(Locale.US, "%.2f", item.boughtPrice)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.ExtraBold,
                color = OrderGreen
              )
            }
            item.isUnavailable -> {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFD93025).copy(alpha = 0.14f)
              ) {
                Text(
                  text = "Unavailable",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFFD93025),
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
              }
            }
            canFulfill -> {
              Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                  onClick = onItemClickUnavailable,
                  shape = RoundedCornerShape(8.dp),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD93025)),
                  border = BorderStroke(1.dp, Color(0xFFD93025).copy(alpha = 0.7f)),
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                  modifier = Modifier.height(32.dp)
                ) {
                  Text("N/A", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                  onClick = onItemClickToBuy,
                  shape = RoundedCornerShape(8.dp),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = OrderGreen),
                  border = BorderStroke(1.dp, OrderGreen),
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                  modifier = Modifier.height(32.dp)
                ) {
                  Text("Buy", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
              }
            }
            else -> {
              Text(
                text = "Not bought",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }
  }
}
