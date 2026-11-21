package com.example.ui.order

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.FacebookProfileInfoSkeleton
import com.example.ui.components.FacebookProfileSummarySkeleton
import com.example.ui.components.FullScreen4kPhotoDialog
import com.example.ui.components.ShimmerBox
import com.example.ui.components.rememberEnhanced4kImageBitmap
import com.example.ui.theme.OrderGreen
import com.example.util.AdminConfig
import com.example.util.InputSanitizer
import com.example.util.LocalAppStrings
import com.example.util.ProfileImageHelper
import com.example.util.UrlSecurityHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileView(
  uiState: OrderUiState,
  userDisplayName: String,
  onCollectDailyCoin: () -> Unit = {},
  onSaveExtendedProfile: (
    profilePhotoData: String,
    birthday: String,
    profession: String,
    address: String,
    onComplete: (Boolean, String?) -> Unit
  ) -> Unit = { _, _, _, _, cb -> cb(true, null) },
  onSignOut: () -> Unit = {},
  onSubmitGiftClaim: (recipientName: String, phone: String, address: String, note: String) -> Unit = { _, _, _, _ -> },
  onOpenAdminPanel: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val strings = LocalAppStrings.current

  var showEditProfileDialog by remember { mutableStateOf(false) }
  var showMonthlyLimitAlert by remember { mutableStateOf(false) }
  var showOwnPhoto4kViewer by remember { mutableStateOf(false) }

  val currentMonth = remember { InputSanitizer.currentYearMonth() }
  val alreadyEditedThisMonth = uiState.lastProfileEditMonth == currentMonth && currentMonth.isNotBlank()
  val canUpgradeLegacyPhoto = uiState.profilePhotoData.isNotBlank() &&
    !uiState.profilePhotoData.startsWith("data:image/webp;base64,")

  val todayDate = remember { java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()) }
  val canCollectCoin = uiState.lastCoinDate != todayDate

  val allHistory = remember(uiState.myOrders, uiState.partnerOrders) {
    (uiState.myOrders + uiState.partnerOrders)
      .filter { it.status == "completed" }
      .sortedByDescending { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L }
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    contentPadding = PaddingValues(bottom = 88.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // --- TOP SECTION: Logout & Profile ---
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
          .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically
        ) {
          TextButton(
            onClick = onSignOut,
            modifier = Modifier.testTag("profile_logout_button_top")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ExitToApp,
              contentDescription = strings.signOut,
              tint = Color(0xFFD93025),
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = strings.signOut,
              color = Color(0xFFD93025),
              fontWeight = FontWeight.Bold,
              style = MaterialTheme.typography.labelLarge
            )
          }
        }

        // Profile Avatar
        val isServerProfileSyncing = !uiState.hasLoadedProfile || !uiState.isOnline
        val profileBitmap = rememberEnhanced4kImageBitmap(uiState.profilePhotoData)
        Box(
          modifier = Modifier
            .size(92.dp)
            .clip(CircleShape)
            .background(OrderGreen.copy(alpha = 0.15f), CircleShape)
            .border(2.5.dp, OrderGreen.copy(alpha = 0.6f), CircleShape)
            .clickable(enabled = !isServerProfileSyncing && profileBitmap != null) {
              showOwnPhoto4kViewer = true
            }
            .testTag("profile_avatar_box"),
          contentAlignment = Alignment.Center
        ) {
          if (isServerProfileSyncing) {
            ShimmerBox(modifier = Modifier.fillMaxSize(), shape = CircleShape)
          } else if (profileBitmap != null) {
            Image(
              bitmap = profileBitmap,
              contentDescription = "Profile Picture",
              contentScale = ContentScale.Crop,
              filterQuality = FilterQuality.High,
              modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
            )
          } else {
            Icon(
              imageVector = Icons.Default.Person,
              contentDescription = "Default Profile Avatar",
              tint = OrderGreen,
              modifier = Modifier.size(46.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = userDisplayName,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.ExtraBold,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ID + Copy Button
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center
        ) {
          Text(
            text = "ID: ${uiState.myNumericId}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.width(6.dp))
          IconButton(
            onClick = {
              val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
              val clip = android.content.ClipData.newPlainText("Paircart ID", uiState.myNumericId)
              clipboard.setPrimaryClip(clip)
              android.widget.Toast.makeText(context, String.format(strings.copiedIdToast, uiState.myNumericId), android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.size(24.dp)
          ) {
            Icon(
              painter = painterResource(id = R.drawable.ic_content_copy),
              contentDescription = strings.copyId,
              tint = OrderGreen,
              modifier = Modifier.size(16.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Saved Extended Profile Information Card (Birthday, Profession, Address)
        val formattedBirthday = remember(uiState.profileBirthday) {
          InputSanitizer.formatBirthdayDisplay(uiState.profileBirthday)
        }
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_details_card")
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(
              text = "Personal Info",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.ExtraBold,
              color = OrderGreen
            )

            val showProfileLoading = !uiState.hasLoadedProfile || !uiState.isOnline

            if (showProfileLoading) {
              FacebookProfileInfoSkeleton()
            } else {
              ProfileDetailRow(
                icon = Icons.Default.DateRange,
                label = "Birthday",
                value = formattedBirthday.ifBlank { "Not added yet" },
                isPlaceholder = formattedBirthday.isBlank(),
                tag = "profile_birthday_value"
              )
              ProfileDetailRow(
                icon = Icons.Default.AccountCircle,
                label = "Profession",
                value = uiState.profileProfession.ifBlank { "Not added yet" },
                isPlaceholder = uiState.profileProfession.isBlank(),
                tag = "profile_profession_value"
              )
              ProfileDetailRow(
                icon = Icons.Default.LocationOn,
                label = "Address",
                value = uiState.profileAddress.ifBlank { "Not added yet" },
                isPlaceholder = uiState.profileAddress.isBlank(),
                tag = "profile_address_value"
              )
            }
          }
        }
      }
    }

    // --- ADMIN QUICK SHORTCUT (Visible only to zahidhasan2000joy@gmail.com) ---
    if (uiState.isAdmin) {
      item {
        Card(
          onClick = onOpenAdminPanel,
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF0F3D1F)),
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("profile_open_admin_button")
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Admin Control Panel",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = "Users (${uiState.adminUsers.size}) • 180+ Claims (${uiState.adminGiftClaims.size}) • Orders (${uiState.adminOrders.size})",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f)
              )
            }
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Open Admin Panel",
              tint = Color(0xFFFFD54F),
              modifier = Modifier.size(24.dp)
            )
          }
        }
      }
    }

    // --- UNIFIED COIN, 180-COIN GIFT CLAIM & LINK POSTING CARD ---
    if (uiState.coinFeatureEnabled) {
      item {
        val isGiftUnlocked = uiState.coins >= AdminConfig.GIFT_COIN_THRESHOLD

        if (!uiState.hasLoadedProfile || !uiState.isOnline) {
          FacebookProfileSummarySkeleton()
        } else {
        Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
          containerColor = Color(0xFFE8F5E9)
        ),
        border = BorderStroke(1.5.dp, OrderGreen.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
        ) {
          // Top row: Coin balance + Claim Daily Coin Button
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Box(
                modifier = Modifier
                  .size(46.dp)
                  .background(Color(0xFFFFD54F), CircleShape)
                  .padding(2.dp),
                contentAlignment = Alignment.Center
              ) {
                Image(
                  painter = painterResource(id = R.drawable.app_coin_icon),
                  contentDescription = "Paircart Coins",
                  modifier = Modifier.size(34.dp).clip(CircleShape)
                )
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column {
                Text(
                  text = "${strings.coinsLabel}: ${uiState.coins}",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFF1B5E20)
                )
                Text(
                  text = if (isGiftUnlocked) {
                    "180+ Coins reached — gift unlocked"
                  } else {
                    "Open the app daily to collect coins"
                  },
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFF2E7D32)
                )
              }
            }

            if (canCollectCoin) {
              Button(
                onClick = onCollectDailyCoin,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFFFFB300),
                  contentColor = Color(0xFF3E2723)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp)
              ) {
                Text(text = "+1 Coin", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
              }
            } else {
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = OrderGreen.copy(alpha = 0.15f),
                modifier = Modifier.height(32.dp)
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Collected",
                    tint = OrderGreen,
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = strings.collectedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = OrderGreen
                  )
                }
              }
            }
          }

          // 180-Coin Delivery Address Option (ONLY appears once user reaches 180+ coins)
          if (isGiftUnlocked) {
            val existingClaim = uiState.myGiftClaim
            var isEditingAddress by remember(existingClaim?.id) { mutableStateOf(existingClaim == null) }
            var recipientInput by remember(existingClaim?.recipientName) {
              mutableStateOf(existingClaim?.recipientName ?: userDisplayName)
            }
            var phoneInput by remember(existingClaim?.phone) {
              mutableStateOf(existingClaim?.phone ?: "")
            }
            var addressInput by remember(existingClaim?.address) {
              mutableStateOf(existingClaim?.address ?: "")
            }
            var noteInput by remember(existingClaim?.note) {
              mutableStateOf(existingClaim?.note ?: "")
            }
            var addressValidationError by remember { mutableStateOf(false) }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = OrderGreen.copy(alpha = 0.2f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            if (existingClaim != null && !isEditingAddress) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                border = BorderStroke(1.dp, OrderGreen.copy(alpha = 0.35f)),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("submitted_gift_address_card")
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = strings.giftAddressSubmittedTitle,
                      style = MaterialTheme.typography.labelLarge,
                      fontWeight = FontWeight.ExtraBold,
                      color = Color(0xFF1B5E20)
                    )
                    val statusColor = when (existingClaim.status) {
                      "delivered" -> Color(0xFF137333)
                      "approved" -> Color(0xFF0284C7)
                      else -> Color(0xFFE37400)
                    }
                    Surface(
                      shape = RoundedCornerShape(8.dp),
                      color = statusColor.copy(alpha = 0.14f)
                    ) {
                      Text(
                        text = existingClaim.status.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                      )
                    }
                  }
                  Spacer(modifier = Modifier.height(6.dp))
                  Text(
                    text = "User ID: ${existingClaim.userNumericId.ifBlank { uiState.myNumericId }}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF1B5E20)
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = "${existingClaim.recipientName} • ${existingClaim.phone}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Text(
                    text = existingClaim.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  if (existingClaim.note.isNotBlank()) {
                    Text(
                      text = "Note: ${existingClaim.note}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  if (existingClaim.status == "pending") {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                      onClick = { isEditingAddress = true },
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                      modifier = Modifier.height(32.dp)
                    ) {
                      Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = OrderGreen)
                      Spacer(modifier = Modifier.width(4.dp))
                      Text(strings.updateAddressBtn, color = OrderGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                  }
                }
              }
            } else {
              Text(
                text = strings.giftClaimUnlockedTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1B5E20)
              )
              Spacer(modifier = Modifier.height(8.dp))

              OutlinedTextField(
                value = uiState.myNumericId,
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = {
                  Text(
                    text = "User ID (Attached)",
                    color = Color(0xFF2E7D32)
                  )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                  disabledTextColor = Color(0xFF1B5E20),
                  disabledBorderColor = OrderGreen.copy(alpha = 0.4f),
                  disabledContainerColor = Color.White.copy(alpha = 0.85f),
                  disabledLabelColor = Color(0xFF2E7D32)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("gift_user_id_display")
              )
              Spacer(modifier = Modifier.height(8.dp))

              OutlinedTextField(
                value = recipientInput,
                onValueChange = { raw ->
                  recipientInput = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_PERSON_NAME_LEN)
                  addressValidationError = false
                },
                label = { Text(strings.giftRecipientLabel, color = Color(0xFF2E7D32)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = OrderGreen,
                  unfocusedBorderColor = OrderGreen.copy(alpha = 0.5f),
                  focusedContainerColor = Color.White,
                  unfocusedContainerColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("gift_recipient_input")
              )
              Spacer(modifier = Modifier.height(8.dp))
              OutlinedTextField(
                value = phoneInput,
                onValueChange = { raw ->
                  phoneInput = InputSanitizer.filterPhoneInput(raw, InputSanitizer.MAX_PHONE_LEN)
                  addressValidationError = false
                },
                label = { Text(strings.giftPhoneLabel, color = Color(0xFF2E7D32)) },
                placeholder = { Text("017XXXXXXXX") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = OrderGreen,
                  unfocusedBorderColor = OrderGreen.copy(alpha = 0.5f),
                  focusedContainerColor = Color.White,
                  unfocusedContainerColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("gift_phone_input")
              )
              Spacer(modifier = Modifier.height(8.dp))
              OutlinedTextField(
                value = addressInput,
                onValueChange = { raw ->
                  addressInput = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_ADDRESS_LEN, allowNewlines = true)
                  addressValidationError = false
                },
                label = { Text(strings.giftAddressLabel, color = Color(0xFF2E7D32)) },
                placeholder = { Text("House/Flat, Road, Area, City") },
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = OrderGreen,
                  unfocusedBorderColor = OrderGreen.copy(alpha = 0.5f),
                  focusedContainerColor = Color.White,
                  unfocusedContainerColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("gift_address_input")
              )
              Spacer(modifier = Modifier.height(8.dp))
              OutlinedTextField(
                value = noteInput,
                onValueChange = { raw ->
                  noteInput = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_GIFT_NOTE_LEN)
                },
                label = { Text(strings.giftNoteLabel, color = Color(0xFF2E7D32)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = OrderGreen,
                  unfocusedBorderColor = OrderGreen.copy(alpha = 0.5f),
                  focusedContainerColor = Color.White,
                  unfocusedContainerColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("gift_note_input")
              )

              if (addressValidationError) {
                Text(
                  text = strings.giftFieldError,
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFFD93025),
                  modifier = Modifier.padding(top = 6.dp)
                )
              }

              Spacer(modifier = Modifier.height(10.dp))
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                if (existingClaim != null) {
                  OutlinedButton(
                    onClick = { isEditingAddress = false },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                  ) {
                    Text(strings.cancel)
                  }
                }
                Button(
                  onClick = {
                    val cleanName = InputSanitizer.sanitizePersonName(recipientInput)
                    val cleanPhone = InputSanitizer.sanitizePhone(phoneInput)
                    val cleanAddress = InputSanitizer.sanitizeAddress(addressInput)
                    val cleanNote = InputSanitizer.sanitizeForSubmit(noteInput, InputSanitizer.MAX_GIFT_NOTE_LEN)
                    if (!InputSanitizer.isValidPersonName(cleanName) ||
                      !InputSanitizer.isValidPhone(cleanPhone) ||
                      !InputSanitizer.isValidAddress(cleanAddress)
                    ) {
                      addressValidationError = true
                    } else {
                      onSubmitGiftClaim(cleanName, cleanPhone, cleanAddress, cleanNote)
                      isEditingAddress = false
                    }
                  },
                  enabled = !uiState.isSubmittingGiftClaim,
                  shape = RoundedCornerShape(10.dp),
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20), contentColor = Color.White),
                  modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("submit_gift_address_button")
                ) {
                  if (uiState.isSubmittingGiftClaim) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                  } else {
                    Text(
                      text = if (existingClaim != null) strings.updateAddressBtn else strings.submitAddressBtn,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
    }
  }

    // --- QUICK ACTIONS (Support & Edit Profile) ---
    item {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedButton(
          onClick = { UrlSecurityHelper.openWebUrlSafely(context, "https://www.facebook.com/profile.php?id=61595911449155") },
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.5.dp, OrderGreen),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = OrderGreen),
          modifier = Modifier.weight(1f).height(48.dp)
        ) {
          Icon(painterResource(id = R.drawable.ic_support_headset), contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(strings.contactSupport, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }

        Button(
          onClick = {
            if (alreadyEditedThisMonth && !canUpgradeLegacyPhoto) {
              showMonthlyLimitAlert = true
            } else {
              showEditProfileDialog = true
            }
          },
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen, contentColor = Color.White),
          modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .testTag("profile_edit_button")
        ) {
          Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = strings.editProfileBtn,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
          )
        }
      }
    }

    // --- UNIFIED SUMMARY & ORDER HISTORY IN ONE SINGLE CARD ---
    item {
      val isOrdersDataSyncing = !uiState.isOnline || !uiState.hasLoadedMyOrders || !uiState.hasLoadedPartnerOrders
      if (isOrdersDataSyncing) {
        FacebookProfileSummarySkeleton()
      } else {
      val mySpent = uiState.mySpentTotal
      val partnerSpent = uiState.partnerSpentTotal
      val grandTotal = uiState.grandTotalCost
      val halfSplit = grandTotal / 2.0
      val diff = mySpent - partnerSpent

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp)
          .testTag("summary_and_history_card")
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // 1. Expense Split Block (embedded directly inside the single card — no nested cards)
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .background(OrderGreen.copy(alpha = 0.10f))
              .padding(vertical = 14.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = strings.totalExpenses,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = "৳${String.format(Locale.US, "%.2f", grandTotal)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = OrderGreen
              )
              Text(
                text = strings.splitBillTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(text = "${strings.myPurchasesTotal}:", style = MaterialTheme.typography.bodyMedium)
              Text(
                text = "৳${String.format(Locale.US, "%.2f", mySpent)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = OrderGreen
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(text = "${strings.partnerPurchasesTotal}:", style = MaterialTheme.typography.bodyMedium)
              Text(
                text = "৳${String.format(Locale.US, "%.2f", partnerSpent)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = OrderGreen
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = strings.fairShare,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Text(
                text = "৳${String.format(Locale.US, "%.2f", halfSplit)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
              )
            }
          }

          val settlementText = when {
            grandTotal == 0.0 -> strings.noItemsFound
            kotlin.math.abs(diff) < 0.01 -> strings.allBalanced
            diff > 0 -> String.format(Locale.US, strings.partnerOwesYou, diff / 2.0)
            else -> String.format(Locale.US, strings.youOwePartner, kotlin.math.abs(diff / 2.0))
          }

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
              .padding(horizontal = 12.dp, vertical = 8.dp)
          ) {
            Text(
              text = "${strings.settlementSummary}: $settlementText",
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
          }

          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

          // Product purchase breakdown with full Invoice details
          var selectedProductForDetail by remember { mutableStateOf<ProductFrequencyGroup?>(null) }

          val currentFirebaseUid = Firebase.auth.currentUser?.uid
          val productGroups = remember(allHistory, currentFirebaseUid, uiState.isAdmin) {
            val map = mutableMapOf<String, MutableList<ProductPurchaseDetail>>()
            allHistory.forEach { ord ->
              val invoiceNo = ord.orderNumber.ifBlank { ord.id.take(8).uppercase() }
              val dateStr = ord.formattedDate.ifBlank { "N/A" }
              val timeStr = ord.formattedTime.ifBlank { "N/A" }
              val status = ord.status
              val wasIRequester = ord.userId == currentFirebaseUid
              val relationText = if (wasIRequester) {
                "You requested from ${ord.partnerName.ifBlank { "Partner" }}"
              } else {
                "You bought for ${ord.userName.ifBlank { "Partner" }}"
              }
              val invoiceItemsSummary = ord.typedItems.joinToString(", ") {
                if (it.quantity > 1) "${it.name} (x${it.quantity})" else it.name
              }

              if (ord.typedItems.isEmpty()) {
                val fallbackName = "Order #$invoiceNo"
                map.getOrPut(fallbackName) { mutableListOf() }.add(
                  ProductPurchaseDetail(
                    orderNumber = invoiceNo,
                    formattedDate = dateStr,
                    formattedTime = timeStr,
                    price = ord.totalCost,
                    orderTotalCost = ord.totalCost,
                    quantity = 1,
                    status = status,
                    notes = "",
                    relationText = relationText,
                    marketName = if (uiState.isAdmin) ord.purchaseMarketName.trim() else "",
                    invoiceItemsSummary = invoiceItemsSummary
                  )
                )
              } else {
                ord.typedItems.forEach { item ->
                  val name = item.name.trim().ifBlank { "Order #$invoiceNo" }
                  val price = if (item.boughtPrice > 0.0) {
                    item.boughtPrice
                  } else if (ord.totalCost > 0.0 && ord.typedItems.isNotEmpty()) {
                    ord.totalCost / ord.typedItems.size
                  } else {
                    0.0
                  }
                  val detail = ProductPurchaseDetail(
                    orderNumber = invoiceNo,
                    formattedDate = dateStr,
                    formattedTime = timeStr,
                    price = price,
                    orderTotalCost = ord.totalCost,
                    quantity = item.quantity.coerceAtLeast(1),
                    status = status,
                    notes = item.notes.trim(),
                    relationText = relationText,
                    marketName = if (uiState.isAdmin) ord.purchaseMarketName.trim() else "",
                    invoiceItemsSummary = invoiceItemsSummary
                  )
                  map.getOrPut(name) { mutableListOf() }.add(detail)
                }
              }
            }
            map.map { (name, list) ->
              ProductFrequencyGroup(
                name = name,
                totalPurchases = list.size,
                totalSpent = list.sumOf { it.price },
                purchases = list.sortedWith(compareByDescending<ProductPurchaseDetail> { it.formattedDate }.thenByDescending { it.formattedTime })
              )
            }.sortedByDescending { it.totalPurchases }
          }

          // Product Purchases List (with invoice numbers, dates, times, partner info, & totals)
          if (productGroups.isEmpty()) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(vertical = 18.dp, horizontal = 12.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = "No product purchase records yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
            }
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              productGroups.forEach { group ->
                val latestPurchase = group.purchases.firstOrNull()
                Surface(
                  onClick = { selectedProductForDetail = group },
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                  border = BorderStroke(1.dp, OrderGreen.copy(alpha = 0.25f)),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                      )
                      Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = OrderGreen.copy(alpha = 0.15f)
                      ) {
                        Text(
                          text = "${group.totalPurchases}x bought",
                          style = MaterialTheme.typography.labelSmall,
                          fontWeight = FontWeight.Bold,
                          color = OrderGreen,
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                      }
                    }
                    if (latestPurchase != null) {
                      Text(
                        text = "${latestPurchase.orderNumber} • ${latestPurchase.formattedDate} • ${latestPurchase.formattedTime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                      Text(
                        text = latestPurchase.relationText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (latestPurchase.relationText.startsWith("You requested")) Color(0xFFD93025) else OrderGreen
                      )
                    }
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = "Total Spent: ৳${String.format(Locale.US, "%.2f", group.totalSpent)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                      Text(
                        text = "View Details ›",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = OrderGreen
                      )
                    }
                  }
                }
              }
            }
          }

          // Modal Dialog showing exact purchase & invoice breakdown for tapped product
          selectedProductForDetail?.let { group ->
            AlertDialog(
              onDismissRequest = { selectedProductForDetail = null },
              title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(text = group.name, fontWeight = FontWeight.Bold)
                  Text(
                    text = "Bought ${group.totalPurchases} time(s) • Total Spent: ৳${String.format(Locale.US, "%.2f", group.totalSpent)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              },
              text = {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                  verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  group.purchases.forEach { detail ->
                    Surface(
                      shape = RoundedCornerShape(10.dp),
                      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Column(
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                      ) {
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          Text(
                            text = "Invoice ${detail.orderNumber}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = OrderGreen
                          )
                          Text(
                            text = "৳${String.format(Locale.US, "%.2f", detail.price)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                          )
                        }
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                          Text(
                            text = "Date: ${detail.formattedDate}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                          Text(
                            text = "Time: ${detail.formattedTime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                        }
                        if (detail.relationText.isNotBlank()) {
                          Text(
                            text = detail.relationText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (detail.relationText.startsWith("You requested")) Color(0xFFD93025) else OrderGreen
                          )
                        }
                        if (detail.notes.isNotBlank()) {
                          Text(
                            text = "Note: ${detail.notes}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                        }
                        if (detail.invoiceItemsSummary.isNotBlank()) {
                          Text(
                            text = "Invoice Items: ${detail.invoiceItemsSummary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                        }
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          Text(
                            text = "Qty: ${detail.quantity} • Inv Total: ৳${String.format(Locale.US, "%.2f", detail.orderTotalCost)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                          Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (detail.status == "completed") Color(0xFF1B5E20).copy(alpha = 0.15f) else Color(0xFFFFB300).copy(alpha = 0.2f)
                          ) {
                            Text(
                              text = detail.status.uppercase(),
                              style = MaterialTheme.typography.labelSmall,
                              fontWeight = FontWeight.Bold,
                              color = if (detail.status == "completed") Color(0xFF1B5E20) else Color(0xFFE65100),
                              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                          }
                        }
                        if (uiState.isAdmin && detail.marketName.isNotBlank()) {
                          Text(
                            text = "Market: ${detail.marketName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                        }
                      }
                    }
                  }
                }
              },
              confirmButton = {
                Button(
                  onClick = { selectedProductForDetail = null },
                  colors = ButtonDefaults.buttonColors(containerColor = OrderGreen)
                ) {
                  Text("Close", fontWeight = FontWeight.Bold)
                }
              }
            )
          }
        }
      }
      }
    }
  }

  // Monthly Edit Limit Alert Dialog
  if (showMonthlyLimitAlert) {
    val nextEligibleDate = InputSanitizer.nextEligibleEditDateLabel(uiState.lastProfileEditMonth.ifBlank { currentMonth })
    val editedOnText = if (uiState.lastProfileEditAtMs > 0L) {
      SimpleDateFormat("dd MMM, yyyy", Locale.US).format(Date(uiState.lastProfileEditAtMs))
    } else {
      uiState.lastProfileEditMonth
    }
    AlertDialog(
      onDismissRequest = { showMonthlyLimitAlert = false },
      icon = {
        Icon(
          imageVector = Icons.Default.Warning,
          contentDescription = null,
          tint = Color(0xFFD97706),
          modifier = Modifier.size(32.dp)
        )
      },
      title = {
        Text(
          text = "Monthly Edit Limit Reached",
          fontWeight = FontWeight.ExtraBold,
          textAlign = TextAlign.Center
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "You can only edit your profile once per calendar month.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
          )
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFFFFBEB),
            border = BorderStroke(1.dp, Color(0xFFFDE68A)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Text(
                text = "Last edited: $editedOnText",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF92400E)
              )
              Text(
                text = "Next edit available from: $nextEligibleDate",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = OrderGreen
              )
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = { showMonthlyLimitAlert = false },
          colors = ButtonDefaults.buttonColors(containerColor = OrderGreen, contentColor = Color.White),
          modifier = Modifier.testTag("monthly_limit_alert_ok_button")
        ) {
          Text("OK", fontWeight = FontWeight.Bold)
        }
      },
      modifier = Modifier.testTag("monthly_profile_edit_alert")
    )
  }

  // Edit Profile Dialog (Profile Picture, Birthday, Profession, Address)
  if (showEditProfileDialog) {
    EditProfileDialog(
      initialPhotoData = uiState.profilePhotoData,
      initialBirthday = uiState.profileBirthday,
      initialProfession = uiState.profileProfession,
      initialAddress = uiState.profileAddress,
      photoUpgradeOnly = alreadyEditedThisMonth && canUpgradeLegacyPhoto,
      isSaving = uiState.isSavingProfile,
      onDismiss = {
        if (!uiState.isSavingProfile) {
          showEditProfileDialog = false
        }
      },
      onSave = { photo, birthday, profession, address, onValidationError ->
        onSaveExtendedProfile(photo, birthday, profession, address) { success, errMsg ->
          if (success) {
            showEditProfileDialog = false
          } else {
            onValidationError(errMsg ?: "Failed to save profile")
          }
        }
      }
    )
  }

  if (showOwnPhoto4kViewer && uiState.profilePhotoData.isNotBlank()) {
    FullScreen4kPhotoDialog(
      photoData = uiState.profilePhotoData,
      title = userDisplayName,
      subtitle = "ID: ${uiState.myNumericId}",
      onDismiss = { showOwnPhoto4kViewer = false }
    )
  }
}

