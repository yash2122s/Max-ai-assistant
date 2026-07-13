package com.example.automation

import android.content.Context

interface AppAutomation {
    fun execute(context: Context, contact: String, message: String): String
}
