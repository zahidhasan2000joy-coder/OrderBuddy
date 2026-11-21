package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ProfileImageHelper {

  const val TARGET_4K_DIMENSION = 2160
  private const val MAX_UPLOAD_DIMENSION = 2160
  private const val MAX_RAW_UPLOAD_BYTES = 145_000
  private const val DATA_URI_WEBP_PREFIX = "data:image/webp;base64,"
  private const val DATA_URI_JPEG_PREFIX = "data:image/jpeg;base64,"

  // Fast in-memory cache so avatars, dialogs, and fullscreen viewers reuse enhanced bitmaps in 0ms
  private val enhancedCache = LruCache<String, Bitmap>(8)

  private fun cacheKey(dataUri: String): String = "${dataUri.length}_${dataUri.hashCode()}"

  fun getCachedEnhancedBitmap(dataUri: String): Bitmap? {
    if (dataUri.isBlank()) return null
    val cached = enhancedCache.get(cacheKey(dataUri)) ?: return null
    return if (!cached.isRecycled) cached else null
  }

  suspend fun uriToCompressedDataUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
      val resolver = context.applicationContext.contentResolver
      val decoded: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(resolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          decoder.isMutableRequired = false
          val origW = info.size.width.coerceAtLeast(1)
          val origH = info.size.height.coerceAtLeast(1)
          val maxOrig = max(origW, origH)
          if (maxOrig > MAX_UPLOAD_DIMENSION) {
            val scale = MAX_UPLOAD_DIMENSION.toFloat() / maxOrig.toFloat()
            val targetW = max(1, (origW * scale).roundToInt())
            val targetH = max(1, (origH * scale).roundToInt())
            decoder.setTargetSize(targetW, targetH)
          }
        }
      } else {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
          BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return@withContext null

        var sampleSize = 1
        val maxOrig = max(boundsOpts.outWidth, boundsOpts.outHeight)
        while (maxOrig / (sampleSize * 2) >= MAX_UPLOAD_DIMENSION) {
          sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
          inSampleSize = sampleSize
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        resolver.openInputStream(uri)?.use { stream ->
          BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
      }

      if (decoded == null) return@withContext null
      try {
        compressBitmapToDataUri(decoded)
      } finally {
        decoded.recycle()
      }
    } catch (_: Throwable) {
      null
    }
  }

  fun compressBitmapToDataUri(source: Bitmap): String? {
    return try {
      val srcMax = max(source.width, source.height).coerceAtLeast(1)
      val dimensionSteps = listOf(2160, 1600, 1280, 1080, 840)
        .map { min(it, srcMax) }
        .distinct()
        .sortedDescending()

      val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
      } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
      }

      val webpResult = tryCompressWithFormat(
        source = source,
        dimensionSteps = dimensionSteps,
        format = webpFormat,
        dataUriPrefix = DATA_URI_WEBP_PREFIX
      )
      if (webpResult != null) return webpResult

      tryCompressWithFormat(
        source = source,
        dimensionSteps = dimensionSteps,
        format = Bitmap.CompressFormat.JPEG,
        dataUriPrefix = DATA_URI_JPEG_PREFIX
      )
    } catch (_: Throwable) {
      null
    }
  }

  private fun tryCompressWithFormat(
    source: Bitmap,
    dimensionSteps: List<Int>,
    format: Bitmap.CompressFormat,
    dataUriPrefix: String
  ): String? {
    val srcMax = max(source.width, source.height).coerceAtLeast(1)
    val baos = ByteArrayOutputStream()
    var bestSmallBytes: ByteArray? = null

    for ((index, targetMaxDim) in dimensionSteps.withIndex()) {
      val scale = min(1f, targetMaxDim.toFloat() / srcMax.toFloat())
      val targetW = max(1, (source.width * scale).roundToInt())
      val targetH = max(1, (source.height * scale).roundToInt())

      val workingBitmap = if (targetW != source.width || targetH != source.height) {
        Bitmap.createScaledBitmap(source, targetW, targetH, true)
      } else {
        source
      }

      try {
        val qualities = when (index) {
          0 -> listOf(92, 86, 80, 74)
          1 -> listOf(92, 86, 80, 74)
          2 -> listOf(90, 84, 78)
          else -> listOf(84, 76, 68)
        }

        for (q in qualities) {
          baos.reset()
          val ok = workingBitmap.compress(format, q, baos)
          if (!ok) return null
          val candidate = baos.toByteArray()
          if (candidate.isEmpty()) return null
          if (candidate.size <= MAX_RAW_UPLOAD_BYTES) {
            val encoded = Base64.encodeToString(candidate, Base64.NO_WRAP)
            val dataUri = "$dataUriPrefix$encoded"
            if (InputSanitizer.isValidProfilePhotoData(dataUri)) {
              return dataUri
            }
          }
          if (bestSmallBytes == null || candidate.size < bestSmallBytes.size) {
            bestSmallBytes = candidate
          }
        }
      } finally {
        if (workingBitmap !== source) {
          workingBitmap.recycle()
        }
      }
    }

    val fallback = bestSmallBytes ?: return null
    if (fallback.isEmpty() || fallback.size > MAX_RAW_UPLOAD_BYTES) return null
    val encoded = Base64.encodeToString(fallback, Base64.NO_WRAP)
    val dataUri = "$dataUriPrefix$encoded"
    return if (InputSanitizer.isValidProfilePhotoData(dataUri)) dataUri else null
  }

  fun decodeDataUriToBitmap(dataUri: String): Bitmap? {
    if (dataUri.isBlank()) return null
    return try {
      val commaIdx = dataUri.indexOf(',')
      val base64Part = if (commaIdx >= 0) dataUri.substring(commaIdx + 1) else dataUri
      if (base64Part.isBlank() || base64Part.length > InputSanitizer.MAX_PROFILE_PHOTO_LEN) return null
      val bytes = Base64.decode(base64Part, Base64.DEFAULT)
      if (bytes.isEmpty()) return null
      val opts = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
      }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Throwable) {
      null
    }
  }

  suspend fun decodeAndEnhanceFor4kViewer(
    dataUri: String,
    targetMaxDim: Int = TARGET_4K_DIMENSION
  ): Bitmap? = withContext(Dispatchers.Default) {
    getCachedEnhancedBitmap(dataUri)?.let { return@withContext it }
    val decoded = decodeDataUriToBitmap(dataUri) ?: return@withContext null
    val enhanced = enhanceBitmapFor4kViewer(decoded, targetMaxDim)
    if (enhanced !== decoded && !decoded.isRecycled) {
      decoded.recycle()
    }
    enhancedCache.put(cacheKey(dataUri), enhanced)
    enhanced
  }

  /**
   * Fast (~35ms) multi-scale unsharp clarity + native Skia progressive upscaling.
   * Restores crisp edges at the original pixel grid before each 2x scale step,
   * eliminating bilinear softness without UI lag.
   */
  fun enhanceBitmapFor4kViewer(
    source: Bitmap,
    targetMaxDim: Int = TARGET_4K_DIMENSION
  ): Bitmap {
    if (source.width <= 0 || source.height <= 0) return source
    return try {
      val safeTargetMax = targetMaxDim.coerceIn(720, TARGET_4K_DIMENSION)
      var current = source
      val origMax = max(source.width, source.height)

      // Step 1: Crisp edge definition at original pixel scale
      val initialStrength = when {
        origMax <= 400 -> 0.72f
        origMax <= 1080 -> 0.36f
        else -> 0.20f
      }
      val step1 = applyClarityPass(current, amount = initialStrength, edgeThreshold = 2)
      if (current !== source && step1 !== current) current.recycle()
      current = step1

      // Step 2: Progressive 2x native Skia scaling with intermediate clarity pass up to ~1080p
      while (max(current.width, current.height) < min(safeTargetMax, 1080)) {
        val curMax = max(current.width, current.height)
        val nextMax = min(min(safeTargetMax, 1080), curMax * 2)
        val factor = nextMax.toFloat() / curMax.toFloat()
        val nextW = max(1, (current.width * factor).roundToInt())
        val nextH = max(1, (current.height * factor).roundToInt())
        val scaled = Bitmap.createScaledBitmap(current, nextW, nextH, true)
        if (current !== source && scaled !== current) current.recycle()
        current = scaled

        val midSharpen = applyClarityPass(current, amount = 0.42f, edgeThreshold = 3)
        if (current !== source && midSharpen !== current) current.recycle()
        current = midSharpen
      }

      // Step 3: Scale smoothly up to full 2160p viewer target using native Skia
      val curMax = max(current.width, current.height)
      if (curMax < safeTargetMax) {
        val factor = safeTargetMax.toFloat() / curMax.toFloat()
        val finalW = max(1, (current.width * factor).roundToInt())
        val finalH = max(1, (current.height * factor).roundToInt())
        val finalScaled = Bitmap.createScaledBitmap(current, finalW, finalH, true)
        if (current !== source && finalScaled !== current) current.recycle()
        current = finalScaled
      }
      current
    } catch (_: OutOfMemoryError) {
      source
    } catch (_: Throwable) {
      source
    }
  }

  private fun applyClarityPass(source: Bitmap, amount: Float, edgeThreshold: Int): Bitmap {
    val w = source.width
    val h = source.height
    if (w < 3 || h < 3 || amount <= 0f) return source

    val argbSource = if (source.config == Bitmap.Config.ARGB_8888) {
      source
    } else {
      source.copy(Bitmap.Config.ARGB_8888, false) ?: return source
    }

    return try {
      val total = w * h
      val srcPixels = IntArray(total)
      val dstPixels = IntArray(total)
      argbSource.getPixels(srcPixels, 0, w, 0, 0, w, h)

      System.arraycopy(srcPixels, 0, dstPixels, 0, w)
      val lastRowOffset = (h - 1) * w
      System.arraycopy(srcPixels, lastRowOffset, dstPixels, lastRowOffset, w)

      val fixedScale = (amount * 256f).roundToInt()

      for (y in 1 until h - 1) {
        val row = y * w
        dstPixels[row] = srcPixels[row]
        dstPixels[row + w - 1] = srcPixels[row + w - 1]

        val prevRow = row - w
        val nextRow = row + w
        for (x in 1 until w - 1) {
          val idx = row + x
          val c = srcPixels[idx]
          val a = c ushr 24
          val r = (c ushr 16) and 0xFF
          val g = (c ushr 8) and 0xFF
          val b = c and 0xFF

          val cT = srcPixels[prevRow + x]
          val cB = srcPixels[nextRow + x]
          val cL = srcPixels[idx - 1]
          val cR = srcPixels[idx + 1]
          val cTL = srcPixels[prevRow + x - 1]
          val cTR = srcPixels[prevRow + x + 1]
          val cBL = srcPixels[nextRow + x - 1]
          val cBR = srcPixels[nextRow + x + 1]

          val avgR = (
            r * 4 +
              (((cT ushr 16) and 0xFF) + ((cB ushr 16) and 0xFF) + ((cL ushr 16) and 0xFF) + ((cR ushr 16) and 0xFF)) * 2 +
              ((cTL ushr 16) and 0xFF) + ((cTR ushr 16) and 0xFF) + ((cBL ushr 16) and 0xFF) + ((cBR ushr 16) and 0xFF)
            ) shr 4
          val avgG = (
            g * 4 +
              (((cT ushr 8) and 0xFF) + ((cB ushr 8) and 0xFF) + ((cL ushr 8) and 0xFF) + ((cR ushr 8) and 0xFF)) * 2 +
              ((cTL ushr 8) and 0xFF) + ((cTR ushr 8) and 0xFF) + ((cBL ushr 8) and 0xFF) + ((cBR ushr 8) and 0xFF)
            ) shr 4
          val avgB = (
            b * 4 +
              ((cT and 0xFF) + (cB and 0xFF) + (cL and 0xFF) + (cR and 0xFF)) * 2 +
              (cTL and 0xFF) + (cTR and 0xFF) + (cBL and 0xFF) + (cBR and 0xFF)
            ) shr 4

          val diffR = r - avgR
          val diffG = g - avgG
          val diffB = b - avgB

          val outR = if (abs(diffR) > edgeThreshold) (r + ((diffR * fixedScale) shr 8)).coerceIn(0, 255) else r
          val outG = if (abs(diffG) > edgeThreshold) (g + ((diffG * fixedScale) shr 8)).coerceIn(0, 255) else g
          val outB = if (abs(diffB) > edgeThreshold) (b + ((diffB * fixedScale) shr 8)).coerceIn(0, 255) else b

          dstPixels[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
        }
      }

      val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      outBitmap.setPixels(dstPixels, 0, w, 0, 0, w, h)
      outBitmap
    } catch (_: OutOfMemoryError) {
      source
    } finally {
      if (argbSource !== source) {
        argbSource.recycle()
      }
    }
  }
}
