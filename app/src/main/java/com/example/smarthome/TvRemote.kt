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

internal class TvSession(context: Context, val host: String, private val factory: WebSocket.Factory? = null, val status: (String, Boolean) -> Unit) : WebSocketListener() {
    private val profiles=context.getSharedPreferences("tv_devices",Context.MODE_PRIVATE)
    private val prefs = EncryptedSharedPreferences.create(context, "lg_tv_keys", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val secure = !profiles.getBoolean("legacy_"+host,false)
    private val tls = TvTls(host) { prefs.getString("cert_"+host,null) }
    private val transport: WebSocket.Factory by lazy { factory ?: if(secure) tls.client() else OkHttpClient.Builder().followRedirects(false).build() }
    fun trustCertificate() {
        val observed = tls.observed ?: return
        if(prefs.getString("cert_"+host,null)==null) prefs.edit().putString("cert_"+host,observed).apply()
    }
    private var socket: WebSocket? = null
    private val seq = AtomicInteger()
    fun connect() { require(lanHost(host)); val endpoint=if(secure) "wss://"+host+":3001" else "ws://"+host+":3000"; socket = transport.newWebSocket(Request.Builder().url(endpoint).build(), this) }
    fun close() { socket?.cancel() }
    override fun onOpen(ws: WebSocket, response: Response) {
        val permissions = org.json.JSONArray(listOf("CONTROL_AUDIO","CONTROL_POWER","CONTROL_INPUT_MEDIA_PLAYBACK","CONTROL_INPUT_TV","LAUNCH","WRITE_NOTIFICATION_TOAST","READ_CURRENT_CHANNEL","READ_RUNNING_APPS","CONTROL_MOUSE_AND_KEYBOARD"))
        val payload = JSONObject().put("pairingType","PROMPT").put("manifest",JSONObject().put("manifestVersion",1).put("appVersion","1.0").put("permissions",permissions))
        prefs.getString(host,null)?.let { payload.put("client-key",it) }
        ws.send(JSONObject().put("type","register").put("id","pair").put("payload",payload).toString())
    }
    @Volatile private var ready = false
    override fun onMessage(ws: WebSocket, text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val data = msg.optJSONObject("payload")
        when (msg.optString("type")) {
            "registered" -> { data?.optString("client-key")?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString(host,it).apply() }; profiles.edit().putBoolean("paired_"+host,true).apply(); ready=true; status("Connected",true) }
            "error" -> status(msg.optString("error","TV rejected command"),ready)
            "response" -> if (checks.containsKey(msg.optString("id"))) {
                val expected=checks.remove(msg.optString("id"))
                if(expected!=null && data?.optBoolean("returnValue")==true) {
                    val id=seq.incrementAndGet().toString()
                    readings[id]=expected
                    ws.send(JSONObject().put("type","request").put("id",id).put("uri","ssap://audio/getVolume").toString())
                } else status("TV rejected volume change",ready)
            } else if(readings.containsKey(msg.optString("id"))) {
                val expected=readings.remove(msg.optString("id"))
                val actual=(data?.optJSONObject("volumeStatus") ?: data)?.optInt("volume",-1)
                status(if(actual==expected) "Volume verified" else "Volume could not be verified",ready)
            } else if (data?.has("socketPath")==true) { val path=data.optString("socketPath")
                val url=runCatching { java.net.URI(path) }.getOrNull()
                val key=keys.remove(msg.optString("id"))
                if(key!=null && url?.host==host && url.scheme==(if(secure) "wss" else "ws") && url.port==(if(secure) 3001 else 3000)) transport.newWebSocket(Request.Builder().url(path).build(),object:WebSocketListener(){
                    override fun onOpen(remote:WebSocket,response:Response){remote.send("type:button\nname:"+key+"\n\n"); remote.close(1000,"Done"); status("Navigation sent",true)}
                }) else status("Unsupported navigation endpoint",ready)
            } else if (msg.optString("id") != "pair") status(if (data?.optBoolean("returnValue")==true) "TV acknowledged command" else "TV did not confirm command",ready)
        }
    }
    override fun onFailure(ws: WebSocket,t: Throwable,response: Response?) { ready=false; val seen=tls.observed; val saved=prefs.getString("cert_"+host,null); status(when { secure && seen!=null && saved==null -> "Confirm TV certificate SHA-256: "+seen; secure && seen!=null && seen!=saved -> "TV certificate changed. Connection blocked."; else -> "Disconnected: "+(t.message ?: t.javaClass.simpleName) },false) }
    override fun onClosed(ws: WebSocket,code: Int,reason: String) { ready=false; status("Disconnected",false) }
    private val checks = java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val readings = java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val keys = java.util.concurrent.ConcurrentHashMap<String,String>()
    fun key(key: String) {
        require(key in listOf("UP","DOWN","LEFT","RIGHT","ENTER","BACK","HOME"))
        val id = seq.incrementAndGet().toString()
        keys[id]=key
        socket?.send(JSONObject().put("type","request").put("id",id).put("uri","ssap://com.webos.service.networkinput/getPointerInputSocket").toString())
    }
    fun send(uri: String, data: JSONObject = JSONObject()) {
        val id=seq.incrementAndGet().toString()
        if(uri=="audio/setVolume") checks[id]=data.getInt("volume")
        if (!ready || socket?.send(JSONObject().put("type","request").put("id",id).put("uri","ssap://"+uri).put("payload",data).toString()) != true) status("Disconnected",false)
    }
}

