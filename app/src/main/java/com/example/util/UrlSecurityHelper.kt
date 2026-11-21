package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.zip.GZIPInputStream

data class ExtractedProductInfo(
  val cleanUrl: String,
  val domain: String,
  val detectedName: String
)

object UrlSecurityHelper {

  private val URL_IN_TEXT_REGEX = Regex("""https?://[^\s<>"'{}|\\^`]+""", RegexOption.IGNORE_CASE)
  private val WWW_IN_TEXT_REGEX = Regex("""(?:\b|^)www\.[^\s<>"'{}|\\^`]+""", RegexOption.IGNORE_CASE)

  // Direct image URLs and common image CDNs
  private val DIRECT_IMAGE_EXT_REGEX = Regex(
    """(?:\.(?:jpg|jpeg|png|webp|gif|bmp|avif|svg)(?:_[\w\d.]+)?(?:\?.*)?$)|(?:img\.drz\.lazcdn\.com|static-\d+\.daraz\.com\.bd/p/|alicdn\.com/|media-amazon\.com/images/|i\.imgur\.com|images\.unsplash\.com|fbcdn\.net|pinimg\.com)""",
    RegexOption.IGNORE_CASE
  )

  fun isSafePublicHost(rawHost: String): Boolean {
    val host = rawHost.trim().lowercase().removePrefix("[").removeSuffix("]")
    if (host.isBlank() || host.length > 253) return false
    if (host.contains(" ") || host.contains("<") || host.contains(">") || host.contains("@") || host.contains("\\")) {
      return false
    }
    if (host == "localhost" ||
      host.endsWith(".localhost") ||
      host.endsWith(".local") ||
      host.endsWith(".internal") ||
      host.endsWith(".arpa") ||
      host == "::1" ||
      host == "::" ||
      host.startsWith("fe80:") ||
      host.startsWith("fc") ||
      host.startsWith("fd") ||
      host.startsWith("::ffff:")
    ) {
      return false
    }
    // Reject IPv4 loopback, private, link-local, unspecified, or shorthand numeric IP hosts
    val ipv4Match = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(host)
    if (ipv4Match != null) {
      val octets = ipv4Match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
      if (octets.any { it !in 0..255 }) return false
      val (a, b, _, _) = octets
      if (a == 0 || a == 10 || a == 127) return false
      if (a == 169 && b == 254) return false
      if (a == 172 && b in 16..31) return false
      if (a == 192 && b == 168) return false
      if (a >= 224) return false
      return true
    }
    // Reject single-integer / hex IP obfuscations like http://2130706433 or http://0x7f000001
    if (!host.contains('.')) return false
    if (host.split('.').lastOrNull().orEmpty().length < 2) return false
    return true
  }

  /**
   * Extracts a valid web URL from arbitrary text (e.g. copied share text from any website/app).
   */
  fun extractUrlFromText(rawText: String): String? {
    val cleanInput = InputSanitizer.stripUnsafeControlChars(rawText, allowNewlines = true).trim()
    if (cleanInput.isBlank()) return null

    var matched = URL_IN_TEXT_REGEX.find(cleanInput)?.value
    if (matched == null) {
      val wwwMatch = WWW_IN_TEXT_REGEX.find(cleanInput)?.value
      if (wwwMatch != null) {
        matched = "https://$wwwMatch"
      }
    }

    if (matched == null && (cleanInput.startsWith("http://", ignoreCase = true) || cleanInput.startsWith("https://", ignoreCase = true))) {
      matched = cleanInput
    }

    if (matched == null) return null

    val cleaned = matched.trimEnd('.', ',', ')', '>', ']', '"', '\'')
    return sanitizeUrl(cleaned)
  }

