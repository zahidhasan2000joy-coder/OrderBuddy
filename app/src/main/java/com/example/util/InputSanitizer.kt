package com.example.util

import java.util.Locale

object InputSanitizer {

  const val MAX_ITEMS_PER_ORDER = 25
  const val MAX_ITEM_QUANTITY = 99
  const val MAX_ITEM_NAME_LEN = 80
  const val MAX_NOTES_LEN = 200
  const val MAX_MARKET_NAME_LEN = 100
  const val MAX_REASON_LEN = 250
  const val MAX_UNAVAILABLE_REASON_LEN = 150
  const val MAX_URL_LEN = 500
  const val MAX_PERSON_NAME_LEN = 80
  const val MAX_PHONE_LEN = 20
  const val MAX_ADDRESS_LEN = 300
  const val MAX_PROFILE_ADDRESS_LEN = 200
  const val MAX_PROFESSION_LEN = 60
  const val MAX_BIRTHDAY_LEN = 20
  const val MAX_PROFILE_PHOTO_LEN = 210_000
  const val MAX_PROFILE_MONTH_LEN = 7
  const val MAX_GIFT_NOTE_LEN = 200
  const val MAX_ADMIN_TITLE_LEN = 120
  const val MAX_ADMIN_MESSAGE_LEN = 500
  const val MAX_NOTIF_TITLE_LEN = 150
  const val MAX_NOTIF_MESSAGE_LEN = 600
  const val MAX_COINS = 100_000
  const val MAX_ITEM_PRICE = 1_000_000.0
  const val MAX_ORDER_COST = 10_000_000.0
  const val MIN_DURATION_MINS = 5
  const val MAX_DURATION_MINS = 1440
  const val NUMERIC_ID_MIN_LEN = 5
  const val NUMERIC_ID_MAX_LEN = 12

  private val INVISIBLE_AND_BIDI_CHARS = setOf(
    '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
    '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
    '\u2066', '\u2067', '\u2068', '\u2069',
    '\uFEFF', '\u00AD'
  )

  private val MULTI_SPACE_REGEX = Regex("""[ \t\u00A0]+""")
  private val MULTI_NEWLINE_REGEX = Regex("""\n{3,}""")

  /**
   * Strips unsafe ASCII control characters, C1 controls, and BiDi/zero-width spoofing characters.
   */
  fun stripUnsafeControlChars(raw: String, allowNewlines: Boolean = false): String {
    if (raw.isEmpty()) return ""
    val normalized = if (allowNewlines) {
      raw.replace("\r\n", "\n").replace('\r', '\n')
    } else {
      raw.replace('\r', ' ').replace('\n', ' ')
    }

    val sb = StringBuilder(normalized.length)
    for (ch in normalized) {
      if (ch in INVISIBLE_AND_BIDI_CHARS) continue
      val code = ch.code
      val isUnsafeCtrl = when {
        ch == '\n' -> !allowNewlines
        ch == '\t' -> false
        code < 0x20 -> true
        code in 0x7F..0x9F -> true
        else -> false
      }
      if (!isUnsafeCtrl) {
        sb.append(if (ch == '\t') ' ' else ch)
      }
    }
    return sb.toString()
  }

  /**
   * Safe live sanitizer for TextField `onValueChange` (preserves trailing whitespace while typing).
   */
  fun sanitizeOnType(raw: String, maxLength: Int, allowNewlines: Boolean = false): String {
    val clean = stripUnsafeControlChars(raw, allowNewlines = allowNewlines)
    return if (clean.length > maxLength) clean.substring(0, maxLength) else clean
  }

  /**
   * Final sanitizer applied before persisting or displaying submitted user input.
   */
  fun sanitizeForSubmit(raw: String, maxLength: Int, allowNewlines: Boolean = false): String {
    var clean = stripUnsafeControlChars(raw, allowNewlines = allowNewlines)
    clean = if (allowNewlines) {
      clean.lines()
        .joinToString("\n") { line -> MULTI_SPACE_REGEX.replace(line.trim(), " ") }
        .let { MULTI_NEWLINE_REGEX.replace(it, "\n\n") }
        .trim()
    } else {
      MULTI_SPACE_REGEX.replace(clean.trim(), " ")
    }
    return clean.take(maxLength)
  }

