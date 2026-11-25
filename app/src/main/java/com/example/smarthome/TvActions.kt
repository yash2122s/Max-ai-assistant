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
internal fun magicPacket(s:String):ByteArray {
    require(validMac(s))
    val mac=s.split(":").map { it.toInt(16).toByte() }
    return ByteArray(102){ if(it<6) 0xff.toByte() else mac[(it-6)%6] }
}
internal suspend fun wakeTv(mac:String,address:InetAddress=InetAddress.getByName("255.255.255.255"),port:Int=9)=withContext(Dispatchers.IO){
    val bytes=magicPacket(mac)
    DatagramSocket().use { socket -> socket.broadcast=true;socket.send(DatagramPacket(bytes,bytes.size,address,port)) }
}
private suspend fun runTv(ctx:Context,host:String,action:String,value:String):String {
    require(lanHost(host)){"Pair a TV in Smart Home first"}
    val profiles=ctx.getSharedPreferences("tv_devices",Context.MODE_PRIVATE)
    if(action=="on"){ wakeTv(profiles.getString("mac_"+host,"").orEmpty()); return "Wake packet sent" }
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
    val answer=CompletableDeferred<String>()
    lateinit var session:TvSession
    session=TvSession(ctx,host){ msg,ready ->
        when {
            msg=="Connected" -> session.send(uri,data)
            msg=="TV acknowledged command" || msg=="Volume verified" -> answer.complete(msg)
            else -> answer.completeExceptionally(IllegalStateException(msg))
        }
    }
    return try { session.connect();withTimeout(6500){answer.await()} } finally {session.close()}
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
}
class TvAlarm:BroadcastReceiver(){
    override fun onReceive(ctx:Context,intent:Intent){
        val result=goAsync()
        CoroutineScope(Dispatchers.IO).launch { try {runTv(ctx,intent.getStringExtra("host").orEmpty(),intent.getStringExtra("action").orEmpty(),intent.getStringExtra("message").orEmpty())}
            catch(e:Exception){android.util.Log.w("LgTv","Scheduled TV command failed",e)} finally {result.finish()} }
    }
}
