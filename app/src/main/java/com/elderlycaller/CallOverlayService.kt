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

        fun start(context: Context, phoneNumber: String, callerName: String, callerImage: String) {
            val intent = Intent(context, CallOverlayService::class.java).apply {
                putExtra(EXTRA_PHONE, phoneNumber)
                putExtra(EXTRA_NAME, callerName)
                putExtra(EXTRA_IMAGE, callerImage)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Fallback: open phone app directly if service can't start
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { context.startActivity(callIntent) }
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var telecomManager: TelecomManager
    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager

    private var overlayRoot: View? = null
    private var statusLabel: TextView? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var noAnswerJob: Job? = null
    private var callEnded = false

    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        @Suppress("DEPRECATION")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state)
    }

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
        val name  = intent.getStringExtra(EXTRA_NAME)  ?: ""
        val image = intent.getStringExtra(EXTRA_IMAGE) ?: ""

        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(name))
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Settings.canDrawOverlays(this)) showOverlay(name, image)

        registerCallListener()
        placeCall(phone)

        return START_NOT_STICKY
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showOverlay(callerName: String, callerImagePath: String) {
        val dm = resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        // ── Root frame (full-screen, dark blue) ───────────────────────────────
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#1A237E"))

        // ── Top content (photo + name + status) ───────────────────────────────
        val topCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Large circular photo
        val photoSize = dp(260)
        val photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val photoCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#283593"))
            setStroke(dp(5), Color.parseColor("#80CBC4"))
        }
        val photoFrame = FrameLayout(this).apply {
            background = photoCircle
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            addView(photoView, FrameLayout.LayoutParams(photoSize, photoSize))
        }

        // Load photo with Coil, clip to circle
        scope.launch {
            runCatching {
                val loader = ImageLoader(this@CallOverlayService)
                val result = loader.execute(
                    ImageRequest.Builder(this@CallOverlayService)
                        .data(callerImagePath)
                        .size(photoSize, photoSize)
                        .allowHardware(false)
                        .build()
                )
                result.drawable?.let { drawable ->
                    val bmp = Bitmap.createBitmap(photoSize, photoSize, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    canvas.drawOval(RectF(0f, 0f, photoSize.toFloat(), photoSize.toFloat()), paint)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    drawable.setBounds(0, 0, photoSize, photoSize)
                    drawable.draw(canvas)
                    photoView.setImageBitmap(bmp)
                }
            }
        }

        val nameView = TextView(this).apply {
            text = callerName
            textSize = 36f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val statusView = TextView(this).apply {
            text = "Calling…"
            textSize = 22f
            setTextColor(Color.parseColor("#80CBC4"))
            gravity = Gravity.CENTER
        }
        statusLabel = statusView

        topCol.addView(photoFrame, LinearLayout.LayoutParams(photoSize, photoSize).also {
            it.bottomMargin = dp(28)
        })
        topCol.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(14) })
        topCol.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Centre the top content block in the upper portion of the screen
        val topParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        ).also { it.bottomMargin = dp(130) } // shift up to leave room for hang-up button
        root.addView(topCol, topParams)

        // ── Large hang-up button pinned to the bottom ─────────────────────────
        val hangUpBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#B71C1C"))
        }
        val hangUp = Button(this).apply {
            text = "HANG UP"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = hangUpBg
            setOnClickListener { endCall() }
        }
        val hangUpParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(100),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).also {
            it.leftMargin  = dp(32)
            it.rightMargin = dp(32)
            it.bottomMargin = dp(48)
        }
        root.addView(hangUp, hangUpParams)

        // ── Add overlay to window ─────────────────────────────────────────────
        val winParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { windowManager.addView(root, winParams) }
        overlayRoot = root
    }

    private fun removeOverlay() {
        overlayRoot?.let {
            runCatching { windowManager.removeView(it) }
            overlayRoot = null
        }
    }

    // ── Call ──────────────────────────────────────────────────────────────────

    private fun placeCall(phoneNumber: String) {
        val uri = Uri.parse("tel:${Uri.encode(phoneNumber)}")
        runCatching { telecomManager.placeCall(uri, android.os.Bundle()) }.onFailure {
            tearDown(); return
        }

        // Speakerphone: try after 2 s (call may not be OFFHOOK yet on all devices)
        scope.launch {
            delay(2_000)
            enableSpeaker()
        }

        // Auto hang-up if unanswered after 45 s
        noAnswerJob = scope.launch {
            delay(NO_ANSWER_MS)
            if (!callEnded) endCall()
        }
    }

    private fun enableSpeaker() {
        if (!audioManager.isWiredHeadsetOn) {
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
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

    // ── Phone state ───────────────────────────────────────────────────────────

    private fun handleState(state: Int) {
        scope.launch {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // Call answered — enable speaker immediately and update status
                    noAnswerJob?.cancel()
                    enableSpeaker()
                    statusLabel?.text = "Connected"
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (!callEnded) {
                        callEnded = true
                        noAnswerJob?.cancel()
                        audioManager.isSpeakerphoneOn = false
                        audioManager.mode = AudioManager.MODE_NORMAL
                        removeOverlay()
                        stopSelf()
                    }
                }
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
            telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterCallListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modernCallback as? TelephonyCallback)?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        unregisterCallListener()
        removeOverlay()
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val chan = NotificationChannel(NOTIF_CHANNEL, "Active Call", NotificationManager.IMPORTANCE_LOW)
            .apply { setSound(null, null) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun buildNotification(callerName: String): Notification =
        Notification.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("Calling $callerName")
            .setContentText("Use the on-screen button to hang up")
            .setOngoing(true)
            .build()
}