@Composable
private fun ProfileDetailRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  value: String,
  isPlaceholder: Boolean,
  tag: String
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = OrderGreen,
      modifier = Modifier
        .padding(top = 2.dp)
        .size(18.dp)
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (isPlaceholder) FontWeight.Normal else FontWeight.SemiBold,
        color = if (isPlaceholder) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(tag)
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileDialog(
  initialPhotoData: String,
  initialBirthday: String,
  initialProfession: String,
  initialAddress: String,
  photoUpgradeOnly: Boolean = false,
  isSaving: Boolean,
  onDismiss: () -> Unit,
  onSave: (
    photoData: String,
    birthday: String,
    profession: String,
    address: String,
    onValidationError: (String) -> Unit
  ) -> Unit
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  var photoData by remember { mutableStateOf(initialPhotoData) }
  var birthdayIso by remember { mutableStateOf(initialBirthday) }
  var professionInput by remember { mutableStateOf(initialProfession) }
  var addressInput by remember { mutableStateOf(initialAddress) }
  var isCompressingPhoto by remember { mutableStateOf(false) }
  var showDatePicker by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }

  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
  ) { uri ->
    if (uri != null) {
      isCompressingPhoto = true
      errorText = null
      coroutineScope.launch {
        val compressed = ProfileImageHelper.uriToCompressedDataUri(context, uri)
        isCompressingPhoto = false
        if (compressed != null) {
          photoData = compressed
        } else {
          errorText = "Could not process selected image. Please choose another photo."
        }
      }
    }
  }

  val previewBitmap = rememberEnhanced4kImageBitmap(photoData)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (photoUpgradeOnly) "Update Profile Photo" else "Edit Profile",
        fontWeight = FontWeight.ExtraBold
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 460.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        // Profile Photo Picker
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Box(
            modifier = Modifier
              .size(84.dp)
              .clip(CircleShape)
              .background(OrderGreen.copy(alpha = 0.12f))
              .border(2.dp, OrderGreen.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
          ) {
            if (isCompressingPhoto) {
              CircularProgressIndicator(modifier = Modifier.size(26.dp), color = OrderGreen, strokeWidth = 2.5.dp)
            } else if (previewBitmap != null) {
              Image(
                bitmap = previewBitmap,
                contentDescription = "Selected Profile Picture",
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                  .fillMaxSize()
                  .clip(CircleShape)
              )
            } else {
              Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "No Profile Picture",
                tint = OrderGreen,
                modifier = Modifier.size(40.dp)
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = {
                photoPickerLauncher.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
              },
              enabled = !isCompressingPhoto && !isSaving,
              shape = RoundedCornerShape(10.dp),
              border = BorderStroke(1.dp, OrderGreen),
              modifier = Modifier
                .height(40.dp)
                .testTag("edit_profile_pick_photo_button")
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = OrderGreen)
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = if (photoData.isNotBlank()) "Change Photo" else "Add Photo",
                color = OrderGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
              )
            }

            if (photoData.isNotBlank() && !photoUpgradeOnly) {
              TextButton(
                onClick = { photoData = "" },
                enabled = !isCompressingPhoto && !isSaving,
                modifier = Modifier.height(40.dp)
              ) {
                Text("Remove", color = Color(0xFFD93025), fontWeight = FontWeight.Bold, fontSize = 12.sp)
              }
            }
          }
        }

        if (!photoUpgradeOnly) {
          // Birthday Selector (Material 3 DatePicker)
          val formattedBirthday = remember(birthdayIso) {
            InputSanitizer.formatBirthdayDisplay(birthdayIso)
          }
          Surface(
            onClick = { if (!isSaving) showDatePicker = true },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("edit_profile_birthday_button")
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
              ) {
                Icon(Icons.Default.DateRange, contentDescription = "Birthday", tint = OrderGreen, modifier = Modifier.size(20.dp))
                Column {
                  Text(
                    text = "Birthday",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Text(
                    text = formattedBirthday.ifBlank { "Tap to select birthday" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (formattedBirthday.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (formattedBirthday.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
              if (birthdayIso.isNotBlank()) {
                IconButton(
                  onClick = { birthdayIso = "" },
                  modifier = Modifier.size(28.dp)
                ) {
                  Icon(Icons.Default.Close, contentDescription = "Clear birthday", modifier = Modifier.size(16.dp))
                }
              }
            }
          }

          // Profession Input
          OutlinedTextField(
            value = professionInput,
            onValueChange = { raw ->
              professionInput = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_PROFESSION_LEN)
              errorText = null
            },
            label = { Text("Profession") },
            placeholder = { Text("e.g. Software Engineer, Student, Business") },
            leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = OrderGreen) },
            singleLine = true,
            supportingText = { Text("${professionInput.length}/${InputSanitizer.MAX_PROFESSION_LEN}") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("edit_profile_profession_input")
          )

          // Address Input
          OutlinedTextField(
            value = addressInput,
            onValueChange = { raw ->
              addressInput = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_PROFILE_ADDRESS_LEN, allowNewlines = true)
              errorText = null
            },
            label = { Text("Address") },
            placeholder = { Text("e.g. House 12, Road 4, Dhanmondi, Dhaka") },
            leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = OrderGreen) },
            maxLines = 3,
            supportingText = { Text("${addressInput.length}/${InputSanitizer.MAX_PROFILE_ADDRESS_LEN}") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("edit_profile_address_input")
          )
        }

        Surface(
          shape = RoundedCornerShape(10.dp),
          color = OrderGreen.copy(alpha = 0.08f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = OrderGreen, modifier = Modifier.size(18.dp))
            Text(
              text = if (photoUpgradeOnly) {
                "Select a fresh photo to replace your older compressed picture with crisp HD quality."
              } else {
                "Note: You can edit your profile once per calendar month."
              },
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              color = Color(0xFF1B5E20)
            )
          }
        }

        if (!errorText.isNullOrBlank()) {
          Text(
            text = errorText!!,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFD93025),
            fontWeight = FontWeight.Bold
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val cleanProfession = InputSanitizer.sanitizeProfession(professionInput)
          val cleanAddress = InputSanitizer.sanitizeProfileAddress(addressInput)
          val cleanBirthday = InputSanitizer.sanitizeBirthday(birthdayIso)

          if (cleanProfession.isNotEmpty() && !InputSanitizer.isValidProfession(cleanProfession)) {
            errorText = "Please enter a valid profession (2-60 characters)."
            return@Button
          }
          if (cleanAddress.isNotEmpty() && !InputSanitizer.isValidProfileAddress(cleanAddress)) {
            errorText = "Please enter a valid address (3-200 characters)."
            return@Button
          }
          if (photoData.isBlank() && cleanBirthday.isBlank() && cleanProfession.isBlank() && cleanAddress.isBlank()) {
            errorText = "Please fill in at least one profile field before saving."
            return@Button
          }

          onSave(photoData, cleanBirthday, cleanProfession, cleanAddress) { msg ->
            errorText = msg
          }
        },
        enabled = !isSaving && !isCompressingPhoto,
        colors = ButtonDefaults.buttonColors(containerColor = OrderGreen, contentColor = Color.White),
        modifier = Modifier
          .height(48.dp)
          .testTag("edit_profile_save_button")
      ) {
        if (isSaving) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
          Text("Save", fontWeight = FontWeight.Bold)
        }
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = onDismiss,
        enabled = !isSaving,
        modifier = Modifier.height(48.dp)
      ) {
        Text("Cancel", fontWeight = FontWeight.Bold)
      }
    },
    modifier = Modifier.testTag("edit_profile_dialog")
  )

  if (showDatePicker) {
    val initialSelectedMillis = remember(birthdayIso) {
      if (birthdayIso.isNotBlank()) {
        try {
          SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
          }.parse(birthdayIso)?.time
        } catch (_: Exception) {
          null
        }
      } else {
        null
      }
    }
    val datePickerState = rememberDatePickerState(
      initialSelectedDateMillis = initialSelectedMillis,
      selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
          return utcTimeMillis <= System.currentTimeMillis()
        }
      }
    )
    DatePickerDialog(
      onDismissRequest = { showDatePicker = false },
      confirmButton = {
        TextButton(
          onClick = {
            val selected = datePickerState.selectedDateMillis
            if (selected != null) {
              val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
              }
              birthdayIso = fmt.format(Date(selected))
              errorText = null
            }
            showDatePicker = false
          }
        ) {
          Text("OK", color = OrderGreen, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDatePicker = false }) {
          Text("Cancel")
        }
      }
    ) {
      DatePicker(state = datePickerState)
    }
  }
}

data class ProductPurchaseDetail(
  val orderNumber: String,
  val formattedDate: String,
  val formattedTime: String,
  val price: Double,
  val orderTotalCost: Double = 0.0,
  val quantity: Int,
  val status: String,
  val notes: String = "",
  val relationText: String = "",
  val marketName: String = "",
  val invoiceItemsSummary: String = ""
)

data class ProductFrequencyGroup(
  val name: String,
  val totalPurchases: Int,
  val totalSpent: Double,
  val purchases: List<ProductPurchaseDetail>
)
