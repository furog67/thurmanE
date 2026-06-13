package com.elderlycaller

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.*

class CallOverlayService : Service() {

    companion object {
        const val EXTRA_PHONE = "phone_number"
        const val EXTRA_NAME = "caller_name"
        const val EXTRA_IMAGE = "caller_image"
        private const val NOTIF_CHANNEL = "call_channel"
        private const val NOTIF_ID = 42
        private const val NO_ANSWER_MS = 45_000L
        private const val SPEAKER_DELAY_MS = 3_000L

        fun start(context: Context, phoneNumber: String, callerName: String, callerImage: String) {
            val intent = Intent(context, CallOverlayService::class.java).apply {
                putExtra(EXTRA_PHONE, phoneNumber)
                putExtra(EXTRA_NAME, callerName)
                putExtra(EXTRA_IMAGE, callerImage)
            }
            context.startForegroundService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var telecomManager: TelecomManager
    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager

    private var overlayRoot: View? = null
    private var statusLabel: TextView? = null
    private var hangUpButton: Button? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var noAnswerJob: Job? = null
    private var callEnded = false

    // Deprecated but needed for API 26-30
    @Suppress("DEPRECATION")
    private val legacyPhoneListener = object : PhoneStateListener() {
        @Suppress("DEPRECATION")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) =
            handleState(state)
    }

    // Modern callback for API 31+
    private val modernCallback: Any? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state)
            }
        } else null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val phone = intent?.getStringExtra(EXTRA_PHONE) ?: run { stopSelf(); return START_NOT_STICKY }
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val image = intent.getStringExtra(EXTRA_IMAGE) ?: ""

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(name))

        if (Settings.canDrawOverlays(this)) {
            showOverlay(name, image)
        }

        registerCallListener()
        placeCall(phone)

        return START_NOT_STICKY
    }

    // ── Overlay ──────────────────────────────────────────────────────────────

    private fun showOverlay(callerName: String, callerImagePath: String) {
        val dm = resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        // Root frame — same deep blue as the main screen
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#1A237E"))

        // Centre column
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        // Circular photo frame
        val photoFrame = FrameLayout(this)
        val frameSize = dp(200)
        val photoView = ImageView(this)
        photoView.scaleType = ImageView.ScaleType.CENTER_CROP
        photoView.id = View.generateViewId()

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#283593"))
            setStroke(dp(4), Color.parseColor("#80CBC4"))
        }
        photoFrame.background = circle
        photoFrame.clipToOutline = true
        photoFrame.outlineProvider = android.view.ViewOutlineProvider.BOUNDS
        photoFrame.addView(photoView, FrameLayout.LayoutParams(frameSize, frameSize))

        // Load image with Coil inside service
        scope.launch {
            try {
                val loader = ImageLoader(this@CallOverlayService)
                val req = ImageRequest.Builder(this@CallOverlayService)
                    .data(callerImagePath)
                    .size(frameSize, frameSize)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(req)
                result.drawable?.let { drawable ->
                    // Clip to circle
                    val bmp = Bitmap.createBitmap(frameSize, frameSize, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    canvas.drawOval(RectF(0f, 0f, frameSize.toFloat(), frameSize.toFloat()), paint)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    drawable.setBounds(0, 0, frameSize, frameSize)
                    drawable.draw(canvas)
                    photoView.setImageBitmap(bmp)
                }
            } catch (_: Exception) {}
        }

        // Name
        val nameView = TextView(this).apply {
            text = callerName
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        // Animated "Calling…" dots
        val statusView = TextView(this).apply {
            text = "Calling…"
            textSize = 22f
            setTextColor(Color.parseColor("#80CBC4"))
            gravity = Gravity.CENTER
        }
        statusLabel = statusView

        // Big red hang-up button
        val hangUp = Button(this).apply {
            text = "Hang Up"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(50).toFloat()
                setColor(Color.parseColor("#C62828"))
            }
            background = bg
            setPadding(dp(40), dp(16), dp(40), dp(16))
            setOnClickListener { endCall() }
        }
        hangUpButton = hangUp

        // Assemble
        col.addView(photoFrame, LinearLayout.LayoutParams(frameSize, frameSize).also { it.bottomMargin = dp(28) })
        col.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(12) })
        col.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(56) })
        col.addView(hangUp, LinearLayout.LayoutParams(dp(260), dp(72)))

        val colParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(col, colParams)

        val winParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(root, winParams)
            overlayRoot = root
        } catch (_: Exception) {}
    }

    private fun removeOverlay() {
        overlayRoot?.let {
            runCatching { windowManager.removeView(it) }
            overlayRoot = null
        }
    }

    // ── Call ─────────────────────────────────────────────────────────────────

    private fun placeCall(phoneNumber: String) {
        val uri = Uri.parse("tel:${Uri.encode(phoneNumber)}")
        try {
            telecomManager.placeCall(uri, android.os.Bundle())
        } catch (e: SecurityException) {
            tearDown()
            return
        }

        // Enable speakerphone shortly after call starts (if no wired headset)
        scope.launch {
            delay(SPEAKER_DELAY_MS)
            if (!audioManager.isWiredHeadsetOn) {
                audioManager.mode = AudioManager.MODE_IN_CALL
                audioManager.isSpeakerphoneOn = true
            }
        }

        // Auto hang-up if nobody answers within NO_ANSWER_MS
        noAnswerJob = scope.launch {
            delay(NO_ANSWER_MS)
            if (!callEnded) endCall()
        }
    }

    private fun endCall() {
        if (callEnded) return
        callEnded = true
        noAnswerJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { telecomManager.endCall() }
        }
        tearDown()
    }

    private fun tearDown() {
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        removeOverlay()
        stopSelf()
    }

    // ── Phone state ──────────────────────────────────────────────────────────

    private fun handleState(state: Int) {
        if (state == TelephonyManager.CALL_STATE_IDLE && !callEnded) {
            // Call ended by remote party or system
            callEnded = true
            noAnswerJob?.cancel()
            scope.launch {
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
                removeOverlay()
                stopSelf()
            }
        } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            // Call connected — update overlay status text
            scope.launch {
                statusLabel?.text = "Connected"
            }
        }
    }

    private fun registerCallListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modernCallback as? TelephonyCallback)?.let {
                telephonyManager.registerTelephonyCallback(mainExecutor, it)
            }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(legacyPhoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterCallListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modernCallback as? TelephonyCallback)?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(legacyPhoneListener, PhoneStateListener.LISTEN_NONE)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        unregisterCallListener()
        removeOverlay()
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            NOTIF_CHANNEL,
            "Active Call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Manages active phone calls"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun buildNotification(callerName: String): Notification {
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("Calling $callerName")
            .setContentText("Tap Hang Up on screen to end call")
            .setOngoing(true)
            .build()
    }
}
