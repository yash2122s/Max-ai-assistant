package com.example.smarthome
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import java.net.*
class TvNetworkTest {
    @Test fun wakePacketArrivesAtUdpReceiver() = runBlocking {
        val loop=InetAddress.getLoopbackAddress()
        DatagramSocket(0,loop).use { s ->
            s.soTimeout=2000
            wakeTv("01:02:03:04:05:06",loop,s.localPort)
            val packet=DatagramPacket(ByteArray(120),120)
            s.receive(packet)
            assertEquals(102,packet.length)
            assertArrayEquals(ByteArray(102){if(it<6) (-1).toByte() else ((it-6)%6+1).toByte()},packet.data.copyOf(102))
        }
    }
}