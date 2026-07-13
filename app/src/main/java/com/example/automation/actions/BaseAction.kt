package com.example.automation.actions

import android.util.Log

abstract class BaseAction<T> : JarvisAction<T> {
    protected val TAG = this::class.java.simpleName

    protected fun log(message: String) {
        Log.d(TAG, message)
    }
    
    protected fun logError(message: String, e: Exception? = null) {
        Log.e(TAG, message, e)
    }
}
