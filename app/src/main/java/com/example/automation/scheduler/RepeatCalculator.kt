package com.example.automation.scheduler

import java.util.Calendar

object RepeatCalculator {
    fun calculateNextExecutionTime(currentTime: Long, repeatType: RepeatType): Long? {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = currentTime
        }
        return when (repeatType) {
            RepeatType.DAILY -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                calendar.timeInMillis
            }
            RepeatType.WEEKLY -> {
                calendar.add(Calendar.WEEK_OF_YEAR, 1)
                calendar.timeInMillis
            }
            RepeatType.MONTHLY -> {
                calendar.add(Calendar.MONTH, 1)
                calendar.timeInMillis
            }
            else -> null
        }
    }
}
