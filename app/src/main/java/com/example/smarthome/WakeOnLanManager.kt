package com.example.smarthome

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

internal object WakeOnLanManager {
    private val wakeLock = Mutex()

    fun magicPacket(mac: String): ByteArray {
        require(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(mac)) { "Invalid TV MAC address" }
        val bytes = mac.split(':').map { it.toInt(16).toByte() }
        return ByteArray(102) { index -> if (index < 6) 0xff.toByte() else bytes[(index - 6) % 6] }
    }

    fun broadcast(address: Inet4Address, prefixLength: Int): InetAddress {
        require(prefixLength in 1..32)
        val ip = address.address
        val mask = if (prefixLength == 32) -1 else -1 shl (32 - prefixLength)
        val value = ((ip[0].toInt() and 255) shl 24) or ((ip[1].toInt() and 255) shl 16) or
            ((ip[2].toInt() and 255) shl 8) or (ip[3].toInt() and 255)
        val result = value or mask.inv()
        return InetAddress.getByAddress(byteArrayOf((result ushr 24).toByte(),
            (result ushr 16).toByte(), (result ushr 8).toByte(), result.toByte()))
    }

    suspend fun wake(context: Context, mac: String, port: Int = 9, packets: Int = 5) {
        require(port in 1..65535 && packets in 1..10)
        require(!wakeLock.isLocked) { "TV wake already in progress" }
        wakeLock.withLock {
            withContext(Dispatchers.IO) {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val network = manager.activeNetwork ?: error("Wi-Fi is unavailable")
                val properties = manager.getLinkProperties(network) ?: error("Local network unavailable")
                val link: LinkAddress = properties.linkAddresses.firstOrNull {
                    it.address is Inet4Address && it.prefixLength in 1..32 &&
                        !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
                } ?: error("IPv4 Wi-Fi address unavailable")
                val destination = broadcast(link.address as Inet4Address, link.prefixLength)
                val packet = magicPacket(mac)
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    for (attempt in 0 until packets) {
                        socket.send(DatagramPacket(packet, packet.size, destination, port))
                        if (attempt < packets - 1) delay(1000)
                    }
                }
            }
        }
    }
}
