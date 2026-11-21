package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.R
import com.example.util.AdminConfig
import com.example.util.SecureFirebaseVault
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AuthManager"

private fun Context.findActivity(): Activity? {
  var current = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}

private fun getFirestoreDb(context: Context): FirebaseFirestore {
  return try {
    val dbId = context.applicationContext.getString(R.string.firestore_database_id)
    FirebaseFirestore.getInstance(dbId)
  } catch (e: Throwable) {
    FirebaseFirestore.getInstance()
  }
}

fun observeGlobalAppEnabled(context: Context): Flow<Boolean> = callbackFlow {
  var registration: com.google.firebase.firestore.ListenerRegistration? = null
  try {
    SecureFirebaseVault.ensureFirebaseInitialized(context)
    val db = getFirestoreDb(context)
    registration = db.collection("app_config").document("global")
      .addSnapshotListener { snap, err ->
        if (err != null) {
          Log.w(TAG, "observeGlobalAppEnabled listener warning: ${err.message}")
          return@addSnapshotListener
        }
        val enabled = snap?.getBoolean("appEnabled") ?: true
        trySend(enabled)
      }
  } catch (e: Throwable) {
    Log.w(TAG, "observeGlobalAppEnabled setup failed: ${e.message}")
    trySend(true)
  }
  awaitClose {
    registration?.remove()
  }
}

suspend fun isAppEnabledForUser(context: Context, email: String?): Boolean {
  if (AdminConfig.isAdminEmail(email)) {
    return true
  }
  return withContext(Dispatchers.IO) {
    try {
      SecureFirebaseVault.ensureFirebaseInitialized(context)
      val db = getFirestoreDb(context)
      val docRef = db.collection("app_config").document("global")
      val serverSnap = withTimeoutOrNull(4000L) {
        try {
          docRef.get(Source.SERVER).await()
        } catch (e: Throwable) {
          Log.w(TAG, "isAppEnabledForUser server fetch failed: ${e.message}")
          null
        }
      }
      if (serverSnap != null) {
        return@withContext serverSnap.getBoolean("appEnabled") ?: true
      }
      val cacheSnap = try {
        docRef.get(Source.CACHE).await()
      } catch (_: Throwable) {
        null
      }
      cacheSnap?.getBoolean("appEnabled") ?: true
    } catch (e: Throwable) {
      Log.w(TAG, "isAppEnabledForUser check error: ${e.message}")
      true
    }
  }
}

private suspend fun clearAuthSession(credentialManager: CredentialManager?) {
  withContext(NonCancellable) {
    try {
      Firebase.auth.signOut()
    } catch (e: Throwable) {
      Log.e(TAG, "clearAuthSession: Firebase signOut error", e)
    }
    try {
      credentialManager?.clearCredentialState(ClearCredentialStateRequest())
    } catch (e: Throwable) {
      Log.w(TAG, "clearAuthSession: Credential state clear warning", e)
    }
  }
}

fun attemptAutoSignIn(
  context: Context,
  credentialManager: CredentialManager?,
  onAuthSuccess: () -> Unit,
  onUnauthenticated: () -> Unit,
  scope: CoroutineScope,
  onAppClosedByAdmin: () -> Unit = onUnauthenticated
) {
  Log.d(TAG, "attemptAutoSignIn: starting")
  try {
    SecureFirebaseVault.ensureFirebaseInitialized(context)
    val user = Firebase.auth.currentUser
    if (user != null) {
      Log.d(TAG, "attemptAutoSignIn: User already signed in: ${user.uid}, checking appEnabled")
      scope.launch {
        if (isAppEnabledForUser(context, user.email)) {
          onAuthSuccess()
        } else {
          Log.w(TAG, "attemptAutoSignIn: App is closed by Admin; signing out existing user")
          clearAuthSession(credentialManager)
          onAppClosedByAdmin()
        }
      }
      return
    }
    if (credentialManager == null) {
      Log.w(TAG, "attemptAutoSignIn: CredentialManager is null")
      onUnauthenticated()
      return
    }
    val clientId = SecureFirebaseVault.getWebClientId()

    Log.d(TAG, "attemptAutoSignIn: requesting credentials")
    val googleIdOption = GetGoogleIdOption.Builder()
      .setFilterByAuthorizedAccounts(true)
      .setServerClientId(clientId)
      .setAutoSelectEnabled(true)
      .build()

    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    scope.launch {
      try {
        val result = credentialManager.getCredential(context, request)
        val credential = result.credential
        Log.d(TAG, "attemptAutoSignIn: credential received: ${credential.type}")
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
          val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
          if (googleIdToken.isNullOrBlank()) {
            Log.e(TAG, "attemptAutoSignIn: googleIdToken is null or blank")
            onUnauthenticated()
            return@launch
          }
          val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
          Firebase.auth.signInWithCredential(authCredential).await()
          val signedInEmail = Firebase.auth.currentUser?.email
          if (!isAppEnabledForUser(context, signedInEmail)) {
            Log.w(TAG, "attemptAutoSignIn: App is closed by Admin; blocking non-admin login")
            clearAuthSession(credentialManager)
            onAppClosedByAdmin()
            return@launch
          }
          Log.d(TAG, "attemptAutoSignIn: Firebase sign-in successful")
          onAuthSuccess()
        } else {
          Log.d(TAG, "attemptAutoSignIn: unexpected credential type")
          onUnauthenticated()
        }
      } catch (e: GetCredentialCancellationException) {
        Log.d(TAG, "attemptAutoSignIn: flow cancelled: ${e.message}")
        onUnauthenticated()
      } catch (e: androidx.credentials.exceptions.NoCredentialException) {
        Log.d(TAG, "attemptAutoSignIn: No credentials available for auto sign-in")
        onUnauthenticated()
      } catch (e: GetCredentialException) {
        Log.e(TAG, "attemptAutoSignIn: GetCredentialException: ${e.message}", e)
        onUnauthenticated()
      } catch (e: Throwable) {
        Log.e(TAG, "attemptAutoSignIn: Throwable during credential flow: ${e.message}", e)
        onUnauthenticated()
      }
    }
  } catch (e: Throwable) {
    Log.e(TAG, "attemptAutoSignIn: unexpected top-level exception: ${e.message}", e)
    onUnauthenticated()
  }
}

