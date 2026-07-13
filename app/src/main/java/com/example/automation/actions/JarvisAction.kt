package com.example.automation.actions

import android.content.Context

interface JarvisAction<T> {
    fun execute(context: Context, payload: T)
}
