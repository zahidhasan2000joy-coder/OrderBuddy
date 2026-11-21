package com.example.ui.order

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import com.example.util.LocalAppStrings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import com.example.ui.components.FacebookAppEntrySkeleton
import com.example.ui.components.FacebookFeedOrderSkeletonCard
import com.example.ui.components.FacebookFeedSkeletonList
import com.example.ui.components.FacebookNoticeSkeletonCard
import com.example.ui.components.FacebookPartnerConnectionSkeletonCard
import com.example.ui.components.FullScreen4kPhotoDialog
import com.example.ui.components.rememberEnhanced4kImageBitmap
import com.example.util.ProfileImageHelper
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.util.NotificationHelper
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.Order
import com.example.ui.theme.OrderGreen
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainOrderScreen(
  viewModel: OrderViewModel,
  onSignOut: () -> Unit,
  onForcedClosedSignOut: () -> Unit = onSignOut,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val strings = LocalAppStrings.current
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }

  var isNewOrderScreenOpen by remember { mutableStateOf(false) }
  var isNotificationsScreenOpen by remember { mutableStateOf(false) }
  var showSignOutDialog by remember { mutableStateOf(false) }
  var showReminderDialog by remember { mutableStateOf(false) }
  var inputPartnerNumericId by remember { mutableStateOf("") }
  var fabOffsetX by remember { mutableFloatStateOf(0f) }
  var fabOffsetY by remember { mutableFloatStateOf(0f) }

  val currentUser = Firebase.auth.currentUser
  val userId = currentUser?.uid ?: "guest"
  val tabPrefs = remember(userId) { context.getSharedPreferences("orderbuddy_prefs_$userId", Context.MODE_PRIVATE) }
  
  val partnerListState = androidx.compose.foundation.lazy.rememberLazyListState()
  val myListState = androidx.compose.foundation.lazy.rememberLazyListState()
  val isScrolledUp by remember {
    androidx.compose.runtime.derivedStateOf {
      when (uiState.selectedTab) {
        0 -> partnerListState.firstVisibleItemIndex > 0 || partnerListState.firstVisibleItemScrollOffset > 30
        1 -> myListState.firstVisibleItemIndex > 0 || myListState.firstVisibleItemScrollOffset > 30
        else -> false
      }
    }
  }

  var seenPartnerOrderCount by remember(userId) {
    mutableIntStateOf(tabPrefs.getInt("seen_partner_orders_count", -1))
  }

  // Sync partner orders count so previous orders don't show as unread on login
  LaunchedEffect(uiState.partnerOrders.size, uiState.selectedTab) {
      if (seenPartnerOrderCount == -1 || uiState.selectedTab == 0) {
          seenPartnerOrderCount = uiState.partnerOrders.size
          tabPrefs.edit().putInt("seen_partner_orders_count", uiState.partnerOrders.size).apply()
      }
  }

  val userDisplayName = currentUser?.displayName ?: currentUser?.email?.substringBefore("@") ?: "You"
  var currentTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }

  LaunchedEffect(Unit) {
    while (true) {
      delay(15_000L)
      currentTimeMs = System.currentTimeMillis()
      viewModel.evaluateLifecycleTimersNow()
    }
  }

  val activeAcceptedOrders = uiState.partnerOrders.filter { it.status == "accepted" }
  val pendingItemsCount = activeAcceptedOrders.sumOf { o -> o.typedItems.count { !it.isBought && !it.isUnavailable } }
  val itemsSummary = activeAcceptedOrders
    .flatMap { o -> o.typedItems.filter { !it.isBought && !it.isUnavailable }.map { it.name } }
    .take(3)
    .joinToString(", ")

  LaunchedEffect(uiState.hasLoadedPartnerOrders, pendingItemsCount) {
    if (uiState.hasLoadedPartnerOrders && pendingItemsCount <= 0 && NotificationHelper.isReminderActive(context)) {
      NotificationHelper.cancelShoppingAlarm(context)
    }
  }

  LaunchedEffect(uiState.errorMessage) {
    uiState.errorMessage?.let { error ->
      snackbarHostState.showSnackbar(error)
      viewModel.clearError()
    }
  }

  LaunchedEffect(uiState.appEnabled, uiState.isAdmin) {
    if (!uiState.appEnabled && !uiState.isAdmin) {
      isNewOrderScreenOpen = false
      isNotificationsScreenOpen = false
      showSignOutDialog = false
      onForcedClosedSignOut()
    }
  }

  val barColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)

  Box(modifier = Modifier.fillMaxSize()) {
  if (isNotificationsScreenOpen) {
    NotificationsScreen(
      notifications = uiState.inboxNotifications,
      isOnline = uiState.isOnline,
      onBack = { isNotificationsScreenOpen = false },
      onMarkRead = { viewModel.markNotificationRead(it) },
      onMarkAllRead = { viewModel.markAllNotificationsRead() },
      onDeleteNotification = { viewModel.deleteInboxNotification(it) },
      onClearAll = { viewModel.clearAllInboxNotifications() }
    )
  } else if (isNewOrderScreenOpen) {
    BackHandler {
      viewModel.cancelPendingOrderSend()
      isNewOrderScreenOpen = false
    }
    Scaffold(
      snackbarHost = { SnackbarHost(snackbarHostState) },
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = strings.newOrder,
              fontWeight = FontWeight.Bold,
              style = MaterialTheme.typography.titleLarge
            )
          },
          navigationIcon = {
            IconButton(
              onClick = {
                viewModel.cancelPendingOrderSend()
                isNewOrderScreenOpen = false
              },
              modifier = Modifier.testTag("back_from_new_order_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
              )
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = barColor
          )
        )
      }
    ) { innerPadding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
      ) {
        NewOrderTabContent(
            uiState = uiState,
            onSendOrder = { items, onSuccess ->
              viewModel.sendInvoiceOrder(items) {
                onSuccess()
                isNewOrderScreenOpen = false
              }
            },
            onCancelPendingSend = {
              viewModel.cancelPendingOrderSend()
            }
        )
      }
    }
  } else {
    Scaffold(
      snackbarHost = { SnackbarHost(snackbarHostState) },
      topBar = {
        CenterAlignedTopAppBar(
          title = {
            Image(
              painter = painterResource(id = R.drawable.app_wordmark_logo),
              contentDescription = "Paircart Logo",
              modifier = Modifier
                .height(22.dp)
                .widthIn(max = 105.dp),
              contentScale = ContentScale.Fit
            )
          },
          colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent
          ),
          actions = {
            IconButton(
              onClick = { isNotificationsScreenOpen = true },
              modifier = Modifier
                .padding(end = 4.dp)
                .testTag("open_notifications_button")
            ) {
              if (uiState.unreadNotificationCount > 0) {
                BadgedBox(
                  badge = {
                    Badge(containerColor = Color(0xFFD93025), contentColor = Color.White) {
                      Text(
                        text = if (uiState.unreadNotificationCount > 99) "99+" else uiState.unreadNotificationCount.toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                      )
                    }
                  }
                ) {
                  Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = strings.notificationsTitle,
                    tint = Color(0xFFEA580C)
                  )
                }
              } else {
                Icon(
                  imageVector = Icons.Default.Notifications,
                  contentDescription = strings.notificationsTitle,
                  tint = Color(0xFFF57C00)
                )
              }
            }
          }
        )
      },
      floatingActionButton = {},
      bottomBar = {
        Surface(
          shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
          shadowElevation = 8.dp,
          color = barColor,
          tonalElevation = 3.dp,
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        ) {
          NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("fixed_bottom_navigation_bar")
          ) {
            val navItems = buildList {
              add(Triple(0, strings.tabPartnerOrders, R.drawable.ic_group))
              add(Triple(1, strings.tabMyOrders, R.drawable.ic_receipt))
              add(Triple(2, strings.tabProfile, R.drawable.ic_receipt))
              if (uiState.isAdmin) {
                add(Triple(3, strings.tabAdmin, R.drawable.ic_receipt))
              }
            }

            navItems.forEach { (index, label, resId) ->
              val isSelected = uiState.selectedTab == index
              val pendingGiftCount = uiState.adminGiftClaims.count { it.status == "pending" }
              val badgeCount = when (index) {
                0 -> if (isSelected) null else {
                  val unseen = if (seenPartnerOrderCount >= 0) uiState.partnerOrders.size - seenPartnerOrderCount else 0
                  if (unseen > 0) unseen else null
                }
                3 -> if (uiState.isAdmin && pendingGiftCount > 0) pendingGiftCount else null
                else -> null
              }

              NavigationBarItem(
                selected = isSelected,
                onClick = {
                  if (index == 0) {
                    seenPartnerOrderCount = uiState.partnerOrders.size
                    tabPrefs.edit().putInt("seen_partner_orders_count", uiState.partnerOrders.size).apply()
                  }
                  viewModel.setSelectedTab(index)
                },
                icon = {
                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(16.dp))
                      .background(if (isSelected) OrderGreen.copy(alpha = 0.15f) else Color.Transparent)
                      .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                  ) {
                    if (index == 2) {
                      Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = label,
                        tint = if (isSelected) OrderGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                      )
                    } else if (index == 3) {
                      if (badgeCount != null && badgeCount > 0) {
                        BadgedBox(
                          badge = {
                            Badge(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF3E2723)) {
                              Text(badgeCount.toString(), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                          }
                        ) {
                          Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = label,
                            tint = if (isSelected) OrderGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                          )
                        }
                      } else {
                        Icon(
                          imageVector = Icons.Default.Settings,
                          contentDescription = label,
                          tint = if (isSelected) OrderGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                          modifier = Modifier.size(20.dp)
                        )
                      }
                    } else if (badgeCount != null && badgeCount > 0) {
                      BadgedBox(
                        badge = {
                          Badge(containerColor = OrderGreen, contentColor = Color.White) {
                            Text(badgeCount.toString(), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                          }
                        }
                      ) {
                        Icon(
                          painter = painterResource(id = resId),
                          contentDescription = label,
                          tint = if (isSelected) OrderGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                          modifier = Modifier.size(20.dp)
                        )
                      }
                    } else {
                      Icon(
                        painter = painterResource(id = resId),
                        contentDescription = label,
                        tint = if (isSelected) OrderGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                      )
                    }
                  }
                },
                label = {
                  Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (isSelected) OrderGreen else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                },
                colors = NavigationBarItemDefaults.colors(
                  indicatorColor = Color.Transparent,
                  selectedIconColor = OrderGreen,
                  selectedTextColor = OrderGreen,
                  unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                  unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                  .clip(RoundedCornerShape(20.dp))
                  .testTag("bottom_tab_$index")
              )
            }
          }
        }
      },
      containerColor = MaterialTheme.colorScheme.background, // Light Gray
      modifier = modifier.fillMaxSize()
    ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      Column(
        modifier = Modifier.fillMaxSize()
      ) {

      // Notification Permission / System Alert Check Banner
      val lifecycleOwner = LocalLifecycleOwner.current
      var notificationsAllowed by remember {
        mutableStateOf(NotificationHelper.areNotificationsAllowed(context))
      }
      val notifPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
      ) { granted ->
        notificationsAllowed = granted && NotificationHelper.areNotificationsAllowed(context)
        if (!notificationsAllowed) {
          NotificationHelper.openAppNotificationSettings(context)
        } else {
          NotificationHelper.createNotificationChannel(context)
        }
      }
      DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
            notificationsAllowed = NotificationHelper.areNotificationsAllowed(context)
            if (notificationsAllowed) {
              NotificationHelper.createNotificationChannel(context)
            }
            viewModel.onSessionResumed()
          }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
      }

      if (!notificationsAllowed) {
        Surface(
          color = Color(0xFFE65100),
          onClick = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
              context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
              notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
              NotificationHelper.openAppNotificationSettings(context)
            }
          },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("enable_notifications_banner")
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Notifications are off — Tap here to enable",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
            }
          }
        }
      }

      // In-App Notification Alert Banner
      AnimatedVisibility(
        visible = uiState.bannerNotification != null,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
      ) {
        Card(
          shape = RoundedCornerShape(0.dp),
          colors = CardDefaults.cardColors(containerColor = OrderGreen),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("partner_notification_banner")
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = uiState.bannerNotification ?: "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
            }

            IconButton(
              onClick = { viewModel.clearBannerNotification() },
              modifier = Modifier.size(28.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
              )
            }
          }
        }
      }

      // Shopping Mode Banner

      if (pendingItemsCount > 0) {
        Card(
          shape = RoundedCornerShape(0.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF137333)), // Darker Green for Shopping Mode
          modifier = Modifier
            .fillMaxWidth()
            .testTag("shopping_mode_banner")
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(
                painter = painterResource(id = R.drawable.ic_receipt),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text(
                  text = strings.shoppingModeTitle,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = Color.White.copy(alpha = 0.85f)
                )
                Text(
                  text = String.format(strings.shoppingModeSubtitle, pendingItemsCount),
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.Bold,
                  color = Color.White
                )
              }
            }

            Button(
              onClick = { showReminderDialog = true },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF137333)
              ),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
              modifier = Modifier
                .height(34.dp)
                .testTag("shopping_mode_reminder_button")
            ) {
              Icon(
                painter = painterResource(id = R.drawable.ic_access_time),
                contentDescription = null,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = strings.reminderBtn,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }

      // Connection UI moved to Profile Tab as requested

      // Tab Content (Switched via Fixed Bottom Navigation Bar)
      when (uiState.selectedTab) {
        0 -> {
          OrdersList(
            orders = uiState.partnerOrders,
            isMyOrders = false,
            uiState = uiState,
            nowMs = currentTimeMs,
            listState = partnerListState,
            emptyMessage = if (!uiState.isPartnerConnected) {
              strings.emptyPartnerNotConnected
            } else {
              strings.emptyPartnerOrders
            },
            onAcceptOrder = { orderId, mins -> viewModel.acceptOrder(orderId, mins) },
            onRejectOrder = { orderId, reason -> viewModel.rejectOrder(orderId, reason) },
            onMarkOrderIncomplete = { orderId, reason -> viewModel.markOrderIncomplete(orderId, reason) },
            onMarkItemBought = { orderId, index, price, marketName ->
              val order = uiState.partnerOrders.firstOrNull { it.id == orderId }
              if (order != null) {
                viewModel.markItemBought(orderId, order.typedItems, index, price, marketName)
              }
            },
            onMarkItemUnavailable = { orderId, index, reason, marketName ->
              val order = uiState.partnerOrders.firstOrNull { it.id == orderId }
              if (order != null) {
                viewModel.markItemUnavailable(orderId, order.typedItems, index, reason, marketName)
              }
            },
            onDeleteOrder = { orderId -> viewModel.deleteOrder(orderId) },
            onConnectPartner = { id -> viewModel.connectPartnerWithNumericId(id) },
            onDisconnectPartner = { viewModel.disconnectPartner() }
          )
        }
        1 -> {
          OrdersList(
            orders = uiState.myOrders,
            isMyOrders = true,
            uiState = uiState,
            nowMs = currentTimeMs,
            listState = myListState,
            emptyMessage = strings.emptyMyOrders,
            onAcceptOrder = { _, _ -> },
            onRejectOrder = { _, _ -> },
            onMarkOrderIncomplete = { _, _ -> },
            onMarkItemBought = { _, _, _, _ -> },
            onMarkItemUnavailable = { _, _, _, _ -> },
            onDeleteOrder = { orderId -> viewModel.deleteOrder(orderId) },
            onConnectPartner = { id -> viewModel.connectPartnerWithNumericId(id) },
            onDisconnectPartner = { viewModel.disconnectPartner() }
          )
        }
        2 -> {
          ProfileView(
            uiState = uiState,
            userDisplayName = userDisplayName,
            onCollectDailyCoin = { viewModel.collectDailyCoin() },
            onSaveExtendedProfile = { photo, birthday, profession, address, onComplete ->
              viewModel.updateExtendedProfile(photo, birthday, profession, address, onComplete)
            },
            onSignOut = { showSignOutDialog = true },
            onSubmitGiftClaim = { recipient, phone, address, note ->
              viewModel.submitGiftClaim(recipient, phone, address, note)
            },
            onOpenAdminPanel = { viewModel.setSelectedTab(3) }
          )
        }
        3 -> {
          if (uiState.isAdmin) {
            AdminDashboardView(
              uiState = uiState,
              onUpdateUser = { id, name, coins, clearPartner ->
                viewModel.adminUpdateUser(id, name, coins, clearPartner)
              },
              onDeleteUser = { id -> viewModel.adminDeleteUser(id) },
              onUpdateOrder = { orderId, status, totalCost, marketName ->
                viewModel.adminUpdateOrder(orderId, status, totalCost, marketName)
              },
              onDeleteOrder = { orderId -> viewModel.deleteOrder(orderId) },
              onUpdateGiftClaim = { claimId, status, recipient, phone, address, note ->
                viewModel.adminUpdateGiftClaim(claimId, status, recipient, phone, address, note)
              },
              onDeleteGiftClaim = { claimId -> viewModel.adminDeleteGiftClaim(claimId) },
              onUpdateMarketPurchase = { id, item, market, price ->
                viewModel.adminUpdateMarketPurchase(id, item, market, price)
              },
              onDeleteMarketPurchase = { id -> viewModel.adminDeleteMarketPurchase(id) },
              onSendNotification = { targetUid, title, msg ->
                viewModel.adminSendNotification(targetUid, title, msg)
              },
              onBroadcastNotification = { title, msg ->
                viewModel.adminBroadcastNotification(title, msg)
              },
              onUpdateNotification = { notifId, title, msg ->
                viewModel.adminUpdateNotification(notifId, title, msg)
              },
              onDeleteNotification = { notifId -> viewModel.adminDeleteNotification(notifId) },
              onToggleCoinFeature = { viewModel.adminToggleCoinFeature(it) },
              onToggleAppEnabled = { viewModel.adminToggleAppEnabled(it) }
            )
          }
        }
      }
    }

      // Freely Draggable Floating Action Button across entire screen inside Box
    if (uiState.selectedTab == 0 || uiState.selectedTab == 1) {
      FloatingActionButton(
        onClick = { isNewOrderScreenOpen = true },
        containerColor = OrderGreen,
        contentColor = Color.White,
        shape = CircleShape,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 16.dp, bottom = 24.dp)
          .offset { IntOffset(fabOffsetX.toInt(), fabOffsetY.toInt()) }
          .pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
              change.consume()
              fabOffsetX = (fabOffsetX + dragAmount.x).coerceIn(-400f, 100f)
              fabOffsetY = (fabOffsetY + dragAmount.y).coerceIn(-600f, 100f)
            }
          }
          .testTag("fab_new_order")
      ) {
        Icon(Icons.Default.Add, contentDescription = strings.newOrder)
      }
    }
  }
}
}
  AnimatedVisibility(
    visible = uiState.isAppEntryLoading,
    enter = fadeIn(),
    exit = fadeOut(animationSpec = tween(durationMillis = 300))
  ) {
    AppLaunchLoadingOverlay(isOnline = uiState.isOnline)
  }
}

  // Sign out confirmation dialog
  if (showSignOutDialog) {
    AlertDialog(
      onDismissRequest = { showSignOutDialog = false },
      shape = RoundedCornerShape(20.dp),
      containerColor = MaterialTheme.colorScheme.surface,
      title = { Text(strings.signOut, fontWeight = FontWeight.Bold) },
      text = { Text(strings.confirmSignOutMsg) },
      confirmButton = {
        Button(
          onClick = {
            showSignOutDialog = false
            onSignOut()
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025)),
          modifier = Modifier.testTag("confirm_sign_out_button")
        ) {
          Text(strings.signOut, color = Color.White)
        }
      },
      dismissButton = {
        TextButton(onClick = { showSignOutDialog = false }) {
          Text(strings.cancel, color = Color.Gray)
        }
      }
    )
  }

  // Timed Shopping Reminder Dialog (Repeats at chosen interval until all items are bought)
  if (showReminderDialog) {
    val activeReminderMins = NotificationHelper.getActiveReminderMinutes(context)
    AlertDialog(
      onDismissRequest = { showReminderDialog = false },
      shape = RoundedCornerShape(20.dp),
      containerColor = MaterialTheme.colorScheme.surface,
      title = {
        Text(strings.marketReminderTitle, fontWeight = FontWeight.Bold)
      },
      text = {
        Column {
          Text(
            text = strings.marketReminderMsg,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          if (activeReminderMins > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
              shape = RoundedCornerShape(10.dp),
              colors = CardDefaults.cardColors(containerColor = OrderGreen.copy(alpha = 0.12f)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = String.format(strings.activeReminderFmt, activeReminderMins),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = OrderGreen,
                modifier = Modifier.padding(10.dp)
              )
            }
          }
          Spacer(modifier = Modifier.height(14.dp))
          Text(
            text = strings.chooseTime,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(8.dp))

          val options = listOf(
            Pair(15, strings.in15Mins),
            Pair(30, strings.in30Mins),
            Pair(60, strings.in1Hour),
            Pair(120, strings.in2Hours)
          )

          options.forEach { (mins, label) ->
            Button(
              onClick = {
                showReminderDialog = false
                NotificationHelper.createNotificationChannel(context)
                NotificationHelper.scheduleShoppingAlarm(
                  context = context,
                  minutesLater = mins,
                  itemsSummary = itemsSummary,
                  itemsCount = pendingItemsCount
                )
                if (!NotificationHelper.areNotificationsAllowed(context)) {
                  Toast.makeText(
                    context,
                    "Please allow notifications so reminders can alert you.",
                    Toast.LENGTH_LONG
                  ).show()
                  NotificationHelper.openAppNotificationSettings(context)
                } else {
                  Toast.makeText(
                    context,
                    "Reminder set: $label",
                    Toast.LENGTH_LONG
                  ).show()
                }
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = if (activeReminderMins == mins) OrderGreen else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (activeReminderMins == mins) Color.White else OrderGreen
              ),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
            ) {
              Text(label, fontWeight = FontWeight.Bold)
            }
          }

          if (activeReminderMins > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
              onClick = {
                NotificationHelper.cancelShoppingAlarm(context)
                showReminderDialog = false
                Toast.makeText(context, strings.stopReminder, Toast.LENGTH_SHORT).show()
              },
              shape = RoundedCornerShape(12.dp),
              border = BorderStroke(1.dp, Color(0xFFD93025)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD93025)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(strings.stopReminder, fontWeight = FontWeight.Bold)
            }
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { showReminderDialog = false }) {
          Text(strings.close, color = Color.Gray)
        }
      }
    )
  }

  // Daily Bonus Coin Pop-up Dialog
  if (uiState.showCoinAnimation && uiState.coinFeatureEnabled) {
    AlertDialog(
      onDismissRequest = { viewModel.dismissCoinDialog() },
      shape = RoundedCornerShape(24.dp),
      containerColor = MaterialTheme.colorScheme.surface,
      title = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Box(
            modifier = Modifier
              .size(72.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
          ) {
            Image(
              painter = painterResource(id = R.drawable.app_coin_icon),
              contentDescription = "Paircart Coin",
              modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
            )
          }
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = strings.dailyBonusTitle,
            fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
          )
        }
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = strings.dailyBonusMsg,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "${strings.coinsLabel}: ${uiState.coins}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = OrderGreen
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.collectDailyCoin()
          },
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("collect_daily_coin_dialog_button")
        ) {
          Image(
            painter = painterResource(id = R.drawable.app_coin_icon),
            contentDescription = null,
            modifier = Modifier
              .size(24.dp)
              .clip(CircleShape)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = strings.collectBtn,
            fontWeight = FontWeight.Bold,
            color = Color.White
          )
        }
      },
      dismissButton = {
        TextButton(
          onClick = { viewModel.dismissCoinDialog() },
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(strings.close, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    )
  }
}

