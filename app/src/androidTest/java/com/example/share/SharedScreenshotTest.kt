package com.example.share

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AppDatabase
import com.example.data.repository.JarvisRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class SharedScreenshotTest {
    @Test fun sharedImageResolvesToMaxAndProducesASchedulableDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val image = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "max-share-test.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }))
        val title = "Example interview"
        var savedId: Int? = null
        val repo = JarvisRepository(context)
        val dao = AppDatabase.getDatabase(context).reminderDao()
        val before = dao.getAllReminders().map { it.id }.toSet()
        try {
            val bitmap = Bitmap.createBitmap(1200, 500, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 60f }
            canvas.drawText(title, 60f, 120f, paint)
            canvas.drawText("27 November 2099 at 3:00 PM", 60f, 250f, paint)
            requireNotNull(resolver.openOutputStream(image)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val intent = Intent(Intent.ACTION_SEND).setType("image/png").setPackage(context.packageName)
                .putExtra(Intent.EXTRA_STREAM, image).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(ShareReminderActivity::class.java.name, intent.resolveActivity(context.packageManager)?.className)
            val uri = requireNotNull(sharedImage(intent))
            val zone = TimeZone.getDefault()
            val draft = ReminderDraftParser.extract(readScreenshot(context, uri), System.currentTimeMillis(), zone)
            assertEquals(ReminderDraft(title, "2099-11-27", "15:00"), draft)
            assertTrue(dao.getAllReminders().none { it.id !in before && it.message == title })
            val trigger = requireNotNull(ReminderDraftParser.trigger(draft.date, draft.time, "30", zone))
            repo.saveReminder(null, draft.title, trigger)
            val saved = dao.getAllReminders().single { it.id !in before && it.message == title }
            savedId = saved.id
            assertEquals(trigger, saved.triggerAt)
        } finally {
            savedId?.let { repo.deleteReminder(it) }
            resolver.delete(image, null, null)
        }
        Unit
    }
}
