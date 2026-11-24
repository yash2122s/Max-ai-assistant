package com.example.share

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun sharedImage(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
    @Suppress("DEPRECATION")
    val uri = (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
        ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
    return uri?.takeIf { it.scheme == "content" && !it.authority.isNullOrBlank() }
}

internal suspend fun readScreenshot(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000L)
    val image = InputImage.fromFilePath(context, uri)
    val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    suspendCancellableCoroutine { continuation ->
        try {
            reader.process(image).addOnCompleteListener { task ->
                reader.close()
                if (continuation.isActive) {
                    if (task.isSuccessful) continuation.resume(task.result.text)
                    else continuation.resumeWithException(task.exception ?: IllegalStateException("Could not read screenshot"))
                }
            }
        } catch (e: Exception) {
            reader.close()
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
}
