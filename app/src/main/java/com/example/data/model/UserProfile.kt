package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.abs

@Keep
@IgnoreExtraProperties
data class UserProfile(
  val uid: String = "",
  val email: String = "",
  val displayName: String = "",
  val numericId: String = "",
  val connectedPartnerId: String = "",
  val connectedPartnerUid: String = "",
  val connectedPartnerName: String = "",
  val coins: Int = 0,
  val lastCoinDate: String = "",
  val profilePhotoData: String = "",
  val birthday: String = "",
  val profession: String = "",
  val address: String = "",
  val lastProfileEditMonth: String = "",
  val lastProfileEditAtMs: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null
) {
  companion object {
    fun generateNumericId(email: String): String {
      val clean = email.lowercase().trim()
      val hash = abs(clean.hashCode())
      val code = (hash % 900000) + 100000
      return code.toString()
    }
  }
}

@Keep
@IgnoreExtraProperties
data class GiftClaim(
  @DocumentId val id: String = "",
  val userId: String = "",
  val userNumericId: String = "",
  val userEmail: String = "",
  val userName: String = "",
  val coins: Int = 0,
  val recipientName: String = "",
  val phone: String = "",
  val address: String = "",
  val note: String = "",
  val status: String = "pending", // pending, approved, delivered
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)