@Composable
private fun OrdersList(
  orders: List<Order>,
  isMyOrders: Boolean,
  emptyMessage: String,
  uiState: OrderUiState,
  nowMs: Long,
  onAcceptOrder: (String, Int) -> Unit,
  onRejectOrder: (String, String) -> Unit,
  onMarkOrderIncomplete: (String, String) -> Unit,
  onMarkItemBought: (orderId: String, itemIndex: Int, price: Double, marketName: String) -> Unit,
  onMarkItemUnavailable: (orderId: String, itemIndex: Int, reason: String, marketNameIfCompleting: String) -> Unit,
  onDeleteOrder: (String) -> Unit,
  onConnectPartner: (String) -> Unit,
  onDisconnectPartner: () -> Unit,
  modifier: Modifier = Modifier,
  listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
  val strings = LocalAppStrings.current
  var visibleLimit by remember(orders.size, isMyOrders) { mutableStateOf(5) }
  var inputPartnerNumericId by remember { mutableStateOf("") }

  val isDataLoading = if (isMyOrders) !uiState.hasLoadedMyOrders else !uiState.hasLoadedPartnerOrders
  val showLoadingState = isDataLoading || !uiState.isOnline

  if (orders.isEmpty() || !uiState.isOnline) {
    Column(
      modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      if (!isMyOrders) {
        PartnerConnectionSection(
          uiState = uiState,
          strings = strings,
          inputPartnerNumericId = inputPartnerNumericId,
          onValueChange = { inputPartnerNumericId = it },
          onConnectPartner = onConnectPartner,
          onDisconnectPartner = onDisconnectPartner
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        AnnouncementSliderCard(uiState = uiState)
        
        Spacer(modifier = Modifier.height(32.dp))
      } else {
        Spacer(modifier = Modifier.height(24.dp))
      }
      
      if (showLoadingState) {
        FacebookFeedSkeletonList(
          count = 3,
          modifier = Modifier.testTag("orders_loading_card")
        )
      } else {
        Box(
          modifier = Modifier.fillMaxWidth(),
          contentAlignment = Alignment.Center
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Icon(
              painter = painterResource(id = R.drawable.ic_receipt),
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
              modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = emptyMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center
            )
          }
        }
      }
    }
  } else {
    val visibleOrders = orders.take(visibleLimit)
    val hasMore = orders.size > visibleLimit

    LazyColumn(
      state = listState,
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      if (!isMyOrders) {
        item {
          Column(modifier = Modifier.fillMaxWidth()) {
            PartnerConnectionSection(
              uiState = uiState,
              strings = strings,
              inputPartnerNumericId = inputPartnerNumericId,
              onValueChange = { inputPartnerNumericId = it },
              onConnectPartner = onConnectPartner,
              onDisconnectPartner = onDisconnectPartner
            )

            Spacer(modifier = Modifier.height(12.dp))

            AnnouncementSliderCard(uiState = uiState)
          }
        }
      }


      items(visibleOrders, key = { it.id }) { order ->
        InvoiceOrderCard(
          order = order,
          isMyOrder = isMyOrders,
          nowMs = nowMs,
          isBusy = order.id in uiState.busyOrderIds,
          onAcceptOrder = { mins -> onAcceptOrder(order.id, mins) },
          onRejectOrder = { reason -> onRejectOrder(order.id, reason) },
          onMarkOrderIncomplete = { reason -> onMarkOrderIncomplete(order.id, reason) },
          onMarkItemBought = { index, price, marketName -> onMarkItemBought(order.id, index, price, marketName) },
          onMarkItemUnavailable = { index, reason, marketName -> onMarkItemUnavailable(order.id, index, reason, marketName) },
          onDeleteOrder = { onDeleteOrder(order.id) }
        )
      }

      if (hasMore) {
        item(key = "loading_more_orders_indicator") {
          LaunchedEffect(visibleLimit) {
            delay(600)
            visibleLimit += 5
          }

          FacebookFeedOrderSkeletonCard(
            modifier = Modifier
              .padding(vertical = 4.dp)
              .testTag("orders_loading_more_card")
          )
        }
      }
    }
  }
}

