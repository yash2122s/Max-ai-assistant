package com.example.automation.actions

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

class CalendarAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val action = payload.optString("action", "").uppercase()
        try {
            if (action == "ADD_CALENDAR_EVENT") {
                val title = payload.optString("title", "New Event")
                val description = payload.optString("description", "")
                val startTime = payload.optLong("startTime", System.currentTimeMillis())
                val endTime = payload.optLong("endTime", startTime + 3600000) // 1 hour later
                
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.DTSTART, startTime)
                        put(CalendarContract.Events.DTEND, endTime)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.DESCRIPTION, description)
                        put(CalendarContract.Events.CALENDAR_ID, 1) // Default calendar
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    }
                    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    log("Inserted calendar event directly: $uri")
                } else {
                    // Fallback to Intent
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.Events.DESCRIPTION, description)
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    log("Launched calendar intent to add event: $title")
                }
            }
        } catch (e: Exception) {
            logError("Failed to execute Calendar action: ${e.message}", e)
        }
    }

    fun getCalendarEvents(context: Context): JSONArray {
        val eventsArray = JSONArray()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            log("Read Calendar permission not granted.")
            return eventsArray
        }
        
        try {
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND
            )
            
            // Query events from the last 7 days to the next 7 days
            val now = System.currentTimeMillis()
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(
                (now - 7 * 24 * 3600 * 1000).toString(),
                (now + 7 * 24 * 3600 * 1000).toString()
            )
            
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )
            
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = c.getColumnIndex(CalendarContract.Events.TITLE)
                val descIdx = c.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val startIdx = c.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = c.getColumnIndex(CalendarContract.Events.DTEND)
                
                while (c.moveToNext()) {
                    val event = JSONObject().apply {
                        put("id", if (idIdx >= 0) c.getLong(idIdx) else -1)
                        put("title", if (titleIdx >= 0) c.getString(titleIdx) else "")
                        put("description", if (descIdx >= 0) c.getString(descIdx) else "")
                        put("startTime", if (startIdx >= 0) c.getLong(startIdx) else 0L)
                        put("endTime", if (endIdx >= 0) c.getLong(endIdx) else 0L)
                    }
                    eventsArray.put(event)
                }
            }
        } catch (e: Exception) {
            logError("Failed to query calendar events: ${e.message}", e)
        }
        return eventsArray
    }
}
