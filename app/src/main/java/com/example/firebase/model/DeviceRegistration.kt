package com.example.firebase.model

import com.google.firebase.Timestamp
import java.util.Date

data class DeviceRegistration(
    val deviceId: String,
    val deviceModel: String,
    val androidVersion: Int,
    val appVersion: String,
    val registeredAtEpochMillis: Long = System.currentTimeMillis(),
    val lastActiveAtEpochMillis: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
) {
    fun toFirestoreMap(): Map<String, Any?> {
        return mapOf(
            "deviceId" to deviceId,
            "deviceModel" to deviceModel,
            "androidVersion" to androidVersion,
            "appVersion" to appVersion,
            "registeredAt" to Timestamp(Date(registeredAtEpochMillis)),
            "lastActiveAt" to Timestamp(Date(lastActiveAtEpochMillis)),
            "isActive" to isActive
        )
    }

    companion object {
        fun fromFirestoreMap(deviceId: String, data: Map<String, Any?>?): DeviceRegistration {
            if (data == null) {
                return DeviceRegistration(
                    deviceId = deviceId,
                    deviceModel = "Unknown",
                    androidVersion = 0,
                    appVersion = "1.0"
                )
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

            return DeviceRegistration(
                deviceId = deviceId,
                deviceModel = (data["deviceModel"] as? String) ?: "Unknown",
                androidVersion = (data["androidVersion"] as? Number)?.toInt() ?: 0,
                appVersion = (data["appVersion"] as? String) ?: "1.0",
                registeredAtEpochMillis = parseTimestamp("registeredAt"),
                lastActiveAtEpochMillis = parseTimestamp("lastActiveAt"),
                isActive = (data["isActive"] as? Boolean) ?: true
            )
        }
    }
}
