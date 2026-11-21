package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.viewmodel.compose.viewModel
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.repository.OrderRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.signOut
import com.example.ui.order.MainOrderScreen
import com.example.ui.order.OrderViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.NotificationHelper
import com.example.util.configureAppCheck
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.example.service.NotificationSyncManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val integrityValid = com.example.util.AppIntegrityGuard.verifyAppIntegrity(this)
    if (integrityValid) {
      try {
        com.example.util.SecureFirebaseVault.ensureFirebaseInitialized(this)
      } catch (e: Exception) {
        android.util.Log.e("MainActivity", "FirebaseApp initialization failed", e)
      }

      // Configure App Check before setContent
      try {
        configureAppCheck(this, intent)
      } catch (e: Exception) {
        android.util.Log.w("MainActivity", "AppCheck config failed: ${e.message}")
      }
    }

    enableEdgeToEdge(
      statusBarStyle = androidx.activity.SystemBarStyle.light(
        android.graphics.Color.TRANSPARENT,
        android.graphics.Color.TRANSPARENT
      ),
      navigationBarStyle = androidx.activity.SystemBarStyle.light(
        android.graphics.Color.TRANSPARENT,
        android.graphics.Color.TRANSPARENT
      )
    )
    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = true
      isAppearanceLightNavigationBars = true
    }
    setContent {
      MyApplicationTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          if (integrityValid) {
            PaircartApp()
          } else {
            IntegrityBlockedScreen()
          }
        }
      }
    }
  }
}

@Composable
private fun IntegrityBlockedScreen() {
  androidx.compose.foundation.layout.Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    contentAlignment = androidx.compose.ui.Alignment.Center
  ) {
    androidx.compose.material3.Text(
      text = "App verification failed. Please install the official Paircart release.",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.error,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
  }
}

@Composable
fun PaircartApp() {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val appContext = context.applicationContext

  CompositionLocalProvider(
    com.example.util.LocalAppStrings provides com.example.util.EnglishStrings
  ) {
    val credentialManager = remember(context) {
      try {
        CredentialManager.create(context)
      } catch (e: Throwable) {
        null
      }
    }
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }
    var closedNoticeMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
      NotificationHelper.createNotificationChannel(context)
      val listener = FirebaseAuth.AuthStateListener { auth ->
        currentUser = auth.currentUser
      }
      Firebase.auth.addAuthStateListener(listener)
      onDispose {
        Firebase.auth.removeAuthStateListener(listener)
      }
    }

    // Request Notification Permission for Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
      ) { isGranted ->
        if (isGranted) {
          NotificationHelper.createNotificationChannel(appContext)
          if (Firebase.auth.currentUser != null) {
            NotificationSyncManager.scheduleBackgroundSync(appContext)
          }
        }
      }
      androidx.compose.runtime.LaunchedEffect(Unit) {
        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
      }
    }

    // Schedule background notification polling (without persistent foreground service) when logged in
    androidx.compose.runtime.LaunchedEffect(currentUser) {
      if (currentUser != null) {
        NotificationSyncManager.scheduleBackgroundSync(appContext)
      } else {
        NotificationSyncManager.cancelBackgroundSync(appContext)
      }
    }

    val user = currentUser
    if (user == null) {
      AuthScreen(
        closedNoticeMessage = closedNoticeMessage,
        onClearClosedNotice = { closedNoticeMessage = null },
        onAuthSuccess = {
          closedNoticeMessage = null
          currentUser = Firebase.auth.currentUser
        }
      )
    } else {
      val repository = remember(user.uid) { OrderRepository(appContext) }
      val orderViewModel: OrderViewModel = viewModel(
        key = user.uid,
        factory = OrderViewModel.Factory(repository, appContext)
      )

      androidx.compose.runtime.LaunchedEffect(user.uid) {
        orderViewModel.onSessionResumed()
      }

      val performSignOut: () -> Unit = {
        NotificationSyncManager.cancelBackgroundSync(appContext)
        NotificationHelper.cancelShoppingAlarm(appContext)
        orderViewModel.onSignOut()
        signOut(
          context = context,
          credentialManager = credentialManager,
          onSignOutComplete = {
            currentUser = null
          },
          scope = coroutineScope
        )
      }

      MainOrderScreen(
        viewModel = orderViewModel,
        onSignOut = {
          closedNoticeMessage = null
          performSignOut()
        },
        onForcedClosedSignOut = {
          closedNoticeMessage = "App is currently closed by Admin. Only Admin can sign in right now."
          performSignOut()
        }
      )
    }
  }
}