  fun sanitizeUrl(rawUrl: String): String? {
    val trimmed = InputSanitizer.stripUnsafeControlChars(rawUrl, allowNewlines = false).trim()
    if (trimmed.isBlank()) return null

    val directUrl = if (trimmed.contains(" ")) {
      URL_IN_TEXT_REGEX.find(trimmed)?.value ?: return null
    } else {
      trimmed
    }.trimEnd('.', ',', ')', '>', ']', '"', '\'')

    if (directUrl.length > InputSanitizer.MAX_URL_LEN) return null

    val formattedUrl = if (!directUrl.startsWith("http://", ignoreCase = true) &&
      !directUrl.startsWith("https://", ignoreCase = true)
    ) {
      "https://$directUrl"
    } else {
      directUrl
    }

    if (formattedUrl.length > InputSanitizer.MAX_URL_LEN) return null

    return try {
      val uri = Uri.parse(formattedUrl)
      val scheme = uri.scheme?.lowercase()

      if (scheme != "http" && scheme != "https") {
        return null
      }

      if (!uri.userInfo.isNullOrEmpty()) {
        return null
      }

      val host = uri.host ?: return null
      if (!isSafePublicHost(host)) {
        return null
      }

      val normalized = uri.toString()
      if (normalized.length > InputSanitizer.MAX_URL_LEN) null else normalized
    } catch (e: Exception) {
      null
    }
  }

  fun extractDomain(url: String): String {
    val cleanUrl = extractUrlFromText(url) ?: sanitizeUrl(url) ?: return ""
    return try {
      val uri = Uri.parse(cleanUrl)
      val host = uri.host ?: ""
      if (host.startsWith("www.", ignoreCase = true)) {
        host.substring(4)
      } else {
        host
      }
    } catch (e: Exception) {
      ""
    }
  }

