package com.example.ui.auth

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.ui.components.FacebookSlimShimmerBar
import com.example.util.NetworkMonitor
import kotlinx.coroutines.launch

private const val TAG = "AuthScreen"

@Composable
fun AuthScreen(
  onAuthSuccess: () -> Unit,
  closedNoticeMessage: String? = null,
  onClearClosedNotice: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val credentialManager = remember(context) {
    try {
      Log.d(TAG, "AuthScreen: creating CredentialManager")
      CredentialManager.create(context)
    } catch (e: Throwable) {
      Log.e(TAG, "AuthScreen: Failed to create CredentialManager", e)
      null
    }
  }
  val snackbarHostState = remember { SnackbarHostState() }

  var isAttemptingAutoSignIn by remember { mutableStateOf(true) }
  var isInteractiveSigningIn by remember { mutableStateOf(false) }
  var blockedMessage by remember(closedNoticeMessage) { mutableStateOf(closedNoticeMessage) }
  val isOnline by remember(context) {
    NetworkMonitor.observeOnlineState(context)
  }.collectAsState(initial = NetworkMonitor.isOnlineNow(context))
  val isAppEnabled by remember(context) {
    observeGlobalAppEnabled(context)
  }.collectAsState(initial = true)

  LaunchedEffect(closedNoticeMessage) {
    if (!closedNoticeMessage.isNullOrBlank()) {
      blockedMessage = closedNoticeMessage
      snackbarHostState.showSnackbar(closedNoticeMessage)
      onClearClosedNotice()
    }
  }

  LaunchedEffect(isAppEnabled) {
    if (isAppEnabled) {
      blockedMessage = null
    }
  }

  LaunchedEffect(Unit) {
    Log.d(TAG, "AuthScreen: LaunchedEffect starting attemptAutoSignIn")
    attemptAutoSignIn(
      context = context,
      credentialManager = credentialManager,
      onAuthSuccess = {
        Log.d(TAG, "AuthScreen: auto sign-in success")
        onAuthSuccess()
      },
      onUnauthenticated = {
        Log.d(TAG, "AuthScreen: auto sign-in not authenticated")
        isAttemptingAutoSignIn = false
      },
      scope = coroutineScope,
      onAppClosedByAdmin = {
        Log.w(TAG, "AuthScreen: auto sign-in blocked because app is closed by Admin")
        isAttemptingAutoSignIn = false
        blockedMessage = "App is currently closed by Admin. Only Admin can sign in right now."
      }
    )
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    modifier = modifier.fillMaxSize()
  ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      // 1. Vibrant Modern Emerald & Teal Gradient Background
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color(0xFF065F46), // Deep Emerald
                Color(0xFF047857),
                Color(0xFF0F766E), // Teal
                Color(0xFF134E4A)  // Dark Teal
              )
            )
          )
      )

      // 3. Foreground Content
      if (isAttemptingAutoSignIn) {
        Column(
          modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Box(
            modifier = Modifier
              .size(88.dp)
              .clip(RoundedCornerShape(22.dp))
              .background(Color.White)
              .padding(8.dp),
            contentAlignment = Alignment.Center
          ) {
            Image(
              painter = painterResource(id = R.drawable.app_brand_logo),
              contentDescription = "Paircart Logo",
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize()
            )
          }
          Spacer(modifier = Modifier.height(18.dp))
          Text(
            text = if (isOnline) "Paircart" else "No internet connection",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(14.dp))
          Box(
            modifier = Modifier
              .width(160.dp)
              .clip(RoundedCornerShape(4.dp))
              .testTag("auto_signin_progress")
          ) {
            FacebookSlimShimmerBar(
              accentColor = if (isOnline) Color(0xFF4ADE80) else Color(0xFFFCA5A5)
            )
          }
        }
      } else {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          // Framed Brand Logo (First Logo)
          Box(
            modifier = Modifier
              .size(110.dp)
              .shadow(16.dp, RoundedCornerShape(26.dp))
              .clip(RoundedCornerShape(26.dp))
              .background(Color.White)
              .border(2.dp, Color.White.copy(alpha = 0.95f), RoundedCornerShape(26.dp))
              .padding(8.dp),
            contentAlignment = Alignment.Center
          ) {
            Image(
              painter = painterResource(id = R.drawable.app_brand_logo),
              contentDescription = "Paircart Logo",
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize()
            )
          }

          Spacer(modifier = Modifier.height(24.dp))

          Text(
            text = "Paircart",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
          )

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = "Send shopping lists and split expenses with your partner.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.widthIn(max = 380.dp)
          )

          Spacer(modifier = Modifier.height(28.dp))

          // Feature cards
          FeatureGlassCard(
            iconRes = R.drawable.ic_receipt,
            title = "Real-Time Order Lists",
            description = "Add groceries, meals, or daily essentials anytime."
          )

          Spacer(modifier = Modifier.height(12.dp))

          FeatureGlassCard(
            iconRes = R.drawable.ic_group,
            title = "50/50 Expense Tracking",
            description = "Team up with your partner to manage orders and settle bills."
          )

          Spacer(modifier = Modifier.height(24.dp))

          if (!isAppEnabled || !blockedMessage.isNullOrBlank()) {
            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(
                containerColor = Color(0xFF7F1D1D).copy(alpha = 0.92f)
              ),
              border = BorderStroke(1.dp, Color(0xFFFCA5A5).copy(alpha = 0.8f)),
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .padding(bottom = 16.dp)
                .testTag("app_closed_banner")
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.Lock,
                  contentDescription = "App Closed",
                  tint = Color(0xFFFEE2E2),
                  modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "App Temporarily Closed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = blockedMessage
                      ?: "User login is currently disabled by Admin. Only Admin can sign in right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFEE2E2)
                  )
                }
              }
            }
          }

          // Google Sign-In Button
          Button(
            onClick = {
              if (!NetworkMonitor.isOnlineNow(context)) {
                coroutineScope.launch {
                  snackbarHostState.showSnackbar("No internet connection. Please connect to the internet first.")
                }
                return@Button
              }
              Log.d(TAG, "AuthScreen: Sign-in button clicked")
              isInteractiveSigningIn = true
              onGoogleSignInClicked(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {
                  Log.d(TAG, "AuthScreen: interactive sign-in success")
                  isInteractiveSigningIn = false
                  blockedMessage = null
                  onAuthSuccess()
                },
                onAuthError = { errorMsg ->
                  Log.e(TAG, "AuthScreen: interactive sign-in error: $errorMsg")
                  isInteractiveSigningIn = false
                  if (errorMsg.contains("closed by Admin", ignoreCase = true)) {
                    blockedMessage = errorMsg
                  }
                  coroutineScope.launch {
                    snackbarHostState.showSnackbar(errorMsg)
                  }
                },
                scope = coroutineScope,
                onAuthCancelled = {
                  Log.d(TAG, "AuthScreen: interactive sign-in cancelled")
                  isInteractiveSigningIn = false
                }
              )
            },
            enabled = !isInteractiveSigningIn,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = Color.White
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 420.dp)
              .height(54.dp)
              .testTag("google_sign_in_button")
          ) {
            if (isInteractiveSigningIn) {
              CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = Color.White,
                strokeWidth = 2.5.dp
              )
            } else {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
              ) {
                Icon(
                  imageVector = Icons.Default.ShoppingCart,
                  contentDescription = null,
                  modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = if (isOnline) "Sign in with Google" else "Waiting for Internet...",
                  style = MaterialTheme.typography.titleMedium,
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

@Composable
private fun FeatureGlassCard(
  iconRes: Int,
  title: String,
  description: String,
  modifier: Modifier = Modifier
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(
      containerColor = Color.Black.copy(alpha = 0.45f)
    ),
    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
    modifier = modifier
      .fillMaxWidth()
      .widthIn(max = 420.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .background(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            shape = CircleShape
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          painter = painterResource(id = iconRes),
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(28.dp)
        )
      }

      Spacer(modifier = Modifier.width(16.dp))

      Column {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = Color.White
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.8f)
        )
      }
    }
  }
}
