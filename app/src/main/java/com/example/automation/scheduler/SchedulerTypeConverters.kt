package com.example.automation.scheduler

import androidx.room.TypeConverter

class SchedulerTypeConverters {
    @TypeConverter
    fun toTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun fromTaskStatus(status: TaskStatus): String = status.name

    @TypeConverter
    fun toTaskType(value: String): TaskType = TaskType.valueOf(value)

    @TypeConverter
    fun fromTaskType(type: TaskType): String = type.name

    @TypeConverter
    fun toRetryPolicy(value: String): RetryPolicy = RetryPolicy.valueOf(value)

    @TypeConverter
    fun fromRetryPolicy(policy: RetryPolicy): String = policy.name

    @TypeConverter
    fun toRepeatType(value: String): RepeatType = RepeatType.valueOf(value)

    @TypeConverter
    fun fromRepeatType(repeatType: RepeatType): String = repeatType.name

    @TypeConverter
    fun toToolArguments(value: String): ToolArguments = ToolArguments(value)

    @TypeConverter
    fun fromToolArguments(args: ToolArguments): String = args.json
}