@Composable
private fun AnnouncementSliderCard(
  uiState: OrderUiState,
  modifier: Modifier = Modifier
) {
  if (!uiState.isOnline || !uiState.hasLoadedProfile) {
    FacebookNoticeSkeletonCard(modifier = modifier)
    return
  }
  val announcements = uiState.serverAnnouncements
  if (announcements.isEmpty()) return

  val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { announcements.size })

  // Auto-slide every 5 seconds; keyed only on announcements.size so programmatic scroll does not cancel itself
  LaunchedEffect(announcements.size) {
    if (pagerState.currentPage >= announcements.size) {
      pagerState.scrollToPage(0)
    }
    if (announcements.size > 1) {
      while (true) {
        kotlinx.coroutines.delay(5000L)
        if (!pagerState.isScrollInProgress) {
          val nextPage = (pagerState.currentPage + 1) % announcements.size
          pagerState.animateScrollToPage(
            page = nextPage,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 550)
          )
        }
      }
    }
  }

  val posterGradients = remember {
    listOf(
      listOf(Color(0xFF0F3A1D), Color(0xFF1B5E20), Color(0xFF2E7D32)),
      listOf(Color(0xFF4A2000), Color(0xFFD84315), Color(0xFFFF8F00)),
      listOf(Color(0xFF141B4D), Color(0xFF1A237E), Color(0xFF283593))
    )
  }

  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    androidx.compose.foundation.pager.HorizontalPager(
      state = pagerState,
      key = { idx -> announcements.getOrNull(idx)?.id ?: idx },
      pageSpacing = 12.dp,
      contentPadding = PaddingValues(0.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(152.dp)
        .testTag("announcement_banner_pager")
    ) { page ->
      val ann = announcements[page]
      val gradientColors = posterGradients[page % posterGradients.size]

      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxSize()
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.linearGradient(gradientColors))
            .padding(14.dp)
        ) {
          Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
          ) {
            // Poster Header
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.18f)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp),
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                  Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(13.dp)
                  )
                  Text(
                    text = "Notice",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                  )
                }
              }

              Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.30f)
              ) {
                Text(
                  text = "${page + 1}/${announcements.size}",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFFFFD54F),
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
              }
            }

            // Poster Body
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Text(
                text = ann.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFE082),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
              Text(
                text = ann.message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.94f),
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                lineHeight = 16.sp
              )
            }
          }
        }
      }
    }

    if (announcements.size > 1) {
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        repeat(announcements.size) { iteration ->
          val isSelected = pagerState.currentPage == iteration
          Box(
            modifier = Modifier
              .size(width = if (isSelected) 22.dp else 6.dp, height = 6.dp)
              .clip(RoundedCornerShape(3.dp))
              .background(if (isSelected) OrderGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
          )
        }
      }
    }
  }
}