  fun containsLetterOrDigit(value: String): Boolean {
    return value.any { it.isLetterOrDigit() }
  }

  fun sanitizeItemName(raw: String): String {
    return sanitizeForSubmit(raw.replace("|||", " "), MAX_ITEM_NAME_LEN, allowNewlines = false)
  }

  fun isValidItemName(raw: String): Boolean {
    val clean = sanitizeItemName(raw)
    return clean.length in 2..MAX_ITEM_NAME_LEN && containsLetterOrDigit(clean)
  }

  fun sanitizeNotes(raw: String): String {
    return sanitizeForSubmit(raw, MAX_NOTES_LEN, allowNewlines = true)
  }

  fun isValidNotes(raw: String): Boolean {
    val clean = sanitizeNotes(raw)
    return clean.length in 1..MAX_NOTES_LEN && containsLetterOrDigit(clean)
  }

  fun sanitizeMarketName(raw: String): String {
    return sanitizeForSubmit(raw, MAX_MARKET_NAME_LEN, allowNewlines = false)
  }

  fun isValidMarketName(raw: String): Boolean {
    val clean = sanitizeMarketName(raw)
    return clean.length in 2..MAX_MARKET_NAME_LEN && containsLetterOrDigit(clean)
  }

  fun sanitizeReason(raw: String, maxLength: Int = MAX_REASON_LEN): String {
    return sanitizeForSubmit(raw, maxLength, allowNewlines = true)
  }

  fun isValidReason(raw: String, maxLength: Int = MAX_REASON_LEN): Boolean {
    val clean = sanitizeReason(raw, maxLength)
    return clean.length in 2..maxLength && containsLetterOrDigit(clean)
  }

  fun filterDigitsOnly(raw: String, maxLength: Int): String {
    return raw.filter { it in '0'..'9' }.take(maxLength)
  }

  fun isValidNumericId(raw: String): Boolean {
    val clean = raw.trim()
    return clean.length in NUMERIC_ID_MIN_LEN..NUMERIC_ID_MAX_LEN && clean.all { it in '0'..'9' }
  }

  fun filterDecimalInput(
    raw: String,
    maxIntegerDigits: Int = 7,
    maxDecimalDigits: Int = 2
  ): String {
    val sb = StringBuilder()
    var hasDot = false
    var intCount = 0
    var decCount = 0
    for (ch in raw) {
      when {
        ch in '0'..'9' -> {
          if (!hasDot) {
            if (intCount < maxIntegerDigits) {
              sb.append(ch)
              intCount++
            }
          } else {
            if (decCount < maxDecimalDigits) {
              sb.append(ch)
              decCount++
            }
          }
        }
        ch == '.' && !hasDot -> {
          if (sb.isEmpty()) {
            sb.append('0')
            intCount = 1
          }
          sb.append('.')
          hasDot = true
        }
      }
    }
    return sb.toString()
  }

