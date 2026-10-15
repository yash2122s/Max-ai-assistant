package com.example.security

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression coverage for the private scheduler receiver declaration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TaskReceiverManifestSecurityTest {
    @Test
    fun `merged manifest keeps TaskReceiver non-exported and unpermissioned`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, "com.example.automation.scheduler.TaskReceiver"),
            PackageManager.GET_INTENT_FILTERS
        )

        assertFalse(info.exported)
        assertNull(info.permission)
    }
}