@Composable
private fun PartnerConnectionSection(
  uiState: OrderUiState,
  strings: com.example.util.AppStrings,
  inputPartnerNumericId: String,
  onValueChange: (String) -> Unit,
  onConnectPartner: (String) -> Unit,
  onDisconnectPartner: () -> Unit,
  modifier: Modifier = Modifier
) {
  if (!uiState.isOnline || !uiState.hasLoadedProfile || (uiState.isPartnerConnected && !uiState.hasLoadedPartnerProfile)) {
    FacebookPartnerConnectionSkeletonCard(modifier = modifier)
    return
  }

  var showPartnerPhotoDialog by remember { mutableStateOf(false) }
  var showPartnerFullScreen4k by remember { mutableStateOf(false) }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.5.dp, OrderGreen.copy(alpha = 0.6f)),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = modifier.fillMaxWidth()
  ) {
    if (uiState.isPartnerConnected) {
      val partnerBitmap = rememberEnhanced4kImageBitmap(uiState.partnerPhotoData)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { showPartnerPhotoDialog = true }
            .padding(end = 6.dp)
            .testTag("partner_profile_photo_button")
        ) {
          Box(
            modifier = Modifier
              .size(46.dp)
              .clip(CircleShape)
              .background(OrderGreen.copy(alpha = 0.12f))
              .border(1.5.dp, OrderGreen.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
          ) {
            if (partnerBitmap != null) {
              Image(
                bitmap = partnerBitmap,
                contentDescription = "${uiState.partnerDisplayName} Profile Photo",
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                  .fillMaxSize()
                  .clip(CircleShape)
              )
            } else {
              Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "View Partner Profile Photo",
                tint = OrderGreen,
                modifier = Modifier.size(22.dp)
              )
            }
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(Color(0xFF34A853))
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = strings.connectedWithPartner,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = "${uiState.partnerDisplayName} (ID: ${uiState.partnerNumericId})",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = FontWeight.Medium
            )
          }
        }

        TextButton(
          onClick = onDisconnectPartner,
          colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD93025))
        ) {
          Text(
            text = strings.disconnect,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall
          )
        }
      }

      if (showPartnerPhotoDialog) {
        AlertDialog(
          onDismissRequest = { showPartnerPhotoDialog = false },
          shape = RoundedCornerShape(22.dp),
          containerColor = MaterialTheme.colorScheme.surface,
          title = {
            Column(modifier = Modifier.fillMaxWidth()) {
              Text(
                text = uiState.partnerDisplayName.ifBlank { "Connected Partner" },
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleMedium
              )
              Text(
                text = "ID: ${uiState.partnerNumericId}",
                style = MaterialTheme.typography.labelSmall,
                color = OrderGreen,
                fontWeight = FontWeight.Bold
              )
            }
          },
          text = {
            Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 240.dp, max = 320.dp)
                  .clip(RoundedCornerShape(20.dp))
                  .background(OrderGreen.copy(alpha = 0.08f))
                  .border(2.dp, OrderGreen.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                  .clickable(enabled = partnerBitmap != null) {
                    showPartnerFullScreen4k = true
                  }
                  .testTag("partner_profile_photo_4k_preview"),
                contentAlignment = Alignment.Center
              ) {
                if (partnerBitmap != null) {
                  Image(
                    bitmap = partnerBitmap,
                    contentDescription = "Partner Profile Picture",
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                  )
                } else {
                  Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Default.Person,
                      contentDescription = "No Profile Picture",
                      tint = OrderGreen,
                      modifier = Modifier.size(64.dp)
                    )
                    Text(
                      text = "No profile picture uploaded yet",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      textAlign = TextAlign.Center
                    )
                  }
                }
              }

              if (uiState.partnerProfession.isNotBlank() || uiState.partnerBirthday.isNotBlank() || uiState.partnerAddress.isNotBlank()) {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                  ) {
                    if (uiState.partnerProfession.isNotBlank()) {
                      Text(
                        text = "Profession: ${uiState.partnerProfession}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                      )
                    }
                    if (uiState.partnerBirthday.isNotBlank()) {
                      Text(
                        text = "Birthday: ${com.example.util.InputSanitizer.formatBirthdayDisplay(uiState.partnerBirthday)}",
                        style = MaterialTheme.typography.bodySmall
                      )
                    }
                    if (uiState.partnerAddress.isNotBlank()) {
                      Text(
                        text = "Address: ${uiState.partnerAddress}",
                        style = MaterialTheme.typography.bodySmall
                      )
                    }
                  }
                }
              }
            }
          },
          confirmButton = {
            Button(
              onClick = { showPartnerPhotoDialog = false },
              colors = ButtonDefaults.buttonColors(containerColor = OrderGreen, contentColor = Color.White),
              modifier = Modifier.height(44.dp)
            ) {
              Text(strings.close, fontWeight = FontWeight.Bold)
            }
          },
          modifier = Modifier.testTag("partner_profile_photo_dialog")
        )
      }

      if (showPartnerFullScreen4k && uiState.partnerPhotoData.isNotBlank()) {
        FullScreen4kPhotoDialog(
          photoData = uiState.partnerPhotoData,
          title = uiState.partnerDisplayName.ifBlank { "Connected Partner" },
          subtitle = "ID: ${uiState.partnerNumericId}",
          onDismiss = { showPartnerFullScreen4k = false }
        )
      }
    } else {
      // Input mode when not connected
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(14.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            tint = OrderGreen,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = strings.enterPartnerIdHint,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedTextField(
            value = inputPartnerNumericId,
            onValueChange = { onValueChange(com.example.util.InputSanitizer.filterDigitsOnly(it, 6)) },
            placeholder = {
              Text(
                "ID: 582914",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontSize = 13.sp
              )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = OrderGreen,
              unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
              focusedTextColor = MaterialTheme.colorScheme.onSurface,
              unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
              cursorColor = OrderGreen
            ),
            modifier = Modifier
              .weight(1f)
              .height(50.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = { if (inputPartnerNumericId.isNotBlank()) onConnectPartner(inputPartnerNumericId) },
            enabled = !uiState.isConnectingPartner,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OrderGreen),
            modifier = Modifier.height(50.dp)
          ) {
            if (uiState.isConnectingPartner) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp
              )
            } else {
              Text(strings.connect, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AppLaunchLoadingOverlay(isOnline: Boolean) {
  FacebookAppEntrySkeleton(isOnline = isOnline)
}