@Composable
fun TvRemote() {
    val activity = LocalContext.current
    val ctx = activity.applicationContext
    val scope = rememberCoroutineScope()
    val profiles = remember { ctx.getSharedPreferences("tv_devices", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(profiles.getString("last", "").orEmpty()) }
    var mac by remember(host) { mutableStateOf(profiles.getString("mac_" + host, "").orEmpty()) }
    var selected by remember { mutableStateOf(host) }
    var devices by remember { mutableStateOf(emptyList<String>()) }
    var info by remember { mutableStateOf("Choose a TV on your Wi-Fi") }
    var ready by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<TvSession?>(null) }
    var volume by remember { mutableFloatStateOf(20f) }
    var message by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var legacy by remember(host) { mutableStateOf(profiles.getBoolean("legacy_" + host, false)) }

    DisposableEffect(selected, attempt) {
        var active = true
        val connection = if (lanHost(selected)) TvSession(ctx, selected) { status, connected ->
            scope.launch {
                if (active) {
                    info = status
                    ready = connected
                    if (status == "Connected") profiles.edit().putString("last", selected).apply()
                }
            }
        } else null
        session = connection
        ready = false
        if (connection != null) {
            info = "Approve pairing on your TV"
            connection.connect()
        }
        onDispose { active = false; connection?.close() }
    }
    LaunchedEffect(info, selected) {
        if (info.startsWith("Disconnected") && lanHost(selected)) {
            delay(8000)
            attempt++
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("LG Smart TV", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (ready) "Connected" else "TV connection", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Hide setup" else "Setup") }
                }
                Text(if (ready) selected else info, style = MaterialTheme.typography.bodyMedium)
                if (info.startsWith("Confirm TV certificate")) {
                    Button(onClick = { session?.trustCertificate(); attempt++ }) { Text("Trust this TV and pair") }
                }
                if (showSettings || !ready) {
                    Button(onClick = {
                        scope.launch {
                            info = "Searching Wi-Fi…"
                            devices = runCatching { discover() }.getOrDefault(emptyList())
                            info = if (devices.isEmpty()) "No TVs found. Enter the TV address." else "Choose a TV"
                        }
                    }) { Text("Discover TVs") }
                    (devices + profiles.all.keys.filter { it.startsWith("paired_") }
                        .map { it.removePrefix("paired_") }).distinct().forEach { address ->
                        TextButton(onClick = { host = address; selected = address; attempt++ }) { Text(address) }
                    }
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("TV IP address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(enabled = lanHost(host), onClick = { selected = host; attempt++ }) { Text("Connect") }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = legacy, onCheckedChange = {
                            legacy = it
                            profiles.edit().putBoolean("legacy_" + host, it).apply()
                        })
                        Text("Older TV connection (port 3000)")
                    }
                    OutlinedTextField(mac, { mac = it }, label = { Text("TV MAC address for wake-up") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
        }

        if (ready) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Remote", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { session?.send("system/turnOff") }) { Text("Power off") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        RemoteKey("▲", Modifier.width(68.dp)) { session?.key("UP") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        RemoteKey("◀", Modifier.width(68.dp)) { session?.key("LEFT") }
                        RemoteKey("OK", Modifier.width(68.dp)) { session?.key("ENTER") }
                        RemoteKey("▶", Modifier.width(68.dp)) { session?.key("RIGHT") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        RemoteKey("▼", Modifier.width(68.dp)) { session?.key("DOWN") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { session?.key("BACK") }) { Text("Back") }
                        TextButton(onClick = { session?.key("HOME") }) { Text("Home") }
                        TextButton(onClick = { session?.send("media.controls/play") }) { Text("Play") }
                        TextButton(onClick = { session?.send("media.controls/pause") }) { Text("Pause") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Volume  ${volume.toInt()}", style = MaterialTheme.typography.titleMedium)
                    Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f,
                        onValueChangeFinished = { session?.send("audio/setVolume", JSONObject().put("volume", volume.toInt())) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { session?.send("audio/volumeDown") }) { Text("−") }
                        OutlinedButton(onClick = { session?.send("audio/setMute", JSONObject().put("mute", true)) }) { Text("Mute") }
                        OutlinedButton(onClick = { session?.send("audio/setMute", JSONObject().put("mute", false)) }) { Text("Unmute") }
                        OutlinedButton(onClick = { session?.send("audio/volumeUp") }) { Text("+") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Apps", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for ((label, id) in listOf("YouTube" to "youtube.leanback.v4", "Netflix" to "netflix", "Prime" to "amazon")) {
                            TextButton(onClick = { session?.send("system.launcher/launch", JSONObject().put("id", id)) }) { Text(label) }
                        }
                    }
                    Text("Input", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (1..4).forEach { n ->
                            TextButton(onClick = { session?.send("tv/switchInput", JSONObject().put("inputId", "HDMI_" + n)) }) { Text("HDMI $n") }
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TV notification", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(message, { message = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Message to show on TV") })
                    Button(enabled = message.isNotBlank(), onClick = {
                        session?.send("system.notifications/createToast", JSONObject().put("message", message))
                    }) { Text("Show on TV") }
                }
            }
        }
        if (lanHost(host) && validMac(mac)) {
            OutlinedButton(onClick = {
                profiles.edit().putString("mac_" + host, mac).apply()
                scope.launch { info = runCatching { wakeTv(mac); "Wake packet sent" }.getOrElse { "Wake failed" } }
            }) { Text("Power on TV") }
        }
        if (lanHost(selected)) {
            TextButton(onClick = { showSchedule = !showSchedule }) { Text(if (showSchedule) "Hide schedules" else "Schedules") }
            if (showSchedule) {
                for (action in listOf("on", "off", "reminder")) {
                    OutlinedButton(onClick = {
                        android.app.TimePickerDialog(activity, { _, hour, minute ->
                            if (action == "on" && host == selected && validMac(mac))
                                profiles.edit().putString("mac_" + selected, mac).apply()
                            info = runCatching { scheduleTv(ctx, selected, action, hour, minute, message); "Scheduled" }
                                .getOrElse { it.message ?: "Could not schedule" }
                        }, 20, 0, true).show()
                    }) { Text("Schedule $action") }
                }
            }
        }
    }
}

@Composable
private fun RemoteKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(52.dp),
        contentPadding = PaddingValues(0.dp)) { Text(label) }
}
