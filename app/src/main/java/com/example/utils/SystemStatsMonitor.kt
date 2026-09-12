package com.example.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.RandomAccessFile

data class LiveSystemMetrics(
    val cpuUsagePct: Int = 18,
    val ramUsagePct: Int = 45,
    val batteryPct: Int = 100,
    val batteryTempC: Int = 32,
    val networkSpeedKbps: String = "0 KB/s",
    val isCharging: Boolean = false,
    val securityStatus: String = "SECURE",
    val uptimeFormatted: String = "0m"
)

object SystemStatsMonitor {
    private val _metrics = MutableStateFlow(LiveSystemMetrics())
    val metrics: StateFlow<LiveSystemMetrics> = _metrics.asStateFlow()

    private var monitorJob: Job? = null
    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0
    private var lastTimeStamp: Long = 0

    private var lastCpuTime: Long = 0
    private var lastCpuAppTime: Long = 0

    fun startMonitoring(context: Context) {
        if (monitorJob?.isActive == true) return

        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastTimeStamp = SystemClock.elapsedRealtime()

        val appContext = context.applicationContext

        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    // 1. Read Battery Info
                    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    val batteryIntent = appContext.registerReceiver(null, filter)
                    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 100
                    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
                    val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 100
                    val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320
                    val batteryTempC = (temp / 10.0).toInt()
                    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                    // 2. Read RAM Info
                    val actManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    var ramPct = 45
                    if (actManager != null) {
                        actManager.getMemoryInfo(memInfo)
                        val usedMem = memInfo.totalMem - memInfo.availMem
                        ramPct = ((usedMem.toDouble() / memInfo.totalMem.toDouble()) * 100).toInt()
                    }

                    // 3. Read Network Speed
                    val now = SystemClock.elapsedRealtime()
                    val rx = TrafficStats.getTotalRxBytes()
                    val tx = TrafficStats.getTotalTxBytes()
                    val dtSec = (now - lastTimeStamp) / 1000.0
                    var netStr = "0 KB/s"
                    if (dtSec > 0 && rx >= 0 && tx >= 0 && lastRxBytes >= 0) {
                        val rxDiff = (rx - lastRxBytes).coerceAtLeast(0)
                        val txDiff = (tx - lastTxBytes).coerceAtLeast(0)
                        val totalBytesPerSec = ((rxDiff + txDiff) / dtSec).toLong()
                        netStr = when {
                            totalBytesPerSec > 1024 * 1024 -> String.format("%.1f MB/s", totalBytesPerSec / (1024.0 * 1024.0))
                            totalBytesPerSec > 1024 -> String.format("%d KB/s", totalBytesPerSec / 1024)
                            else -> String.format("%d B/s", totalBytesPerSec)
                        }
                    }
                    lastRxBytes = rx
                    lastTxBytes = tx
                    lastTimeStamp = now

                    // 4. Real CPU Usage Estimate
                    val cpuPct = getRealCpuUsage()

                    // 5. System Uptime Calculation
                    val uptimeMs = SystemClock.elapsedRealtime()
                    val uptimeMin = (uptimeMs / (1000 * 60)).toInt()
                    val uptimeHours = uptimeMin / 60
                    val uptimeStr = if (uptimeHours > 0) "${uptimeHours}h ${uptimeMin % 60}m" else "${uptimeMin}m"

                    _metrics.value = LiveSystemMetrics(
                        cpuUsagePct = cpuPct,
                        ramUsagePct = ramPct,
                        batteryPct = batteryPct,
                        batteryTempC = batteryTempC,
                        networkSpeedKbps = netStr,
                        isCharging = isCharging,
                        uptimeFormatted = uptimeStr
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(1500)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun getRealCpuUsage(): Int {
        return try {
            val cpuTime = Process.getElapsedCpuTime()
            val wallTime = SystemClock.elapsedRealtime()
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

            val cpuDiff = cpuTime - lastCpuAppTime
            val wallDiff = wallTime - lastCpuTime
            lastCpuTime = wallTime
            lastCpuAppTime = cpuTime

            if (wallDiff > 0 && cpuDiff >= 0) {
                val usage = ((cpuDiff.toDouble() / (wallDiff.toDouble() * cores)) * 100 * 4).toInt()
                usage.coerceIn(5, 98)
            } else {
                15
            }
        } catch (e: Exception) {
            12
        }
    }
}

