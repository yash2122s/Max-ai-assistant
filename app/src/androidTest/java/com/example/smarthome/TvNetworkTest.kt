package com.example.smarthome
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import java.net.*
class TvNetworkTest {
    @Test fun pairsAndVerifiesVolumeOverWebSocket(){
        val server=okhttp3.mockwebserver.MockWebServer()
        val received=java.util.concurrent.LinkedBlockingQueue<String>()
        val frames=java.util.concurrent.LinkedBlockingQueue<String>()
        server.enqueue(okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(object:okhttp3.WebSocketListener(){
            override fun onMessage(ws:okhttp3.WebSocket,text:String) {
                val req=org.json.JSONObject(text)
                val uri=req.optString("uri")
                frames.offer(uri.ifBlank { "register" })
                val reply=org.json.JSONObject().put("id",req.optString("id"))
                if(req.optString("type")=="register") {
                    reply.put("type","registered").put("payload",org.json.JSONObject().put("client-key","test-device"))
                } else {
                    val data=org.json.JSONObject().put("returnValue",true)
                    if(uri.endsWith("getVolume")) data.put("volume",42)
                    reply.put("type","response").put("payload",data)
                }
                ws.send(reply.toString())
            }
        }))
        server.start()
        val client=okhttp3.OkHttpClient()
        val factory=object:okhttp3.WebSocket.Factory{
            override fun newWebSocket(request:okhttp3.Request,listener:okhttp3.WebSocketListener)=client.newWebSocket(request.newBuilder().url(server.url("/")).build(),listener)
        }
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val tv=TvSession(context,"192.168.1.215",factory){ msg,_ -> received.offer(msg) }
        try {
            tv.connect()
            assertEquals("Connected",received.poll(8,java.util.concurrent.TimeUnit.SECONDS))
            tv.send("audio/setVolume",org.json.JSONObject().put("volume",42))
            assertEquals("Volume verified",received.poll(8,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("register","ssap://audio/setVolume","ssap://audio/getVolume"),frames.toList())
        } finally { tv.close();server.shutdown();client.dispatcher.executorService.shutdown() }
    }
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