package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R

@Composable
fun NeuralEngineVideoPlayer(
    modifier: Modifier = Modifier,
    videoResId: Int = R.raw.brain_neural_engine
) {
    val context = LocalContext.current
    val videoUri = remember(videoResId) {
        Uri.parse("android.resource://${context.packageName}/$videoResId")
    }

    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(videoUri)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.setVolume(0f, 0f) // Mute audio for seamless background loop
                    start()
                }
            }
        },
        update = { videoView ->
            if (!videoView.isPlaying) {
                videoView.start()
            }
        },
        modifier = modifier
    )
}