fun onGoogleSignInClicked(
  context: Context,
  credentialManager: CredentialManager?,
  onAuthSuccess: () -> Unit,
  onAuthError: (String) -> Unit,
  scope: CoroutineScope,
  onAuthCancelled: () -> Unit = {}
) {
  Log.d(TAG, "onGoogleSignInClicked: starting")
  try {
    SecureFirebaseVault.ensureFirebaseInitialized(context)
    if (credentialManager == null) {
      Log.e(TAG, "onGoogleSignInClicked: CredentialManager is null")
      onAuthError("CredentialManager unavailable on this device.")
      return
    }
    val clientId = SecureFirebaseVault.getWebClientId()

    Log.d(TAG, "onGoogleSignInClicked: launching sign-in request")
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

    scope.launch {
      try {
        val activity = context.findActivity()
        if (activity == null) {
          Log.e(TAG, "onGoogleSignInClicked: activity is null")
          onAuthError("Unable to locate Activity for sign-in prompt.")
          return@launch
        }
        val result = credentialManager.getCredential(activity, request)
        val credential = result.credential
        Log.d(TAG, "onGoogleSignInClicked: credential received: ${credential.type}")
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
          val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
          if (googleIdToken.isNullOrBlank()) {
            Log.e(TAG, "onGoogleSignInClicked: googleIdToken is null or blank")
            onAuthError("Auth token retrieval failed.")
            return@launch
          }
          val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
          Firebase.auth.signInWithCredential(authCredential).await()
          val signedInEmail = Firebase.auth.currentUser?.email
          if (!isAppEnabledForUser(context, signedInEmail)) {
            Log.w(TAG, "onGoogleSignInClicked: App is closed by Admin; blocking non-admin login")
            clearAuthSession(credentialManager)
            onAuthError("App is currently closed by Admin. Only Admin can sign in right now.")
            return@launch
          }
          Log.d(TAG, "onGoogleSignInClicked: Firebase sign-in successful")
          onAuthSuccess()
        } else {
          Log.e(TAG, "onGoogleSignInClicked: unexpected credential type: ${credential.type}")
          onAuthError("Unexpected credential type received.")
        }
      } catch (e: GetCredentialCancellationException) {
        Log.w(TAG, "onGoogleSignInClicked: flow cancelled: ${e.message}")
        onAuthCancelled()
      } catch (e: GetCredentialException) {
        Log.e(TAG, "onGoogleSignInClicked: GetCredentialException: ${e.message}", e)
        onAuthError("Sign-in failed: ${e.localizedMessage}")
      } catch (e: Throwable) {
        Log.e(TAG, "onGoogleSignInClicked: Google Sign-In failed", e)
        onAuthError(e.localizedMessage ?: "Sign in failed")
      }
    }
  } catch (e: Throwable) {
    Log.e(TAG, "onGoogleSignInClicked: unexpected top-level exception", e)
    onAuthError(e.localizedMessage ?: "Sign in error")
  }
}

fun signOut(
  context: Context,
  credentialManager: CredentialManager?,
  onSignOutComplete: () -> Unit,
  scope: CoroutineScope
) {
  Log.d(TAG, "signOut: starting")
  scope.launch {
    try {
      clearAuthSession(credentialManager)
    } finally {
      Log.d(TAG, "signOut: completion callback")
      onSignOutComplete()
    }
  }
}
