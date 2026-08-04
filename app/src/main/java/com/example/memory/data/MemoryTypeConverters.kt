package com.example.memory.data

import androidx.room.TypeConverter

class MemoryTypeConverters {

    @TypeConverter
    fun fromMemoryType(value: MemoryType): String = value.name

    @TypeConverter
    fun toMemoryType(value: String): MemoryType = MemoryType.valueOf(value)

    @TypeConverter
    fun fromMemorySource(value: MemorySource): String = value.name

    @TypeConverter
    fun toMemorySource(value: String): MemorySource = MemorySource.valueOf(value)

    @TypeConverter
    fun fromFloatArray(arr: FloatArray?): ByteArray? {
        if (arr == null) return null
        val buffer = java.nio.ByteBuffer.allocate(arr.size * 4)
        arr.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.isEmpty()) return null
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
