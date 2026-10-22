package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.engine.MangaTranslationEngine
import com.example.data.model.BubbleType
import com.example.data.model.MangaBubble
import com.example.util.OfflineJapaneseTtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class LiveMangaOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null

    // Floating Control Pill View
    private var floatingPillView: View? = null
    private var pillParams: WindowManager.LayoutParams? = null

    // Full-screen transparent overlay for translated speech bubbles
    private var bubbleOverlayContainer: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // MediaProjection & VirtualDisplay
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageReaderThread: HandlerThread? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val isFrameRequested = AtomicBoolean(false)
    @Volatile private var latestFrameBitmap: Bitmap? = null
    private val frameLock = java.lang.Object()

    // Auto-diff state
    private var isAutoDiffActive = true
    private var lastPerceptualHash: Long = 0L
    private var isScreenMoving = false
    private var lastMovementTime = 0L
    private var samplingJob: Job? = null

    // Touch pass-through state (true = touches pass through to underlying reader app)
    private var isTouchPassThrough = true

    // Offline TTS
    private var ttsManager: OfflineJapaneseTtsManager? = null
    private var lastDetectedJapanese: String = ""

    // Bubbles currently displayed on screen
    private var activeBubbles = listOf<MangaBubble>()
    private var isOverlayVisible = true

    companion object {
        const val CHANNEL_ID = "manga_live_overlay_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.example.service.ACTION_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        val isRunning: Boolean
            get() = _isRunningFlow.value

        fun stop(context: Context) {
            _isRunningFlow.value = false
            try {
                val intent = Intent(context, LiveMangaOverlayService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                try {
                    val stopIntent = Intent(context, LiveMangaOverlayService::class.java)
                    context.stopService(stopIntent)
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        ttsManager = OfflineJapaneseTtsManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopLiveService()
            return START_NOT_STICKY
        }

        // Immediate foreground promotion required by Android
        startForegroundWithMediaProjection()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            stopLiveService()
            return START_NOT_STICKY
        }

        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                stopLiveService()
                return START_NOT_STICKY
            }
            mediaProjection = projection

            // Mandatory on Android 14+ (API 34+): Must register MediaProjection.Callback before creating VirtualDisplay
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopLiveService()
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))

            _isRunningFlow.value = true

            // Setup overlay views:
            // 1. Container added FIRST with FLAG_NOT_TOUCHABLE so background screen receives touches
            setupBubbleOverlayContainer()
            // 2. Control Pill added SECOND so it sits above container and receives user clicks
            setupFloatingPill()
            setupVirtualDisplay()
            startAutoDiffLoop()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to start live overlay: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            stopLiveService()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createRoundedBackground(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun stopLiveService() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { stopLiveService() }
            return
        }
        _isRunningFlow.value = false
        samplingJob?.cancel()
        cleanupViews()
        cleanupMediaProjection()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Manga Live Translator Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active screen capture & real-time manga bubble overlay"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithMediaProjection() {
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LiveMangaOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Live Translator Active")
            .setContentText("Translating Japanese manga dialogue over apps...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Live Overlay", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Transparent full-screen overlay for drawing translated bubbles over any app.
     * By default, FLAG_NOT_TOUCHABLE is set so touches freely pass through to the
     * background screen (manga reader, web browser, home screen).
     */
    private fun setupBubbleOverlayContainer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // FLAG_NOT_TOUCHABLE ensures touches pass directly to the underlying manga reader / screen!
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        bubbleOverlayContainer = FrameLayout(this)
        try {
            windowManager?.addView(bubbleOverlayContainer, overlayParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setTouchPassThrough(passThrough: Boolean) {
        // Enforce FLAG_NOT_TOUCHABLE so background screen ALWAYS responds to touch!
        isTouchPassThrough = true
        val container = bubbleOverlayContainer ?: return
        val params = overlayParams ?: return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager?.updateViewLayout(container, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Compact floating control pill with bounded dragging and instant close.
     * Guaranteed width (~230dp) to prevent being pushed off-screen.
     */
    private fun setupFloatingPill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(80)
        }

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = createRoundedBackground(0xF00F172A.toInt(), 20, 0x666366F1.toInt(), 1)
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(12).toFloat()
        }

        // 1. Dedicated Drag Handle
        val dragHandle = TextView(this).apply {
            text = "⠿"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            minWidth = dp(28)
            minHeight = dp(36)
        }

        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = pillParams?.x ?: 0
                        initialY = pillParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val displayMetrics = resources.displayMetrics
                        val pillWidth = floatingPillView?.width ?: dp(210)
                        val pillHeight = floatingPillView?.height ?: dp(44)
                        val maxX = (displayMetrics.widthPixels - pillWidth).coerceAtLeast(0)
                        val maxY = (displayMetrics.heightPixels - pillHeight).coerceAtLeast(0)

                        val newX = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, maxX)
                        val newY = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(dp(20), maxY)

                        pillParams?.x = newX
                        pillParams?.y = newY
                        try {
                            windowManager?.updateViewLayout(floatingPillView, pillParams)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        return true
                    }
                }
                return false
            }
        })

        // 2. Scan Button (primary on-demand translation trigger)
        val scanButton = TextView(this).apply {
            text = "⚡ Scan"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = createRoundedBackground(0xFF4F46E5.toInt(), 12)
            minHeight = dp(36)
            setOnClickListener {
                text = "⏳ ..."
                triggerManualCaptureAndTranslate()
                postDelayed({ text = "⚡ Scan" }, 1200)
            }
        }

        // 3. Hide / Show Translations Toggle Button
        val hideButton = TextView(this).apply {
            text = if (isOverlayVisible) "👁️ Hide" else "🙈 Show"
            setTextColor(if (isOverlayVisible) 0xFFCBD5E1.toInt() else 0xFFFBBF24.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = createRoundedBackground(if (isOverlayVisible) 0x33475569.toInt() else 0x4D78350F.toInt(), 12)
            minHeight = dp(36)
            setOnClickListener {
                isOverlayVisible = !isOverlayVisible
                bubbleOverlayContainer?.visibility = if (isOverlayVisible) View.VISIBLE else View.INVISIBLE
                text = if (isOverlayVisible) "👁️ Hide" else "🙈 Show"
                setTextColor(if (isOverlayVisible) 0xFFCBD5E1.toInt() else 0xFFFBBF24.toInt())
                background = createRoundedBackground(if (isOverlayVisible) 0x33475569.toInt() else 0x4D78350F.toInt(), 12)
                Toast.makeText(
                    this@LiveMangaOverlayService,
                    if (isOverlayVisible) "Translations visible" else "Translations hidden (tap Show to reveal)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 4. Read Japanese Out Loud Button
        val readButton = TextView(this).apply {
            text = "🔊 Read"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = createRoundedBackground(0xFF0284C7.toInt(), 12)
            minHeight = dp(36)
            setOnClickListener {
                readCurrentJapaneseAloud(this)
            }
        }

        // 5. Compact Red Close Button (instant service teardown)
        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = createRoundedBackground(0xFFEF4444.toInt(), 12)
            minWidth = dp(36)
            minHeight = dp(36)
            setOnClickListener {
                Toast.makeText(this@LiveMangaOverlayService, "Live Overlay Stopped", Toast.LENGTH_SHORT).show()
                stopLiveService()
            }
        }

        fun createSpacer(): View = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
        }

        pill.addView(dragHandle)
        pill.addView(createSpacer())
        pill.addView(scanButton)
        pill.addView(createSpacer())
        pill.addView(hideButton)
        pill.addView(createSpacer())
        pill.addView(readButton)
        pill.addView(createSpacer())
        pill.addView(closeButton)

        floatingPillView = pill
        try {
            windowManager?.addView(floatingPillView, pillParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readCurrentJapaneseAloud(button: TextView? = null) {
        val tts = ttsManager
        if (tts == null) {
            Toast.makeText(this, "Japanese Voice (TTS) initializing...", Toast.LENGTH_SHORT).show()
            return
        }

        if (tts.isSpeaking()) {
            tts.stop()
            button?.text = "🔊 Read"
            button?.background = createRoundedBackground(0xFF0284C7.toInt(), 12)
            Toast.makeText(this, "Japanese speech stopped", Toast.LENGTH_SHORT).show()
            return
        }

        val lines = activeBubbles
            .map { it.japanese.trim() }
            .filter { it.isNotBlank() && MangaTranslationEngine.isJapaneseText(it) }

        if (lines.isNotEmpty()) {
            val spokenText = lines.joinToString(separator = "。 ")
            button?.text = "⏹ Stop"
            button?.background = createRoundedBackground(0xFFD97706.toInt(), 12)
            tts.speakJapanese(spokenText)
            Toast.makeText(this, "🇯🇵 Reading aloud:\n$spokenText", Toast.LENGTH_LONG).show()

            val duration = (spokenText.length * 280L).coerceIn(2000L, 10000L)
            button?.postDelayed({
                button.text = "🔊 Read"
                button.background = createRoundedBackground(0xFF0284C7.toInt(), 12)
            }, duration)
            return
        }

        // No bubbles yet: scan current frame first and then speak!
        button?.text = "⏳ ..."
        Toast.makeText(this, "Scanning screen to read Japanese...", Toast.LENGTH_SHORT).show()
        serviceScope.launch(Dispatchers.Default) {
            val bitmap = captureCurrentScreenFrame()
            if (bitmap != null) {
                val result = MangaTranslationEngine.processMangaImage(
                    context = this@LiveMangaOverlayService,
                    bitmap = bitmap
                )
                withContext(Dispatchers.Main) {
                    button?.text = "🔊 Read"
                    activeBubbles = result.bubbles
                    lastDetectedJapanese = result.bubbles.map { it.japanese }.filter { it.isNotBlank() }.joinToString(" ")
                    renderBubblesOnOverlay(result.bubbles, bitmap.width, bitmap.height)

                    val newLines = result.bubbles
                        .map { it.japanese.trim() }
                        .filter { it.isNotBlank() && MangaTranslationEngine.isJapaneseText(it) }

                    if (newLines.isNotEmpty()) {
                        val spokenText = newLines.joinToString(separator = "。 ")
                        button?.text = "⏹ Stop"
                        button?.background = createRoundedBackground(0xFFD97706.toInt(), 12)
                        tts.speakJapanese(spokenText)
                        Toast.makeText(this@LiveMangaOverlayService, "🇯🇵 Reading aloud:\n$spokenText", Toast.LENGTH_LONG).show()

                        val duration = (spokenText.length * 280L).coerceIn(2000L, 10000L)
                        button?.postDelayed({
                            button.text = "🔊 Read"
                            button.background = createRoundedBackground(0xFF0284C7.toInt(), 12)
                        }, duration)
                    } else {
                        Toast.makeText(this@LiveMangaOverlayService, "No Japanese text detected to read", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    button?.text = "🔊 Read"
                    Toast.makeText(this@LiveMangaOverlayService, "Could not capture frame to read", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupVirtualDisplay() {
        val proj = mediaProjection ?: return
        val metrics = resources.displayMetrics
        val width = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val height = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
        val density = if (metrics.densityDpi > 0) metrics.densityDpi else 320

        // Continuous background draining prevents VirtualDisplay buffer stalls
        // which eliminates SurfaceSyncGroup 1000ms timeouts and restores background screen touch
        imageReaderThread?.quitSafely()
        val thread = HandlerThread("MangaImageDrainThread").apply { start() }
        imageReaderThread = thread
        val drainHandler = Handler(thread.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    if (isFrameRequested.compareAndSet(true, false)) {
                        val bmp = extractBitmapFromImage(image)
                        synchronized(frameLock) {
                            latestFrameBitmap?.recycle()
                            latestFrameBitmap = bmp
                            frameLock.notifyAll()
                        }
                    }
                    image.close()
                }
            } catch (e: Throwable) {
                // Ignore discarded frame exceptions
            }
        }, drainHandler)

        val vdCallback = object : VirtualDisplay.Callback() {
            override fun onPaused() {}
            override fun onResumed() {}
            override fun onStopped() {}
        }
        virtualDisplay = proj.createVirtualDisplay(
            "MangaLiveDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            vdCallback,
            Handler(Looper.getMainLooper())
        )
    }

    private fun extractBitmapFromImage(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val fullWidth = image.width + rowPadding / pixelStride
            if (fullWidth <= 0 || image.height <= 0) return null

            val bitmap = Bitmap.createBitmap(
                fullWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                cropped
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Auto-Live Diffing Loop:
     * Samples screen every 1.5 seconds.
     * Computes fast perceptual hash (dHash).
     * If user is turning page or scrolling, waits for page to settle.
     * When screen stabilizes (stopped moving), executes OCR & translation!
     */
    private fun startAutoDiffLoop() {
        samplingJob?.cancel()
        samplingJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(600)
                if (!isAutoDiffActive) continue

                val bitmap = captureCurrentScreenFrame() ?: continue
                val currentHash = MangaTranslationEngine.computePerceptualHash(bitmap)

                if (lastPerceptualHash == 0L) {
                    lastPerceptualHash = currentHash
                    processAndDisplayTranslation(bitmap)
                    continue
                }

                val distance = MangaTranslationEngine.hashDistance(lastPerceptualHash, currentHash)
                val now = System.currentTimeMillis()

                if (distance > 5) {
                    // Page turned or user is scrolling
                    isScreenMoving = true
                    lastMovementTime = now
                    lastPerceptualHash = currentHash
                    bitmap.recycle()
                } else if (isScreenMoving && (now - lastMovementTime >= 350)) {
                    // Screen stabilized after page turn (350ms) - Trigger OCR + AI pipeline immediately
                    isScreenMoving = false
                    lastPerceptualHash = currentHash
                    processAndDisplayTranslation(bitmap)
                } else {
                    // No significant change, recycle frame bitmap immediately
                    bitmap.recycle()
                }
            }
        }
    }

    private fun triggerManualCaptureAndTranslate() {
        serviceScope.launch(Dispatchers.Default) {
            val bitmap = captureCurrentScreenFrame()
            if (bitmap != null) {
                processAndDisplayTranslation(bitmap)
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LiveMangaOverlayService, "Capturing screen...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun captureCurrentScreenFrame(): Bitmap? = withContext(Dispatchers.Default) {
        if (imageReader == null || virtualDisplay == null) return@withContext null

        synchronized(frameLock) {
            latestFrameBitmap?.recycle()
            latestFrameBitmap = null
        }
        isFrameRequested.set(true)

        val bmp = synchronized(frameLock) {
            var elapsed = 0L
            while (isFrameRequested.get() && elapsed < 400L) {
                try {
                    frameLock.wait(50)
                } catch (e: InterruptedException) {
                    break
                }
                elapsed += 50L
            }
            val frame = latestFrameBitmap
            latestFrameBitmap = null
            frame
        }
        bmp
    }

    private suspend fun processAndDisplayTranslation(bitmap: Bitmap) {
        try {
            val result = MangaTranslationEngine.processMangaImage(
                context = this@LiveMangaOverlayService,
                bitmap = bitmap
            )

            withContext(Dispatchers.Main) {
                activeBubbles = result.bubbles
                lastDetectedJapanese = result.bubbles.map { it.japanese }.filter { it.isNotBlank() }.joinToString(" ")
                renderBubblesOnOverlay(result.bubbles, bitmap.width, bitmap.height)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                bitmap.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Renders translated speech bubbles directly over the manga page on top of any app.
     * In Tap Mode, tapping any translated bubble reads the original Japanese sentence aloud
     * using Android's offline Japanese TextToSpeech engine!
     */
    private fun renderBubblesOnOverlay(bubbles: List<MangaBubble>, screenWidth: Int, screenHeight: Int) {
        val container = bubbleOverlayContainer ?: return
        container.removeAllViews()
        container.visibility = if (isOverlayVisible) View.VISIBLE else View.INVISIBLE

        for (bubble in bubbles) {
            val bubbleLeft = (bubble.xMinFraction * screenWidth).toInt()
            val bubbleTop = (bubble.yMinFraction * screenHeight).toInt()
            val bubbleWidth = ((bubble.xMaxFraction - bubble.xMinFraction) * screenWidth).toInt().coerceAtLeast(dp(70))
            val bubbleHeight = ((bubble.yMaxFraction - bubble.yMinFraction) * screenHeight).toInt().coerceAtLeast(dp(36))

            val bubbleBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xF2FFFFFF.toInt())
                setStroke(dp(1), 0xFFCBD5E1.toInt())
                cornerRadius = dp(8).toFloat()
            }

            val bubbleView = TextView(this).apply {
                text = bubble.english
                textSize = if (bubble.bubbleType == BubbleType.SFX) 12f else 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (bubble.bubbleType == BubbleType.SFX) 0xFFF43F5E.toInt() else 0xFF0F172A.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = bubbleBg
                elevation = dp(4).toFloat()

                // Interactive Tap: Read original Japanese aloud via offline TTS
                setOnClickListener {
                    ttsManager?.speakJapanese(bubble.japanese)
                    Toast.makeText(
                        this@LiveMangaOverlayService,
                        "🇯🇵 ${bubble.japanese}\n🔊 Reading Japanese aloud...",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            val params = FrameLayout.LayoutParams(bubbleWidth, bubbleHeight).apply {
                leftMargin = bubbleLeft
                topMargin = bubbleTop
            }

            container.addView(bubbleView, params)
        }
    }

    private fun cleanupMediaProjection() {
        try {
            isFrameRequested.set(false)
            synchronized(frameLock) {
                latestFrameBitmap?.recycle()
                latestFrameBitmap = null
                frameLock.notifyAll()
            }
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            imageReaderThread?.quitSafely()
            imageReaderThread = null
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
            projectionCallback = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cleanupViews() {
        try {
            floatingPillView?.let { view ->
                try {
                    if (view.isAttachedToWindow) {
                        windowManager?.removeViewImmediate(view)
                    } else {
                        windowManager?.removeView(view)
                    }
                } catch (e: Exception) {
                    try { windowManager?.removeView(view) } catch (_: Exception) {}
                }
            }
            floatingPillView = null

            bubbleOverlayContainer?.let { container ->
                try {
                    if (container.isAttachedToWindow) {
                        windowManager?.removeViewImmediate(container)
                    } else {
                        windowManager?.removeView(container)
                    }
                } catch (e: Exception) {
                    try { windowManager?.removeView(container) } catch (_: Exception) {}
                }
            }
            bubbleOverlayContainer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLiveService()
        ttsManager?.shutdown()
        ttsManager = null
    }
}