  /**
   * Parses a decimal amount strictly within [0.0, maxAmount]. Returns null for NaN, Infinity,
   * negative values, or non-numeric junk. Blank strings return 0.0 when allowBlankAsZero is true.
   */
  fun parseValidPrice(
    raw: String,
    maxAmount: Double = MAX_ITEM_PRICE,
    allowBlankAsZero: Boolean = true
  ): Double? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return if (allowBlankAsZero) 0.0 else null
    if (trimmed == ".") return null
    val parsed = trimmed.toDoubleOrNull() ?: return null
    if (!parsed.isFinite() || parsed < 0.0 || parsed > maxAmount) return null
    // Round to 2 decimal places cleanly
    return String.format(Locale.US, "%.2f", parsed).toDoubleOrNull() ?: parsed
  }

  fun filterPhoneInput(raw: String, maxLength: Int = MAX_PHONE_LEN): String {
    val clean = stripUnsafeControlChars(raw, allowNewlines = false)
    val sb = StringBuilder()
    for (ch in clean) {
      when {
        ch in '0'..'9' -> sb.append(ch)
        ch == '+' && sb.isEmpty() -> sb.append(ch)
        ch == '-' || ch == ' ' -> sb.append(ch)
      }
      if (sb.length >= maxLength) break
    }
    return sb.toString()
  }

  fun sanitizePhone(raw: String): String {
    return MULTI_SPACE_REGEX.replace(filterPhoneInput(raw, MAX_PHONE_LEN).trim(), " ")
  }

  fun isValidPhone(raw: String): Boolean {
    val clean = sanitizePhone(raw)
    val digitCount = clean.count { it in '0'..'9' }
    return clean.length in 6..MAX_PHONE_LEN && digitCount >= 6
  }

  fun sanitizePersonName(raw: String): String {
    return sanitizeForSubmit(raw, MAX_PERSON_NAME_LEN, allowNewlines = false)
  }

  fun isValidPersonName(raw: String): Boolean {
    val clean = sanitizePersonName(raw)
    return clean.length in 2..MAX_PERSON_NAME_LEN && containsLetterOrDigit(clean)
  }

  fun sanitizeAddress(raw: String): String {
    return sanitizeForSubmit(raw, MAX_ADDRESS_LEN, allowNewlines = true)
  }

  fun isValidAddress(raw: String): Boolean {
    val clean = sanitizeAddress(raw)
    return clean.length in 5..MAX_ADDRESS_LEN && containsLetterOrDigit(clean)
  }

  fun sanitizeProfession(raw: String): String {
    return sanitizeForSubmit(raw, MAX_PROFESSION_LEN, allowNewlines = false)
  }

  fun isValidProfession(raw: String): Boolean {
    val clean = sanitizeProfession(raw)
    return clean.length in 2..MAX_PROFESSION_LEN && containsLetterOrDigit(clean)
  }

  fun sanitizeProfileAddress(raw: String): String {
    return sanitizeForSubmit(raw, MAX_PROFILE_ADDRESS_LEN, allowNewlines = true)
  }

  fun isValidProfileAddress(raw: String): Boolean {
    val clean = sanitizeProfileAddress(raw)
    return clean.length in 3..MAX_PROFILE_ADDRESS_LEN && containsLetterOrDigit(clean)
  }

  private val BIRTHDAY_ISO_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")

  fun sanitizeBirthday(raw: String): String {
    val clean = sanitizeForSubmit(raw, MAX_BIRTHDAY_LEN, allowNewlines = false)
    if (clean.isBlank()) return ""
    return if (BIRTHDAY_ISO_REGEX.matches(clean)) clean else ""
  }

  fun formatBirthdayDisplay(rawBirthday: String): String {
    val clean = sanitizeBirthday(rawBirthday)
    if (clean.isBlank()) return ""
    return try {
      val parser = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
      val date = parser.parse(clean) ?: return clean
      java.text.SimpleDateFormat("dd MMM, yyyy", Locale.US).format(date)
    } catch (_: Exception) {
      clean
    }
  }

  fun isValidProfilePhotoData(raw: String): Boolean {
    if (raw.isEmpty()) return true
    if (raw.length > MAX_PROFILE_PHOTO_LEN) return false
    return raw.startsWith("data:image/webp;base64,") ||
      raw.startsWith("data:image/jpeg;base64,") ||
      raw.startsWith("data:image/png;base64,")
  }

  fun currentYearMonth(nowMs: Long = System.currentTimeMillis()): String {
    return java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date(nowMs))
  }

  fun nextEligibleEditDateLabel(lastEditMonth: String): String {
    val match = Regex("""^(\d{4})-(\d{2})$""").matchEntire(lastEditMonth.trim()) ?: return "Next month"
    val year = match.groupValues[1].toIntOrNull() ?: return "Next month"
    val month = match.groupValues[2].toIntOrNull() ?: return "Next month"
    val cal = java.util.Calendar.getInstance(Locale.US).apply {
      clear()
      set(java.util.Calendar.YEAR, year)
      set(java.util.Calendar.MONTH, (month - 1).coerceIn(0, 11))
      set(java.util.Calendar.DAY_OF_MONTH, 1)
      add(java.util.Calendar.MONTH, 1)
    }
    return java.text.SimpleDateFormat("dd MMM, yyyy", Locale.US).format(cal.time)
  }
}