  fun extractProductInfo(rawUrl: String): ExtractedProductInfo? {
    val cleanUrl = extractUrlFromText(rawUrl) ?: sanitizeUrl(rawUrl) ?: return null
    val domain = extractDomain(cleanUrl)

    var detectedName = ""
    try {
      val uri = Uri.parse(cleanUrl)
      val segments = uri.pathSegments ?: emptyList()

      for (segment in segments.reversed()) {
        val decoded = try { URLDecoder.decode(segment, "UTF-8") } catch (e: Exception) { segment }
        var cleaned = decoded.replace(Regex("\\.(html|htm|php|asp|aspx)$", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("-i\\d+.*$", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("-p\\d+.*$", RegexOption.IGNORE_CASE), "")

        if (cleaned.contains("-") || cleaned.contains("_") || (cleaned.length > 5 && !cleaned.all { it.isDigit() })) {
          val words = cleaned
            .split(Regex("[-_]+"))
            .filter { word ->
              word.isNotBlank() &&
                word.length < 30 &&
                !word.matches(Regex("^(dp|gp|item|product|products|ad|details|p|id|buy|shop|index|item.htm|store)$", RegexOption.IGNORE_CASE))
            }

          if (words.size >= 2 || (words.size == 1 && words.first().length > 3)) {
            detectedName = words.joinToString(" ") { word ->
              word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            break
          }
        }
      }

      if (detectedName.isBlank()) {
        val queryParam = uri.getQueryParameter("title")
          ?: uri.getQueryParameter("name")
          ?: uri.getQueryParameter("item")
          ?: uri.getQueryParameter("q")
          ?: uri.getQueryParameter("search")
        if (!queryParam.isNullOrBlank()) {
          detectedName = queryParam.trim().take(80)
        }
      }
    } catch (e: Exception) {
      // Graceful fallback
    }

    val sanitizedTitle = detectedName
      .replace(Regex("[<>{}\"\\\\]"), "")
      .trim()
      .take(100)

    val finalTitle = if (sanitizedTitle.isNotBlank()) {
      sanitizedTitle
    } else {
      if (domain.isNotBlank()) "Link from $domain" else "Web Link"
    }

    return ExtractedProductInfo(
      cleanUrl = cleanUrl,
      domain = domain,
      detectedName = finalTitle
    )
  }

  fun openWebUrlSafely(context: Context, rawUrl: String): Boolean {
    val validUrl = extractUrlFromText(rawUrl) ?: sanitizeUrl(rawUrl)
    if (validUrl.isNullOrEmpty()) {
      Toast.makeText(context, "⚠️ Invalid or unsafe web link", Toast.LENGTH_SHORT).show()
      return false
    }

    return try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addCategory(Intent.CATEGORY_BROWSABLE)
      }
      context.startActivity(intent)
      true
    } catch (e: Exception) {
      Toast.makeText(context, "⚠️ Unable to open link", Toast.LENGTH_SHORT).show()
      false
    }
  }

  /**
   * Universal robust image extractor for ANY web link (Daraz, Facebook, YouTube, News portals, Blogs, etc.)
   */
  suspend fun fetchProductImage(rawUrl: String): String? = withContext(Dispatchers.IO) {
    val cleanUrl = extractUrlFromText(rawUrl) ?: sanitizeUrl(rawUrl) ?: return@withContext null

    if (DIRECT_IMAGE_EXT_REGEX.containsMatchIn(cleanUrl)) {
      return@withContext cleanUrl
    }

    try {
      var currentUrl = cleanUrl
      var redirects = 0
      var finalHtml: String? = null
      var resolvedBaseUrl: URL? = null

      while (redirects < 5) {
        currentUrl = sanitizeUrl(currentUrl) ?: return@withContext null
        val url = URL(currentUrl)
        resolvedBaseUrl = url
        val connection = (url.openConnection() as? HttpURLConnection) ?: return@withContext null
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.instanceFollowRedirects = false

        connection.setRequestProperty(
          "User-Agent",
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )
        connection.setRequestProperty(
          "Accept",
          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
        connection.setRequestProperty("Accept-Encoding", "gzip, identity")

        val responseCode = try { connection.responseCode } catch (e: Exception) { -1 }
        if (responseCode in 300..399) {
          val location = connection.getHeaderField("Location")
          connection.disconnect()
          if (!location.isNullOrBlank() && location.length <= 1024) {
            val nextUri = URI(currentUrl).resolve(location)
            val validatedNext = sanitizeUrl(nextUri.toString()) ?: break
            currentUrl = validatedNext
            redirects++
            continue
          } else {
            break
          }
        }

        val contentType = connection.contentType ?: ""
        if (contentType.startsWith("image/", ignoreCase = true)) {
          connection.disconnect()
          return@withContext currentUrl
        }

        val rawStream: InputStream? = try {
          connection.inputStream
        } catch (e: Exception) {
          try { connection.errorStream } catch (ex: Exception) { null }
        }

        if (rawStream != null) {
          val contentEncoding = connection.contentEncoding ?: connection.getHeaderField("Content-Encoding") ?: ""
          val isGzip = contentEncoding.contains("gzip", ignoreCase = true)
          val stream = if (isGzip) {
            try { GZIPInputStream(rawStream) } catch (e: Exception) { rawStream }
          } else {
            rawStream
          }

          val buffer = ByteArray(524288) // Read up to 512KB for maximum metadata coverage
          var totalRead = 0
          while (totalRead < buffer.size) {
            val read = stream.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
          }
          try { stream.close() } catch (e: Exception) {}
          connection.disconnect()

          if (totalRead > 0) {
            finalHtml = String(buffer, 0, totalRead, Charsets.UTF_8)
          }
        } else {
          connection.disconnect()
        }
        break
      }

      val html = finalHtml ?: return@withContext null
      val baseUrl = resolvedBaseUrl ?: URL(cleanUrl)

      // 1. OpenGraph / Twitter Image Tags (all variants) - filtering out logos/icons
      val ogRegex = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:image|og:image:secure_url|og:image:url|twitter:image|twitter:image:src|image)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
      )
      for (match in ogRegex.findAll(html)) {
        val rawImg = match.groupValues[1].trim()
        resolveRelativeUrl(baseUrl, rawImg).let { img ->
          sanitizeUrl(img)?.let { sanitized ->
            if (isValidProductOrPostImage(sanitized)) {
              return@withContext sanitized
            }
          }
        }
      }

      // 2. OpenGraph with content before property
      val ogRegexAlt = Regex(
        """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["'](?:og:image|og:image:secure_url|og:image:url|twitter:image|twitter:image:src|image)["']""",
        RegexOption.IGNORE_CASE
      )
      for (match in ogRegexAlt.findAll(html)) {
        val rawImg = match.groupValues[1].trim()
        resolveRelativeUrl(baseUrl, rawImg).let { img ->
          sanitizeUrl(img)?.let { sanitized ->
            if (isValidProductOrPostImage(sanitized)) {
              return@withContext sanitized
            }
          }
        }
      }

      // 3. Itemprop image
      val itempropRegex = Regex(
        """<meta[^>]+itemprop=["']image["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
      )
      for (match in itempropRegex.findAll(html)) {
        val rawImg = match.groupValues[1].trim()
        resolveRelativeUrl(baseUrl, rawImg).let { img ->
          sanitizeUrl(img)?.let { sanitized ->
            if (isValidProductOrPostImage(sanitized)) {
              return@withContext sanitized
            }
          }
        }
      }

      // 4. Link rel="image_src" or preload
      val linkRegex = Regex(
        """<link[^>]+rel=["'](?:image_src|preload)["'][^>]+(?:href|imagesrcset)=["']([^"'\s]+)["']""",
        RegexOption.IGNORE_CASE
      )
      for (match in linkRegex.findAll(html)) {
        val rawImg = match.groupValues[1].trim()
        resolveRelativeUrl(baseUrl, rawImg).let { img ->
          sanitizeUrl(img)?.let { sanitized ->
            if (isValidProductOrPostImage(sanitized)) {
              return@withContext sanitized
            }
          }
        }
      }

      // 5. JSON-LD schema image
      val jsonLdRegex = Regex(
        """"image"\s*:\s*(?:\[\s*)?["'](https?://[^"']+)["']""",
        RegexOption.IGNORE_CASE
      )
      for (match in jsonLdRegex.findAll(html)) {
        val img = match.groupValues[1].trim().replace("\\/", "/")
        sanitizeUrl(img)?.let { sanitized ->
          if (isValidProductOrPostImage(sanitized)) {
            return@withContext sanitized
          }
        }
      }

      // 6. Generic JSON / CDN image key match
      val cdnKeyRegex = Regex(
        """(?:\"image\"|\"img\"|\"mainImage\"|\"originalUrl\"|\"productImg\"|\"imageUrl\"|\"thumbnailUrl\")\s*:\s*\"(https?://[^\"]+)\"""",
        RegexOption.IGNORE_CASE
      )
      for (match in cdnKeyRegex.findAll(html)) {
        val img = match.groupValues[1].trim().replace("\\/", "/")
        sanitizeUrl(img)?.let { sanitized ->
          if (isValidProductOrPostImage(sanitized)) {
            return@withContext sanitized
          }
        }
      }

      // 6.5 Universal CDN / e-commerce image finder
      val cdnMatchRegex = Regex("""https?://[^\s"'<>]+?\.(?:jpg|jpeg|png|webp)(?:\?[^\s"'<>]*)?"""", RegexOption.IGNORE_CASE)
      for (match in cdnMatchRegex.findAll(html)) {
        val imgUrl = match.value.replace("\\/", "/")
        if ((imgUrl.contains("lazcdn") || imgUrl.contains("alicdn") || imgUrl.contains("daraz") || imgUrl.contains("amazon") || imgUrl.contains("fbcdn") || imgUrl.contains("product") || imgUrl.contains("media")) &&
            isValidProductOrPostImage(imgUrl)) {
          sanitizeUrl(imgUrl)?.let { return@withContext it }
        }
      }

      // 7. Universal <img> tag matcher
      val imgTagRegex = Regex("""<img[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
      for (match in imgTagRegex.findAll(html)) {
        val cleanImg = match.groupValues[1].replace("\\/", "/")
        if (cleanImg.length < 300 && isValidProductOrPostImage(cleanImg)) {
          sanitizeUrl(cleanImg)?.let { return@withContext it }
        }
      }

    } catch (e: Exception) {
      // Graceful fallback
    }

    null
  }

  private fun isValidProductOrPostImage(url: String): Boolean {
    val lower = url.lowercase()
    val forbidden = listOf(
      "logo", "favicon", "icon", "avatar", "profile", "badge", "header", "footer",
      "sprite", "pixel", "spacer", "blank", "transparent", "social", "share",
      "watermark", "button", "loading", "spinner", "default", "placeholder",
      "site_logo", "sitelogo", "app_icon", "appicon", "user", "author", "emoji", "brand"
    )
    for (word in forbidden) {
      if (lower.contains(word)) return false
    }
    return true
  }

  private fun resolveRelativeUrl(baseUrl: URL, rawPath: String): String {
    val clean = rawPath.trim()
    return when {
      clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
      clean.startsWith("//") -> "https:$clean"
      clean.startsWith("/") -> "${baseUrl.protocol}://${baseUrl.host}$clean"
      else -> {
        try {
          URI(baseUrl.toString()).resolve(clean).toString()
        } catch (e: Exception) {
          "${baseUrl.protocol}://${baseUrl.host}/$clean"
        }
      }
    }
  }
}
