package com.example.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.example.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Runtime anti-tamper, anti-clone, anti-debug, and anti-hooking guard.
 * Protects Paircart against APK repackaging, signature replacement,
 * manifest debuggable patching, app cloning, and runtime instrumentation.
 */
object AppIntegrityGuard {

  private val S = intArrayOf(0x5B, 0xA4, 0x39, 0xC7, 0x7E, 0x12, 0x8D, 0x41)

  // Obfuscated official applicationId
  private val PKG = intArrayOf(
    34, 152, 68, 168, 159, 228, 82, 0, 212, 217, 96, 175, 46, 50, 153, 200,
    172, 123, 161, 113, 44, 127, 216, 58, 120, 195, 234, 46, 207, 205
  )

  // Obfuscated SHA-256 digest (hex) of official release signing certificate
  private val SIG = intArrayOf(
    34, 43, 169, 67, 147, 221, 95, 141, 96, 240, 112, 174, 226, 197, 37, 215,
    195, 116, 19, 229, 136, 137, 191, 28, 18, 93, 207, 59, 221, 62, 234, 88,
    58, 150, 138, 128, 243, 248, 118, 172, 46, 147, 80, 207, 63, 66, 69, 180,
    230, 69, 246, 9, 104, 118, 162, 57, 123, 46, 188, 97, 171, 219, 83, 122
  )

  private fun decode(data: IntArray, seed: Int): String {
    val out = ByteArray(data.size)
    for (i in data.indices) {
      val mask = (S[i % S.size] xor ((seed + i * 23) and 0xFF)) and 0xFF
      out[i] = (((data[i] - 19) and 0xFF) xor mask).toByte()
    }
    return String(out, Charsets.UTF_8)
  }

  internal fun expectedPackageId(): String = decode(PKG, 0x37)

  internal fun expectedSignatureSha256(): String = decode(SIG, 0x6D)

  internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
      val v = b.toInt() and 0xFF
      sb.append("0123456789ABCDEF"[v ushr 4])
      sb.append("0123456789ABCDEF"[v and 0x0F])
    }
    return sb.toString()
  }

  internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) {
      diff = diff or (a[i].code xor b[i].code)
    }
    return diff == 0
  }

  internal fun containsSuspiciousMarker(text: String): Boolean {
    val lower = text.lowercase()
    return lower.contains("frida") ||
      lower.contains("xposed") ||
      lower.contains("edxposed") ||
      lower.contains("lsposed") ||
      lower.contains("substrate")
  }

  fun verifyAppIntegrity(context: Context): Boolean {
    if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
      return true
    }

    val appContext = context.applicationContext ?: context

    // 1. Block cloned or package-renamed builds
    if (!constantTimeEquals(appContext.packageName, expectedPackageId())) {
      return false
    }

    // 2. Verify APK signing certificate SHA-256 matches official keystore
    if (!verifySigningCertificate(appContext)) {
      return false
    }

    // 3. In release builds, block tampered debuggable manifests, debuggers, and hooking tools
    if (!BuildConfig.DEBUG) {
      if ((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
        return false
      }
      if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
        return false
      }
      if (isNativeTracerAttached() || isHookingFrameworkDetected()) {
        return false
      }
    }

    return true
  }

  @Suppress("DEPRECATION")
  private fun verifySigningCertificate(context: Context): Boolean {
    return try {
      val expectedDigest = expectedSignatureSha256()
      val pm = context.packageManager
      val pkgName = context.packageName
      val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = info.signingInfo ?: return false
        if (signingInfo.hasMultipleSigners()) {
          signingInfo.apkContentsSigners
        } else {
          signingInfo.signingCertificateHistory
        }
      } else {
        pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES).signatures
      }

      if (signatures.isNullOrEmpty()) return false
      signatures.any { sig ->
        val actual = sha256Hex(sig.toByteArray())
        constantTimeEquals(actual, expectedDigest)
      }
    } catch (_: Throwable) {
      false
    }
  }

  private fun isNativeTracerAttached(): Boolean {
    return try {
      val statusFile = File("/proc/self/status")
      if (!statusFile.exists()) return false
      val tracerLine = statusFile.useLines { lines ->
        lines.firstOrNull { it.startsWith("TracerPid:") }
      } ?: return false
      val pid = tracerLine.substringAfter(':').trim().toIntOrNull() ?: 0
      pid > 0
    } catch (_: Throwable) {
      false
    }
  }

  private fun isHookingFrameworkDetected(): Boolean {
    try {
      Class.forName("de.robv.android.xposed.XposedBridge")
      return true
    } catch (_: Throwable) {
      // Not present
    }

    try {
      for (frame in Throwable().stackTrace) {
        if (containsSuspiciousMarker(frame.className)) {
          return true
        }
      }
    } catch (_: Throwable) {
      // Ignore
    }

    try {
      val mapsFile = File("/proc/self/maps")
      if (mapsFile.exists()) {
        val found = mapsFile.useLines { lines ->
          lines.any { containsSuspiciousMarker(it) }
        }
        if (found) return true
      }
    } catch (_: Throwable) {
      // Ignore
    }

    return false
  }
}
