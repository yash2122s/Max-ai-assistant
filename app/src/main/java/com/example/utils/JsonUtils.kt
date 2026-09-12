package com.example.utils

import com.google.gson.JsonElement

fun JsonElement?.asStringOrNull(): String? {
    if (this == null || this.isJsonNull) return null
    return try {
        if (this.isJsonPrimitive) this.asString else this.toString()
    } catch (e: Exception) {
        null
    }
}

fun JsonElement?.asLongOrNull(): Long? {
    if (this == null || this.isJsonNull) return null
    return try {
        if (this.isJsonPrimitive) this.asLong else null
    } catch (e: Exception) {
        null
    }
}

fun JsonElement?.asBooleanOrNull(): Boolean? {
    if (this == null || this.isJsonNull) return null
    return try {
        if (this.isJsonPrimitive) this.asBoolean else null
    } catch (e: Exception) {
        null
    }
}

fun JsonElement?.asIntOrNull(): Int? {
    if (this == null || this.isJsonNull) return null
    return try {
        if (this.isJsonPrimitive) this.asInt else null
    } catch (e: Exception) {
        null
    }
}
