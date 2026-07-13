package com.example.automation

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import com.example.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ShizukuShellPlugin {
    private const val TAG = "ShizukuShellPlugin"
    private var service: IShizukuShell? = null
    private var bindingLatch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            Log.d(TAG, "Shizuku UserService connected")
            service = IShizukuShell.Stub.asInterface(binder)
            bindingLatch?.countDown()
        }
        override fun onServiceDisconnected(component: ComponentName) {
            Log.d(TAG, "Shizuku UserService disconnected")
            service = null
        }
    }

    fun bindSync(): Boolean {
        if (service != null) return true
        if (!ShizukuManager.isShizukuAvailable() || !ShizukuManager.isPermissionGranted()) return false

        bindingLatch = CountDownLatch(1)
        val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name))
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
            
        try {
            Shizuku.bindUserService(args, connection)
            return bindingLatch?.await(3, TimeUnit.SECONDS) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error binding UserService", e)
            return false
        }
    }

    fun runCommand(command: String): String {
        if (!bindSync()) {
            return "Failed to bind to Shizuku UserService"
        }
        return try {
            service?.runCommand(command) ?: "Service connected but interface is null"
        } catch (e: Exception) {
            Log.e(TAG, "Error running command", e)
            "Remote error: ${e.localizedMessage}"
        }
    }
}
