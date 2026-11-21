package com.example.ui.order

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.InboxNotificationItem
import com.example.ui.components.FacebookNotificationsSkeletonList
import com.example.ui.theme.OrderGreen
import com.example.util.LocalAppStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
  notifications: List<InboxNotificationItem>,
  isOnline: Boolean = true,
  onBack: () -> Unit,
  onMarkRead: (InboxNotificationItem) -> Unit,
  onMarkAllRead: () -> Unit,
  onDeleteNotification: (InboxNotificationItem) -> Unit,
  onClearAll: () -> Unit
) {
  BackHandler { onBack() }

  val strings = LocalAppStrings.current
  var selectedFilter by remember { mutableIntStateOf(0) } // 0: All, 1: Unread, 2: Admin, 3: Orders

  val filteredList = remember(notifications, selectedFilter) {
    when (selectedFilter) {
      1 -> notifications.filter { !it.isRead }
      2 -> notifications.filter { it.isBroadcast || it.type == "admin_broadcast" || it.type == "admin_message" }
      3 -> notifications.filter {
        it.type in setOf(
          "new_order",
          "order_accepted",
          "order_completed",
          "order_rejected",
          "order_expired",
          "order_incomplete",
          "item_unavailable",
          "order_pending_reminder",
          "shopping_pre_deadline",
          "order_overdue",
          "shopping_reminder"
        )
      }
      else -> notifications
    }
  }

  val unreadCount = remember(notifications) { notifications.count { !it.isRead } }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = strings.notificationsTitle,
              fontWeight = FontWeight.ExtraBold,
              style = MaterialTheme.typography.titleLarge
            )
            if (unreadCount > 0) {
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = OrderGreen.copy(alpha = 0.14f)
              ) {
                Text(
                  text = "$unreadCount",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.ExtraBold,
                  color = OrderGreen,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
              }
            }
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("notifications_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back"
            )
          }
        },
        actions = {
          if (unreadCount > 0) {
            TextButton(
              onClick = onMarkAllRead,
              modifier = Modifier.testTag("notifications_mark_all_read")
            ) {
              Text(strings.markAllRead, color = OrderGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
          }
          if (notifications.isNotEmpty()) {
            IconButton(
              onClick = onClearAll,
              modifier = Modifier.testTag("notifications_clear_all")
            ) {
              Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = strings.clearAll,
                tint = Color(0xFFD93025)
              )
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface
        )
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .testTag("notifications_page")
    ) {
      // Filter Chips
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        val filters = listOf(
          0 to "All (${notifications.size})",
          1 to "Unread ($unreadCount)",
          2 to "Admin",
          3 to "Orders"
        )
        filters.forEach { (idx, label) ->
          FilterChip(
            selected = selectedFilter == idx,
            onClick = { selectedFilter = idx },
            label = { Text(label, fontWeight = FontWeight.Bold) },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = OrderGreen,
              selectedLabelColor = Color.White
            )
          )
        }
      }

      if (!isOnline) {
        FacebookNotificationsSkeletonList(count = 5)
      } else if (filteredList.isEmpty()) {
        Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(24.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(72.dp)
                  .clip(CircleShape)
                  .background(Color(0xFFF57C00).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Notifications,
                  contentDescription = null,
                  tint = Color(0xFFF57C00),
                  modifier = Modifier.size(36.dp)
                )
              }
              Text(
                text = strings.noNotificationsMsg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
            }
          }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 32.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          items(filteredList, key = { it.id }) { item ->
            NotificationInboxCard(
              item = item,
              adminBadgeLabel = strings.adminNoticeBadge,
              onClick = { onMarkRead(item) },
              onDelete = { onDeleteNotification(item) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NotificationInboxCard(
  item: InboxNotificationItem,
  adminBadgeLabel: String,
  onClick: () -> Unit,
  onDelete: () -> Unit
) {
  val isAdminNotice = item.isBroadcast || item.type == "admin_broadcast" || item.type == "admin_message"
  val dateStr = remember(item.createdAtMs) {
    if (item.createdAtMs > 0L) {
      SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(item.createdAtMs))
    } else ""
  }

  val borderColor = when {
    isAdminNotice && !item.isRead -> Color(0xFFFFB300)
    !item.isRead -> OrderGreen.copy(alpha = 0.65f)
    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
  }

  val containerColor = when {
    isAdminNotice -> Color(0xFFFFFBF0)
    !item.isRead -> OrderGreen.copy(alpha = 0.06f)
    else -> MaterialTheme.colorScheme.surface
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
    border = BorderStroke(if (!item.isRead) 1.5.dp else 1.dp, borderColor),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .testTag("notification_card_${item.id}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.Top
    ) {
      val accentColor = when {
        isAdminNotice -> Color(0xFFEA580C)
        item.type == "order_completed" || item.type == "partner_connected" || item.type == "order_accepted" -> OrderGreen
        item.type == "order_rejected" || item.type == "order_overdue" -> Color(0xFFD93025)
        item.type == "order_incomplete" || item.type == "item_unavailable" -> Color(0xFFE65100)
        item.type == "order_expired" -> Color(0xFF6B7280)
        else -> Color(0xFFF57C00)
      }
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(accentColor.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = when {
            isAdminNotice -> Icons.Default.Notifications
            item.type == "order_completed" -> Icons.Default.CheckCircle
            item.type == "order_accepted" -> Icons.Default.Check
            item.type == "order_rejected" -> Icons.Default.Close
            item.type == "order_overdue" -> Icons.Default.Warning
            item.type == "order_expired" -> Icons.Default.DateRange
            item.type == "order_pending_reminder" || item.type == "shopping_pre_deadline" -> Icons.Default.Notifications
            item.type == "order_incomplete" || item.type == "item_unavailable" -> Icons.Default.Info
            item.type == "partner_connected" -> Icons.Default.Person
            else -> Icons.Default.Notifications
          },
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.size(20.dp)
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            if (!item.isRead) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(if (isAdminNotice) Color(0xFFFF8F00) else OrderGreen)
              )
              Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
              text = item.title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = if (!item.isRead) FontWeight.ExtraBold else FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
          }

          IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Remove notification",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(16.dp)
            )
          }
        }

        if (isAdminNotice) {
          Spacer(modifier = Modifier.height(2.dp))
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFFFFB300).copy(alpha = 0.2f)
          ) {
            Text(
              text = adminBadgeLabel,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.ExtraBold,
              color = Color(0xFF795548),
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = item.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (dateStr.isNotBlank()) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = dateStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
          )
        }
      }
    }
  }
}
