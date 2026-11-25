package com.example.smarthome

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WakeOnLanManagerTest {
    @Test fun packetHasExactMagicBytes() {
        val mac = byteArrayOf(0x60, 0x95.toByte(), 0xf8.toByte(), 0xef.toByte(), 0xc5.toByte(), 0x2a)
        val packet = WakeOnLanManager.magicPacket("60:95:F8:EF:C5:2A")
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xff.toByte() }, packet.copyOfRange(0, 6))
        for (index in 0 until 16) assertArrayEquals(mac, packet.copyOfRange(6 + index * 6, 12 + index * 6))
    }

    @Test fun rejectsInvalidMac() {
        for (mac in listOf("60:95:F8:EF:C5", "60:95:F8:EF:C5:ZZ", "")) {
            assertThrows(IllegalArgumentException::class.java) { WakeOnLanManager.magicPacket(mac) }
        }
    }

    @Test fun calculatesSubnetBroadcast() {
        val address = InetAddress.getByName("192.168.0.112") as java.net.Inet4Address
        assertEquals("192.168.0.255", WakeOnLanManager.broadcast(address, 24).hostAddress)
        assertEquals("192.168.0.127", WakeOnLanManager.broadcast(address, 25).hostAddress)
    }
}
