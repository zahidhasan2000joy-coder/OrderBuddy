package com.example.util

object AdminConfig {
  private val S = intArrayOf(0x5B, 0xA4, 0x39, 0xC7, 0x7E, 0x12, 0x8D, 0x41)
  private val ADM = intArrayOf(
    126, 183, 60, 52, 207, 218, 75, 236, 75, 230, 78, 195, 35, 106, 126, 160,
    171, 72, 201, 104, 28, 105, 184, 71, 93, 85, 7
  )

  private fun decodeAdminEmail(): String {
    val out = ByteArray(ADM.size)
    for (i in ADM.indices) {
      val mask = (S[i % S.size] xor ((0x4A + i * 23) and 0xFF)) and 0xFF
      out[i] = (((ADM[i] - 19) and 0xFF) xor mask).toByte()
    }
    return String(out, Charsets.UTF_8)
  }

  val ADMIN_EMAIL: String
    get() = decodeAdminEmail()

  const val GIFT_COIN_THRESHOLD = 180

  fun isAdminEmail(email: String?): Boolean {
    return email?.trim()?.equals(ADMIN_EMAIL, ignoreCase = true) == true
  }

  fun isAdminNoticeVisible(type: String?): Boolean {
    return !type.orEmpty().trim().equals("new_order", ignoreCase = true)
  }

  fun filterAdminNotices(
    notifications: List<com.example.data.model.NotificationData>
  ): List<com.example.data.model.NotificationData> {
    return notifications.filter { isAdminNoticeVisible(it.type) }
  }
}
