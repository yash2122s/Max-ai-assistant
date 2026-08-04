package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType

class FloatingHudService : Service() {

    private var windowManager: WindowManager? = null
    private var hudOverlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        setupFloatingHud()
        ServiceRegistry.register(ServiceType.OVERLAY, this)
    }

    private fun startAsForeground() {
        val channelId = "floating_hud_channel"
        val channelName = "Max AI Floating HUD"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Max AI Assistant Active")
            .setContentText("Floating HUD is ready.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupFloatingHud() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 250
        }

        // Futuristic Glassmorphic Capsule Container Layout
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 20, 44, 20)

            // Neon Glowing Gradient Background
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 60f
                setColor(Color.parseColor("#E60B0E14")) // Glassmorphic translucent dark
                setStroke(3, Color.parseColor("#00E5FF")) // Glowing Cyan stroke
            }
            elevation = 16f
        }

        // Glowing Pulsing Orb Icon
        val orbIcon = TextView(this).apply {
            text = "⚡"
            textSize = 16f
            setPadding(0, 0, 16, 0)
        }

        // Sleek Bold Futuristic Label
        val labelText = TextView(this).apply {
            text = "MAX AI"
            textSize = 13f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }

        container.addView(orbIcon)
        container.addView(labelText)

        // Pulse Animation for the AI Orb
        val pulseAnim = ScaleAnimation(
            0.92f, 1.08f, 0.92f, 1.08f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 900
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        orbIcon.startAnimation(pulseAnim)

        // Interactive Touch & Smooth Drag Physics
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = true

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isClick = true
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        v?.animate()?.scaleX(0.95f)?.scaleY(0.95f)?.setDuration(100)?.start()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                        }
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(hudOverlayView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.start()
                        if (isClick) {
                            // Launch Main Activity on tap
                            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            if (launchIntent != null) {
                                startActivity(launchIntent)
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        hudOverlayView = container

        try {
            windowManager?.addView(hudOverlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hudOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 4040
    }
}
