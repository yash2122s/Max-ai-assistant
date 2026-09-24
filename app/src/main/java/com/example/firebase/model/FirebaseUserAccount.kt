package com.example.firebase.model

import com.google.firebase.Timestamp
import java.util.Date

data class FirebaseUserAccount(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val isAnonymous: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val lastLoginAtEpochMillis: Long = System.currentTimeMillis()
) {
    fun toFirestoreMap(currentDeviceId: String? = null): Map<String, Any?> {
        return mapOf(
            "userId" to uid,
            "email" to email,
            "displayName" to displayName,
            "photoUrl" to photoUrl,
            "isAnonymous" to isAnonymous,
            "createdAt" to Timestamp(Date(createdAtEpochMillis)),
            "lastLoginAt" to Timestamp(Date(lastLoginAtEpochMillis)),
            "currentDeviceId" to currentDeviceId
        )
    }

    companion object {
        fun fromFirestoreMap(uid: String, data: Map<String, Any?>?): FirebaseUserAccount {
            if (data == null) {
                return FirebaseUserAccount(uid = uid)
            }
            fun parseTimestamp(key: String): Long {
                val value = data[key]
                return when (value) {
                    is Timestamp -> value.toDate().time
                    is Long -> value
                    is Number -> value.toLong()
                    is Date -> value.time
                    else -> System.currentTimeMillis()
                }
            }

            return FirebaseUserAccount(
                uid = uid,
                email = data["email"] as? String,
                displayName = data["displayName"] as? String,
                photoUrl = data["photoUrl"] as? String,
                isAnonymous = (data["isAnonymous"] as? Boolean) ?: false,
                createdAtEpochMillis = parseTimestamp("createdAt"),
                lastLoginAtEpochMillis = parseTimestamp("lastLoginAt")
            )
        }
    }
}
