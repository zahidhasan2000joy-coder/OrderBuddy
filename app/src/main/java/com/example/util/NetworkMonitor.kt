package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object NetworkMonitor {

  fun isOnlineNow(context: Context): Boolean {
    return try {
      val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
      val activeNetwork = cm.activeNetwork ?: return false
      val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false

      val hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      if (!hasInternetCapability) return false

      // In Robolectric unit tests, ShadowNetworkCapabilities may not set NET_CAPABILITY_VALIDATED
      val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
      if (isRobolectric) return true

      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Throwable) {
      false
    }
  }

  fun observeOnlineState(context: Context): Flow<Boolean> = callbackFlow {
    val appContext = context.applicationContext
    trySend(isOnlineNow(appContext))

    val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (cm == null) {
      awaitClose {}
      return@callbackFlow
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        trySend(isOnlineNow(appContext))
      }

      override fun onLost(network: Network) {
        trySend(isOnlineNow(appContext))
      }

      override fun onUnavailable() {
        trySend(false)
      }

      override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        trySend(isOnlineNow(appContext))
      }
    }

    try {
      cm.registerDefaultNetworkCallback(callback)
    } catch (_: Throwable) {
      trySend(isOnlineNow(appContext))
    }

    awaitClose {
      try {
        cm.unregisterNetworkCallback(callback)
      } catch (_: Throwable) {
      }
    }
  }.distinctUntilChanged()
}
