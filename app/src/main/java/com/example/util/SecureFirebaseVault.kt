package com.example.util

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Runtime-reconstructed Firebase configuration vault.
 * Keeps client configuration out of plain-text resources.arsc and DEX string literals.
 */
object SecureFirebaseVault {

  private val S = intArrayOf(0xA3, 0x1F, 0x6D, 0x94, 0x2B, 0xE5, 0x48, 0xC1)

  private val K_A = intArrayOf(
    197, 74, 120, 133, 243, 64, 214, 119, 68, 235, 107, 6, 105, 141, 67, 186,
    167, 99, 196, 138, 221, 23, 227, 31, 17, 132, 51, 12, 126, 226, 78, 215,
    162, 3, 142, 77, 8, 111, 169
  )

  private val K_B = intArrayOf(
    187, 117, 15, 208, 160, 84, 230, 84, 107, 11, 192, 104, 31, 209, 101, 168,
    142, 51, 126, 145, 223, 45, 205, 68, 81, 208, 188, 3, 94, 7, 83, 249,
    170, 81, 52, 55, 251, 61, 251, 56, 125, 231, 108, 237, 110
  )

  private val K_C = intArrayOf(
    176, 15, 151, 48, 249, 69, 248, 133, 110, 137, 29, 236, 138, 220, 118, 151,
    241, 177, 217, 25, 175, 30, 174, 44, 121, 70
  )

  private val K_D = intArrayOf(
    154, 45, 117, 210, 215, 39, 150, 115, 108, 171, 255, 6, 104, 186, 24, 177,
    215, 83, 59, 55, 145, 128, 208, 46, 127, 232, 77, 243, 116, 174, 119, 8,
    184, 35, 144, 136, 255, 65, 2, 77, 66, 133, 93, 222, 123, 224
  )

  private val K_E = intArrayOf(240, 178, 211, 16, 186, 35, 179, 42, 111, 58, 84, 141)

  private val K_F = intArrayOf(
    201, 37, 18, 211, 119, 96, 242, 93, 58, 237, 145, 82, 9, 171, 62, 165,
    9, 117, 133, 154, 165, 31, 142, 84, 24, 177, 240, 15, 97, 149, 23, 218,
    172, 56, 50, 115, 229, 63, 151, 118, 14, 213, 177, 252, 104, 237, 34, 9,
    154, 16, 208, 111, 243, 70, 248, 133, 45, 170, 35, 245, 128, 202, 120, 255,
    198, 18, 160, 78, 196, 82, 0, 86
  )

  private fun decode(data: IntArray, seed: Int): String {
    val out = ByteArray(data.size)
    for (i in data.indices) {
      val mask = (S[i % S.size] xor ((seed + i * 17) and 0xFF)) and 0xFF
      out[i] = (((data[i] - 13) and 0xFF) xor mask).toByte()
    }
    return String(out, Charsets.UTF_8)
  }

  fun buildFirebaseOptions(): FirebaseOptions {
    return FirebaseOptions.Builder()
      .setApiKey(decode(K_A, 0x5A))
      .setApplicationId(decode(K_B, 0x3C))
      .setProjectId(decode(K_C, 0x67))
      .setStorageBucket(decode(K_D, 0x49))
      .setGcmSenderId(decode(K_E, 0x71))
      .build()
  }

  fun getWebClientId(): String = decode(K_F, 0x2E)

  @Synchronized
  fun ensureFirebaseInitialized(context: Context): FirebaseApp? {
    val appContext = context.applicationContext ?: context
    if (!AppIntegrityGuard.verifyAppIntegrity(appContext)) {
      return null
    }
    return try {
      val existing = FirebaseApp.getApps(appContext)
      if (existing.isNotEmpty()) {
        existing.first()
      } else {
        FirebaseApp.initializeApp(appContext, buildFirebaseOptions())
      }
    } catch (_: Throwable) {
      try {
        FirebaseApp.getApps(appContext).firstOrNull()
      } catch (_: Throwable) {
        null
      }
    }
  }
}
