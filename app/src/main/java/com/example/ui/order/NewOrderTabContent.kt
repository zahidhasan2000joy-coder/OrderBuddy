package com.example.ui.order

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import com.example.util.ItemCatalogStore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import com.example.ui.components.ShimmerBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.OrderItemDetail
import com.example.ui.theme.OrderGreen
import com.example.util.InputSanitizer
import com.example.util.LocalAppStrings
import com.example.util.UrlSecurityHelper

@Composable
fun NewOrderTabContent(
  uiState: OrderUiState,
  onSendOrder: (List<OrderItemDetail>, onSuccess: () -> Unit) -> Unit,
  onCancelPendingSend: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val strings = LocalAppStrings.current
  val itemList = remember { mutableStateListOf<OrderItemDetail>() }

  var currentItemName by remember { mutableStateOf("") }
  var currentQuantity by remember { mutableIntStateOf(1) }
  var currentNotes by remember { mutableStateOf("") }
  var currentProductUrl by remember { mutableStateOf("") }
  var currentImageUrl by remember { mutableStateOf("") }
  var isImageLoading by remember { mutableStateOf(false) }
  var isError by remember { mutableStateOf(false) }
  var isNotesError by remember { mutableStateOf(false) }
  var isUrlError by remember { mutableStateOf(false) }
  var isSubmitting by remember { mutableStateOf(false) }
  var catalogVersion by remember { mutableIntStateOf(0) }

  LaunchedEffect(uiState.isLoading, uiState.isSendingOrder, uiState.isWaitingOfflineToSendOrder) {
    if (!uiState.isLoading && !uiState.isSendingOrder && !uiState.isWaitingOfflineToSendOrder) {
      isSubmitting = false
    }
  }

  LaunchedEffect(currentProductUrl) {
    val clean = UrlSecurityHelper.extractUrlFromText(currentProductUrl)
      ?: UrlSecurityHelper.sanitizeUrl(currentProductUrl)
    if (!clean.isNullOrBlank()) {
      isImageLoading = true
      val img = UrlSecurityHelper.fetchProductImage(clean)
      currentImageUrl = img ?: ""
      isImageLoading = false
    } else if (currentImageUrl.isBlank()) {
      currentImageUrl = ""
      isImageLoading = false
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp)
  ) {
    // Header Card
    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(OrderGreen.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              painter = painterResource(id = R.drawable.ic_receipt),
              contentDescription = null,
              tint = OrderGreen,
              modifier = Modifier.size(22.dp)
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column {
            Text(
              text = strings.newOrderInvoiceTitle,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = strings.addItemsInstruction,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Item Name with Auto-Suggest / Search List (English & Bengali separated)
        var showSuggestions by remember { mutableStateOf(false) }

        val allAvailableItems = remember(
          uiState.myOrders,
          uiState.partnerOrders,
          uiState.adminOrders,
          uiState.adminMarketPurchases,
          catalogVersion
        ) {
          ItemCatalogStore.getAllSuggestions(
            context = context,
            myOrders = uiState.myOrders,
            partnerOrders = uiState.partnerOrders,
            adminOrders = uiState.adminOrders,
            marketPurchases = uiState.adminMarketPurchases
          )
        }

        val trimmedQuery = currentItemName.trim()
        val lowerQuery = trimmedQuery.lowercase()
        val filteredSuggestions = remember(trimmedQuery, allAvailableItems) {
          if (trimmedQuery.isBlank()) {
            allAvailableItems.take(30)
          } else {
            val queryIsBengali = ItemCatalogStore.containsBengali(trimmedQuery)
            allAvailableItems
              .filter { ItemCatalogStore.containsBengali(it) == queryIsBengali && it.lowercase().contains(lowerQuery) }
              .take(30)
          }
        }
        OutlinedTextField(
          value = currentItemName,
          onValueChange = { raw ->
            val sanitized = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_ITEM_NAME_LEN)
            currentItemName = sanitized
            if (sanitized.isNotBlank()) isError = false
            showSuggestions = sanitized.isNotBlank()
          },
          label = { Text(strings.itemNameLabel) },
          placeholder = { Text(strings.itemNamePlaceholder) },
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.Search,
              contentDescription = "Search items",
              tint = OrderGreen
            )
          },
          trailingIcon = if (currentItemName.isNotBlank()) {
            {
              IconButton(onClick = {
                currentItemName = ""
                showSuggestions = false
              }) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Clear",
                  tint = Color.Gray,
                  modifier = Modifier.size(18.dp)
                )
              }
            }
          } else null,
          isError = isError && !InputSanitizer.isValidItemName(currentItemName),
          supportingText = if (isError && !InputSanitizer.isValidItemName(currentItemName)) {
            { Text("Enter valid item name (2–${InputSanitizer.MAX_ITEM_NAME_LEN} chars)", color = Color(0xFFD93025)) }
          } else null,
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("tab_item_name_input")
        )

        // Inline suggestions list: strictly placed below the input field and perfectly aligned
        if (showSuggestions && filteredSuggestions.isNotEmpty()) {
          Spacer(modifier = Modifier.height(4.dp))
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            border = BorderStroke(1.dp, OrderGreen.copy(alpha = 0.5f)),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 200.dp)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
            ) {
              filteredSuggestions.forEach { suggestion ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                      currentItemName = suggestion
                      showSuggestions = false
                      isError = false
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    painter = painterResource(id = R.drawable.ic_receipt),
                    contentDescription = null,
                    tint = OrderGreen,
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(10.dp))
                  Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                }
                HorizontalDivider(
                  color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                  thickness = 0.5.dp
                )
              }
            }
          }
        }


        Spacer(modifier = Modifier.height(12.dp))

        // Mandatory Notes / Brand / Size
        OutlinedTextField(
          value = currentNotes,
          onValueChange = { raw ->
            val sanitized = InputSanitizer.sanitizeOnType(raw, InputSanitizer.MAX_NOTES_LEN, allowNewlines = true)
            currentNotes = sanitized
            if (sanitized.isNotBlank()) isNotesError = false
          },
          label = { Text(strings.orderNoteLabel) },
          placeholder = { Text(strings.notePlaceholder) },
          isError = isNotesError && !InputSanitizer.isValidNotes(currentNotes),
          supportingText = if (isNotesError && !InputSanitizer.isValidNotes(currentNotes)) {
            { Text(strings.noteRequiredError) }
          } else null,
          maxLines = 2,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("tab_item_notes_input")
        )

        if (uiState.coinFeatureEnabled) {
          Spacer(modifier = Modifier.height(12.dp))

          // Product Web Link
          OutlinedTextField(
            value = currentProductUrl,
            onValueChange = { rawInput ->
              val input = InputSanitizer.sanitizeOnType(rawInput, InputSanitizer.MAX_URL_LEN)
              if (input.isNotBlank() && uiState.coins < 15) {
                Toast.makeText(context, String.format(strings.linkPostErrorCoins, uiState.coins), Toast.LENGTH_LONG).show()
                currentProductUrl = ""
                isUrlError = false
              } else {
                val extracted = UrlSecurityHelper.extractUrlFromText(input)
                currentProductUrl = extracted ?: input
                isUrlError = false
                if (currentProductUrl.isNotBlank()) {
                  val info = UrlSecurityHelper.extractProductInfo(currentProductUrl)
                  if (info != null && currentItemName.isBlank()) {
                    currentItemName = InputSanitizer.sanitizeItemName(info.detectedName)
                    isError = false
                  }
                }
              }
            },
            label = { Text(strings.productUrlLabel) },
            placeholder = { Text(strings.productUrlPlaceholder) },
            isError = isUrlError,
            supportingText = if (isUrlError) {
              { Text("Enter a valid public https:// product link or leave blank", color = Color(0xFFD93025)) }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("tab_item_product_url_input")
          )

          // Live Extracted Product Info preview
          if (currentProductUrl.isNotBlank()) {
            val extractedInfo = remember(currentProductUrl) {
              UrlSecurityHelper.extractProductInfo(currentProductUrl)
            }
            if (extractedInfo != null) {
              Spacer(modifier = Modifier.height(12.dp))
              Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Box(
                    modifier = Modifier
                      .size(52.dp)
                      .clip(RoundedCornerShape(8.dp))
                      .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                  ) {
                    if (isImageLoading) {
                      ShimmerBox(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(8.dp)
                      )
                    } else if (currentImageUrl.isNotBlank()) {
                      AsyncImage(
                        model = currentImageUrl,
                        contentDescription = extractedInfo.detectedName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                      )
                    } else {
                      Icon(
                        painter = painterResource(id = R.drawable.ic_receipt),
                        contentDescription = null,
                        tint = OrderGreen,
                        modifier = Modifier.size(24.dp)
                      )
                    }
                  }

                  Spacer(modifier = Modifier.width(12.dp))

                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = extractedInfo.detectedName,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold,
                      color = OrderGreen,
                      maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                      text = if (isImageLoading) "Searching product picture... ⏳"
                             else if (currentImageUrl.isNotBlank()) "Photo loaded • ${extractedInfo.domain} ✅"
                             else "Store: ${extractedInfo.domain}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // + Add Another Item button
        OutlinedButton(
          onClick = {
            if (itemList.size >= InputSanitizer.MAX_ITEMS_PER_ORDER) {
              Toast.makeText(
                context,
                "Maximum ${InputSanitizer.MAX_ITEMS_PER_ORDER} items allowed per order.",
                Toast.LENGTH_SHORT
              ).show()
              return@OutlinedButton
            }
            val extractedInfo = UrlSecurityHelper.extractProductInfo(currentProductUrl)
            val rawName = currentItemName.ifBlank { extractedInfo?.detectedName ?: "" }
            val effectiveName = InputSanitizer.sanitizeItemName(rawName)
            val cleanNotes = InputSanitizer.sanitizeNotes(currentNotes)
            val sanitizedUrl = if (currentProductUrl.isBlank()) "" else (UrlSecurityHelper.sanitizeUrl(currentProductUrl) ?: "")

            if (!InputSanitizer.isValidItemName(effectiveName)) {
              isError = true
            }
            if (!InputSanitizer.isValidNotes(cleanNotes)) {
              isNotesError = true
            }
            if (currentProductUrl.isNotBlank() && sanitizedUrl.isBlank()) {
              isUrlError = true
            }
            if (!InputSanitizer.isValidItemName(effectiveName) ||
              !InputSanitizer.isValidNotes(cleanNotes) ||
              (currentProductUrl.isNotBlank() && sanitizedUrl.isBlank())
            ) {
              if (InputSanitizer.isValidItemName(effectiveName) && !InputSanitizer.isValidNotes(cleanNotes)) {
                Toast.makeText(context, strings.noteRequiredError, Toast.LENGTH_SHORT).show()
              }
              return@OutlinedButton
            }
            ItemCatalogStore.saveCustomItem(context, effectiveName)
            catalogVersion++
            itemList.add(
              OrderItemDetail(
                name = effectiveName,
                quantity = currentQuantity.coerceIn(1, InputSanitizer.MAX_ITEM_QUANTITY),
                notes = cleanNotes,
                productUrl = sanitizedUrl,
                imageUrl = UrlSecurityHelper.sanitizeUrl(currentImageUrl) ?: ""
              )
            )
            currentItemName = ""
            currentQuantity = 1
            currentNotes = ""
            currentProductUrl = ""
            currentImageUrl = ""
            isError = false
            isNotesError = false
            isUrlError = false
          },
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, OrderGreen),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = OrderGreen),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("tab_add_item_to_list_button")
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(strings.addAnotherItem, fontWeight = FontWeight.Bold)
        }
      }
    }

    // Invoice Item List Preview (if items were added to list)
    if (itemList.isNotEmpty()) {
      Spacer(modifier = Modifier.height(16.dp))

      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = "Invoice Summary (${itemList.size} items added):",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )

          Spacer(modifier = Modifier.height(10.dp))

          itemList.forEachIndexed { index, item ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              if (item.imageUrl.isNotBlank()) {
                AsyncImage(
                  model = item.imageUrl,
                  contentDescription = item.name,
                  modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(6.dp)),
                  contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(10.dp))
              }

              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = item.name,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold
                )
                if (item.notes.isNotBlank()) {
                  Text(
                    text = item.notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }

              IconButton(
                onClick = { itemList.removeAt(index) },
                modifier = Modifier.size(28.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Remove item",
                  tint = Color.Gray,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
            Spacer(modifier = Modifier.height(6.dp))
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Primary Send Order Button - Always visible from the start
    Button(
      onClick = {
        if (isSubmitting || uiState.isSendingOrder) return@Button
        val finalItems = itemList.toMutableList()
        val extractedInfo = UrlSecurityHelper.extractProductInfo(currentProductUrl)
        val rawName = currentItemName.ifBlank { extractedInfo?.detectedName ?: "" }
        val effectiveName = InputSanitizer.sanitizeItemName(rawName)
        val cleanNotes = InputSanitizer.sanitizeNotes(currentNotes)
        val sanitizedUrl = if (currentProductUrl.isBlank()) "" else (UrlSecurityHelper.sanitizeUrl(currentProductUrl) ?: "")
        val hasPendingFormInput = effectiveName.isNotBlank() || currentNotes.isNotBlank() || currentProductUrl.isNotBlank()

        if (hasPendingFormInput || finalItems.isEmpty()) {
          if (!InputSanitizer.isValidItemName(effectiveName)) {
            isError = true
          }
          if (!InputSanitizer.isValidNotes(cleanNotes)) {
            isNotesError = true
          }
          if (currentProductUrl.isNotBlank() && sanitizedUrl.isBlank()) {
            isUrlError = true
            Toast.makeText(context, "Invalid product URL", Toast.LENGTH_SHORT).show()
            return@Button
          }
          if (!InputSanitizer.isValidItemName(effectiveName)) {
            Toast.makeText(context, "Please enter a valid item name", Toast.LENGTH_SHORT).show()
            return@Button
          }
          if (!InputSanitizer.isValidNotes(cleanNotes)) {
            Toast.makeText(context, strings.noteRequiredError, Toast.LENGTH_SHORT).show()
            return@Button
          }
          if (finalItems.size >= InputSanitizer.MAX_ITEMS_PER_ORDER) {
            Toast.makeText(
              context,
              "Maximum ${InputSanitizer.MAX_ITEMS_PER_ORDER} items allowed per order.",
              Toast.LENGTH_SHORT
            ).show()
            return@Button
          }
          ItemCatalogStore.saveCustomItem(context, effectiveName)
          finalItems.add(
            OrderItemDetail(
              name = effectiveName,
              quantity = currentQuantity.coerceIn(1, InputSanitizer.MAX_ITEM_QUANTITY),
              notes = cleanNotes,
              productUrl = sanitizedUrl,
              imageUrl = UrlSecurityHelper.sanitizeUrl(currentImageUrl) ?: ""
            )
          )
        }
        if (finalItems.isEmpty()) {
          isError = true
          isNotesError = true
          Toast.makeText(context, "Please enter an item name", Toast.LENGTH_SHORT).show()
          return@Button
        }
        if (!uiState.isPartnerConnected) {
          Toast.makeText(context, strings.emptyPartnerNotConnected, Toast.LENGTH_LONG).show()
          return@Button
        }
        // Save all items into persistent catalog for future searches
        finalItems.forEach {
          ItemCatalogStore.saveCustomItem(context, it.name)
        }
        catalogVersion++
        isSubmitting = true
        onSendOrder(finalItems) {
          itemList.clear()
          currentItemName = ""
          currentQuantity = 1
          currentNotes = ""
          currentProductUrl = ""
          currentImageUrl = ""
          isError = false
          isNotesError = false
          isUrlError = false
          isSubmitting = false
        }
      },
      enabled = !isSubmitting && !uiState.isSendingOrder && !uiState.isWaitingOfflineToSendOrder,
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = OrderGreen,
        contentColor = Color.White,
        disabledContainerColor = OrderGreen.copy(alpha = 0.85f),
        disabledContentColor = Color.White
      ),
      modifier = Modifier
        .fillMaxWidth()
        .height(50.dp)
        .testTag("tab_submit_order_invoice_button")
    ) {
      val showButtonLoading = isSubmitting || uiState.isSendingOrder || uiState.isWaitingOfflineToSendOrder
      val totalCount = itemList.size + if (currentItemName.isNotBlank()) 1 else 0
      if (showButtonLoading) {
        CircularProgressIndicator(
          modifier = Modifier
            .size(18.dp)
            .testTag("send_button_loading_indicator"),
          color = Color.White,
          strokeWidth = 2.2.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
          text = if (totalCount > 1) String.format(strings.sendOrderCount, totalCount) else strings.sendOrder,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.titleMedium
        )
      } else {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = if (totalCount > 1) String.format(strings.sendOrderCount, totalCount) else strings.sendOrder,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.titleMedium
        )
      }
    }

    Spacer(modifier = Modifier.height(24.dp))
  }
}
