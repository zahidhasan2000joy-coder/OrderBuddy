package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"
private val isAppCheckConfigured = java.util.concurrent.atomic.AtomicBoolean(false)

fun configureAppCheck(context: Context, intent: Intent?) {
  if (isAppCheckConfigured.get()) return
  try {
    SecureFirebaseVault.ensureFirebaseInitialized(context)

    var installed = false
    val resId = context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
    if (resId != 0) {
      val siteKey = context.getString(resId)
      if (siteKey.isNotBlank()) {
        try {
          FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance(siteKey)
          )
          installed = true
          Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
        } catch (e: Throwable) {
          Log.w(TAG, "Failed to install RecaptchaAppCheckProviderFactory: ${e.message}")
        }
      }
    }

    if (!installed) {
      try {
        val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
        if (!debugToken.isNullOrEmpty()) {
          val persistenceKey = FirebaseApp.getInstance().persistenceKey
          val prefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
          )
          prefs.edit()
            .putString(DEBUG_SECRET_KEY, debugToken)
            .commit()
        }

        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
          DebugAppCheckProviderFactory.getInstance()
        )
        installed = true
        Log.d(TAG, "DebugAppCheckProviderFactory installed as fallback.")
      } catch (e: Throwable) {
        Log.w(TAG, "Failed to install DebugAppCheckProviderFactory: ${e.message}")
      }
    }
    if (installed) {
      isAppCheckConfigured.set(true)
    }
  } catch (e: Throwable) {
    Log.e(TAG, "Failed to configure App Check", e)
  }
}
