package com.example.smarthome

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

internal fun lanHost(host: String): Boolean {
    val parts = host.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31
}

private suspend fun discover() = withContext(Dispatchers.IO) {
    DatagramSocket().use { socket ->
        val probe = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:lge-com:service:webos-second-screen:1\r\n\r\n".toByteArray()
        socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("239.255.255.250"), 1900))
        val hosts = mutableSetOf<String>()
        val end = android.os.SystemClock.elapsedRealtime() + 3500
        socket.soTimeout = 350
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val p = DatagramPacket(ByteArray(4096), 4096)
            try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
            val host = p.address.hostAddress ?: continue
            if (lanHost(host) && String(p.data, 0, p.length).contains("webos-second-screen", true)) hosts.add(host)
        }
        hosts.toList()
    }
}

private class TvSession(context: Context, val host: String, val status: (String, Boolean) -> Unit) : WebSocketListener() {
    private val prefs = EncryptedSharedPreferences.create(context, "lg_tv_keys", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    companion object { private val client = OkHttpClient() }
    private var socket: WebSocket? = null
    private val seq = AtomicInteger()
    fun connect() { require(lanHost(host)); socket = client.newWebSocket(Request.Builder().url("ws://"+host+":3000").build(), this) }
    fun close() { socket?.cancel() }
    override fun onOpen(ws: WebSocket, response: Response) {
        val permissions = org.json.JSONArray(listOf("CONTROL_AUDIO","CONTROL_POWER","CONTROL_INPUT_MEDIA_PLAYBACK","CONTROL_INPUT_TV","LAUNCH","WRITE_NOTIFICATION_TOAST","READ_CURRENT_CHANNEL","READ_RUNNING_APPS"))
        val payload = JSONObject().put("pairingType","PROMPT").put("manifest",JSONObject().put("manifestVersion",1).put("appVersion","1.0").put("permissions",permissions))
        prefs.getString(host,null)?.let { payload.put("client-key",it) }
        ws.send(JSONObject().put("type","register").put("id","pair").put("payload",payload).toString())
    }
    @Volatile private var ready = false
    override fun onMessage(ws: WebSocket, text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val data = msg.optJSONObject("payload")
        when (msg.optString("type")) {
            "registered" -> { data?.optString("client-key")?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString(host,it).apply() }; ready=true; status("Connected",true) }
            "error" -> status(msg.optString("error","TV rejected command"),ready)
            "response" -> if (msg.optString("id") != "pair") status(if (data?.optBoolean("returnValue")==true) "TV acknowledged command" else "TV did not confirm command",ready)
        }
    }
    override fun onFailure(ws: WebSocket,t: Throwable,response: Response?) { ready=false; status("Disconnected",false) }
    override fun onClosed(ws: WebSocket,code: Int,reason: String) { ready=false; status("Disconnected",false) }
    fun send(uri: String, data: JSONObject = JSONObject()) {
        if (!ready || socket?.send(JSONObject().put("type","request").put("id",seq.incrementAndGet().toString()).put("uri","ssap://"+uri).put("payload",data).toString()) != true) status("Disconnected",false)
    }
}

@Composable
fun TvRemote() {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf(emptyList<String>()) }
    var info by remember { mutableStateOf("Choose a TV on your Wi-Fi") }
    var ready by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<TvSession?>(null) }
    var volume by remember { mutableFloatStateOf(20f) }
    var message by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    DisposableEffect(selected,attempt) {
        var active = true
        val s = if (lanHost(selected)) TvSession(ctx,selected) { msg, ok -> scope.launch { if (active) { info=msg; ready=ok } } } else null
        session=s; ready=false
        if(s!=null){ info="Approve pairing on your TV"; s.connect() }
        onDispose { active=false; s?.close() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("LG Smart TV",style=MaterialTheme.typography.headlineMedium)
        Text(info)
        Button(onClick={scope.launch { info="Searching Wi-Fi…"; devices=runCatching { discover() }.getOrDefault(emptyList()); info=if(devices.isEmpty()) "No TVs found. Enter the TV address." else "Choose a TV" }}){Text("Discover TVs")}
        devices.forEach { TextButton(onClick={host=it; selected=it; attempt++}){Text(it)} }
        OutlinedTextField(host,{host=it.trim()},label={Text("TV IP address")},singleLine=true)
        Button(enabled=lanHost(host),onClick={selected=host; attempt++}){Text("Connect")}
        if(ready) {
            Text("Set volume: "+volume.toInt())
            Slider(value=volume,onValueChange={volume=it},valueRange=0f..100f,onValueChangeFinished={session?.send("audio/setVolume",JSONObject().put("volume",volume.toInt()))})
            for ((label,uri) in listOf("Volume up" to "audio/volumeUp","Volume down" to "audio/volumeDown","Play" to "media.controls/play","Pause" to "media.controls/pause","Power off" to "system/turnOff")) {
                OutlinedButton(onClick={session?.send(uri)}){Text(label)}
            }
            Row { for ((label,on) in listOf("Mute" to true,"Unmute" to false)) TextButton(onClick={session?.send("audio/setMute",JSONObject().put("mute",on))}){Text(label)} }
            for ((label,id) in listOf("YouTube" to "youtube.leanback.v4","Netflix" to "netflix","Prime Video" to "amazon")) TextButton(onClick={session?.send("system.launcher/launch",JSONObject().put("id",id))}){Text(label)}
            Row { (1..4).forEach { n -> TextButton(onClick={session?.send("tv/switchInput",JSONObject().put("inputId","HDMI_"+n))}){Text("HDMI "+n)} } }
            OutlinedTextField(message,{message=it},label={Text("TV notification")})
            Button(enabled=message.isNotBlank(),onClick={session?.send("system.notifications/createToast",JSONObject().put("message",message))}){Text("Show on TV")}
        }
    }
}
