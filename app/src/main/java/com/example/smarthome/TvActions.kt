package com.example.smarthome

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import com.example.automation.tools.*
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.*
import java.util.Calendar

internal fun validMac(s:String)=Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(s)
internal fun magicPacket(s:String)=WakeOnLanManager.magicPacket(s)
internal suspend fun wakeTv(mac:String,address:InetAddress=InetAddress.getByName("255.255.255.255"),port:Int=9)=withContext(Dispatchers.IO){
    val bytes=magicPacket(mac)
    DatagramSocket().use { socket -> socket.broadcast=true;socket.send(DatagramPacket(bytes,bytes.size,address,port)) }
}
private suspend fun runTv(ctx:Context,host:String,action:String,value:String):String {
    require(lanHost(host)){"Pair a TV in Smart Home first"}
    val profiles=ctx.getSharedPreferences("tv_devices",Context.MODE_PRIVATE)
    if(action=="on") {
        if(runCatching { awaitTvConnection(ctx,host,5000) }.isSuccess) return "TV already connected"
        val mac=profiles.getString("mac_"+host,"").orEmpty()
        WakeOnLanManager.wake(ctx,mac)
        return awaitTvConnection(ctx,host)
    }
    if(action=="status" || action=="reconnect") return awaitTvConnection(ctx,host, if(action=="status") 5000 else 60000)
    val data=JSONObject()
    val uri=when(action){
        "off" -> "system/turnOff"
        "mute","unmute" -> {data.put("mute",action=="mute");"audio/setMute"}
        "volume" -> {data.put("volume",requireNotNull(value.toIntOrNull()?.takeIf { it in 0..100 }));"audio/setVolume"}
        "play","pause" -> "media.controls/"+action
        "youtube","netflix","prime" -> { data.put("id",mapOf("youtube" to "youtube.leanback.v4","netflix" to "netflix","prime" to "amazon")[action]);"system.launcher/launch" }
        "hdmi" -> {require(value in listOf("1","2","3","4"));data.put("inputId","HDMI_"+value);"tv/switchInput"}
        "reminder" -> {require(value.isNotBlank());data.put("message",value);"system.notifications/createToast"}
        else -> error("Unsupported TV command")
    }
    if(action!="off") runCatching { awaitTvConnection(ctx,host,5000) }.getOrElse {
        val mac=profiles.getString("mac_"+host,"").orEmpty()
        if(!validMac(mac)) throw it
        WakeOnLanManager.wake(ctx,mac)
        awaitTvConnection(ctx,host)
    }
    val answer=CompletableDeferred<String>()
    lateinit var session:TvSession
    session=TvSession(ctx,host){ msg,ready ->
        when {
            msg=="Connected" -> session.send(uri,data)
            msg=="TV acknowledged command" || msg=="Volume verified" -> answer.complete(msg)
            else -> answer.completeExceptionally(IllegalStateException(msg))
        }
    }
    return try { session.connect();withTimeout(10000){answer.await()} } finally {session.close()}
}

private suspend fun awaitTvConnection(ctx:Context,host:String,timeoutMs:Long=60000):String {
    val start=android.os.SystemClock.elapsedRealtime()
    var backoff=700L
    var lastError="TV unreachable"
    while(android.os.SystemClock.elapsedRealtime()-start<timeoutMs) {
        val connected=CompletableDeferred<Boolean>()
        val session=TvSession(ctx,host){ message,ready ->
            if(ready) connected.complete(true)
            else if(message.startsWith("Confirm TV certificate") || message.contains("certificate changed")) {
                lastError=message
                connected.complete(false)
            } else if(message.startsWith("Disconnected")) connected.complete(false)
        }
        try {
            session.connect()
            if(withTimeoutOrNull(4500){connected.await()}==true) return "TV connected"
        } finally {session.close()}
        if(lastError.contains("certificate")) break
        delay(backoff)
        backoff=(backoff*2).coerceAtMost(5000)
    }
    error(lastError)
}
class LgTvTool: Tool {
    override val name="lg_tv"
    override val supportedActions=setOf("LG_TV")
    override val retryPolicy=RetryPolicy.NoRetry
    override val capabilities=ToolCapabilities(supportsPlanner=true,requiresAccessibility=false,requiresNetwork=false,cancellable=true)
    override fun validate(request:ExecutionRequest)=true
    override suspend fun execute(context:Context,request:ExecutionRequest):ToolResult {
        val args=JSONObject(request.arguments.toString())
        val host=context.getSharedPreferences("tv_devices",Context.MODE_PRIVATE).getString("last","").orEmpty()
        return try {ToolResult(success=true,toolName=name,message=runTv(context,host,args.optString("tv_action"),args.optString("value")))}
        catch(e:Exception){ToolResult(success=false,toolName=name,message=e.message ?: "TV command failed")}
    }
}
internal fun scheduleTv(ctx:Context,host:String,action:String,h:Int,m:Int,message:String){
    require(action!="reminder" || message.isNotBlank()){"Enter a TV notification first"}
    if(action=="on") require(validMac(ctx.getSharedPreferences("tv_devices",Context.MODE_PRIVATE).getString("mac_"+host,"").orEmpty())){"Enter TV MAC address first"}
    val time=Calendar.getInstance().apply {set(Calendar.HOUR_OF_DAY,h);set(Calendar.MINUTE,m);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);if(timeInMillis<=System.currentTimeMillis())add(Calendar.DATE,1)}
    val intent=Intent(ctx,TvAlarm::class.java).setData(Uri.parse("max-tv://schedule/"+host+"/"+action+"/"+time.timeInMillis)).putExtra("host",host).putExtra("action",action).putExtra("message",message)
    val pi=PendingIntent.getBroadcast(ctx,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val alarms=ctx.getSystemService(AlarmManager::class.java)
    require(Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms()){"Allow exact alarms in Settings"}
    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time.timeInMillis,pi)
    ctx.getSharedPreferences("tv_schedules",Context.MODE_PRIVATE).edit()
        .putString(host+"/"+action,JSONObject().put("host",host).put("action",action)
            .put("hour",h).put("minute",m).put("message",message).toString()).apply()
}
internal fun restoreTvSchedules(ctx:Context){
    ctx.getSharedPreferences("tv_schedules",Context.MODE_PRIVATE).all.values.forEach { raw ->
        runCatching { JSONObject(raw as String) }.getOrNull()?.let { item ->
            runCatching { scheduleTv(ctx,item.getString("host"),item.getString("action"),
                item.getInt("hour"),item.getInt("minute"),item.optString("message")) }
        }
    }
}
class TvAlarm:BroadcastReceiver(){
    override fun onReceive(ctx:Context,intent:Intent){
        val result=goAsync()
        val host=intent.getStringExtra("host").orEmpty()
        val action=intent.getStringExtra("action").orEmpty()
        ctx.getSharedPreferences("tv_schedules",Context.MODE_PRIVATE).edit().remove(host+"/"+action).apply()
        CoroutineScope(Dispatchers.IO).launch { try {runTv(ctx,host,action,intent.getStringExtra("message").orEmpty())}
            catch(e:Exception){android.util.Log.w("LgTv","Scheduled TV command failed",e)} finally {result.finish()} }
    }
}
