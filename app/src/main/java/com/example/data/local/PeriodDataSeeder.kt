package com.example.data.local

import android.content.Context

/** Historical sample records are retained in the legacy partition, never seeded into an account. */
object PeriodDataSeeder {
    suspend fun seedPeriodData(context: Context) = Unit
}
