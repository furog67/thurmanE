package com.elderlycaller

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.elderlycaller.ui.theme.ElderlyCallerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor

class CallingActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_NAME  = "name"
        const val EXTRA_IMAGE = "image"

        fun start(context: Context, phone: String, name: String, image: String) {
            context.startActivity(
                Intent(context, CallingActivity::class.java).apply {
                    putExtra(EXTRA_PHONE, phone)
                    putExtra(EXTRA_NAME,  name)
                    putExtra(EXTRA_IMAGE, image)
                }
            )
        }
    }

    private lateinit var telecomManager: TelecomManager
    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var winMgr: WindowManager

    private var callEnded = false
    private var overlayRoot: FrameLayout? = null
    private var statusLabel: TextView? = null
    private val callStatusState = mutableStateOf("Calling…")

    // ── Phone state listener (API 26-30) ─────────────────────────────────────

    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        @Suppress("DEPRECATION")
        override fun onCallStateChanged(state: Int, number: String?) = handleState(state)
    }

    // ── TelephonyCallback (API 31+) ───────────────────────────────────────────

    private val modernCallback: Any? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) buildModernCallback() else null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildModernCallback() =
        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = handleState(state)
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val phone = intent.getStringExtra(EXTRA_PHONE) ?: run { finish(); return }
        val name  = intent.getStringExtra(EXTRA_NAME)  ?: ""
        val image = intent.getStringExtra(EXTRA_IMAGE) ?: ""

        telecomManager   = getSystemService(TELECOM_SERVICE)   as TelecomManager
        audioManager     = getSystemService(AUDIO_SERVICE)     as AudioManager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        winMgr           = getSystemService(WINDOW_SERVICE)    as WindowManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        onBackPressedDispatcher.addCallback(this) {
            if (callEnded) finish()
            // else swallow — user must tap HANG UP
        }

        registerCallListener()

        if (Settings.canDrawOverlays(this)) {
            // Add our full-screen overlay FIRST so it's on top before the system
            // dialer activity appears. TYPE_APPLICATION_OVERLAY sits above all
            // regular Activities, including the phone dialer's in-call screen.
            showOverlay(name, image)
            setContent { ElderlyCallerTheme { Box(Modifier.fillMaxSize().background(Color(0xFF1A237E))) } }
        } else {
            // Fallback: Compose UI. The system dialer may cover it.
            // Admin should grant overlay permission via Admin settings.
            setContent {
                ElderlyCallerTheme {
                    CallingScreen(
                        callerName  = name,
                        callerImage = image,
                        status      = callStatusState.value,
                        onHangUp    = ::endCall
                    )
                }
            }
        }

        placeCall(phone)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterCallListener()
        removeOverlay()
        if (!callEnded) resetAudio()
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showOverlay(callerName: String, callerImagePath: String) {
        val dm = resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val root = FrameLayout(this)
        root.setBackgroundColor(AndroidColor.parseColor("#1A237E"))

        // ── Top column: circular photo + name + status ────────────────────────
        val topCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val photoSize = dp(260)
        val photoView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val photoFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AndroidColor.parseColor("#283593"))
                setStroke(dp(5), AndroidColor.parseColor("#80CBC4"))
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BOUNDS
            addView(photoView, FrameLayout.LayoutParams(photoSize, photoSize))
        }

        // Load and clip photo to circle
        lifecycleScope.launch {
            runCatching {
                val loader = ImageLoader(this@CallingActivity)
                val result = loader.execute(
                    ImageRequest.Builder(this@CallingActivity)
                        .data(callerImagePath).size(photoSize, photoSize).allowHardware(false).build()
                )
                result.drawable?.let { d ->
                    val bmp = Bitmap.createBitmap(photoSize, photoSize, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).let { c ->
                        val p = Paint(Paint.ANTI_ALIAS_FLAG)
                        c.drawOval(RectF(0f, 0f, photoSize.toFloat(), photoSize.toFloat()), p)
                        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                        d.setBounds(0, 0, photoSize, photoSize); d.draw(c)
                    }
                    photoView.setImageBitmap(bmp)
                }
            }
        }

        val nameView = TextView(this).apply {
            text = callerName; textSize = 36f
            setTextColor(AndroidColor.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val statusView = TextView(this).apply {
            text = "Calling…"; textSize = 22f
            setTextColor(AndroidColor.parseColor("#80CBC4"))
            gravity = Gravity.CENTER
        }
        statusLabel = statusView

        topCol.addView(photoFrame, LinearLayout.LayoutParams(photoSize, photoSize)
            .apply { bottomMargin = dp(28) })
        topCol.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(14) })
        topCol.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(topCol, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        ).apply { bottomMargin = dp(130) })

        // ── HANG UP button pinned to bottom ───────────────────────────────────
        val hangUp = Button(this).apply {
            text = "HANG UP"; textSize = 28f
            setTextColor(AndroidColor.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(AndroidColor.parseColor("#B71C1C"))
            }
            setOnClickListener { endCall() }
        }
        root.addView(hangUp, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(100),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { leftMargin = dp(32); rightMargin = dp(32); bottomMargin = dp(48) })

        // ── Add to WindowManager ──────────────────────────────────────────────
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { winMgr.addView(root, lp) }
        overlayRoot = root
    }

    private fun removeOverlay() {
        overlayRoot?.let { runCatching { winMgr.removeView(it) } }
        overlayRoot = null
        statusLabel  = null
    }

    // ── Call management ───────────────────────────────────────────────────────

    private fun placeCall(phone: String) {
        val uri = android.net.Uri.parse("tel:${android.net.Uri.encode(phone)}")
        runCatching { telecomManager.placeCall(uri, Bundle()) }

        lifecycleScope.launch {
            delay(2_000)
            if (!callEnded) enableSpeaker()
        }
        lifecycleScope.launch {
            delay(45_000)
            if (!callEnded) endCall()
        }
    }

    private fun enableSpeaker() {
        if (!audioManager.isWiredHeadsetOn) {
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun resetAudio() {
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun endCall() {
        if (callEnded) return
        callEnded = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { telecomManager.endCall() }
        }
        resetAudio()
        removeOverlay()
        finish()
    }

    private fun handleState(state: Int) {
        runOnUiThread {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    statusLabel?.text = "Connected"
                    callStatusState.value = "Connected"
                    enableSpeaker()
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (!callEnded) {
                        callEnded = true
                        resetAudio()
                        removeOverlay()
                        finish()
                    }
                }
            }
        }
    }

    // ── Telephony listener registration ───────────────────────────────────────

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
}

// ── Fallback Compose UI (used only when overlay permission is not granted) ────

@Composable
private fun CallingScreen(
    callerName: String,
    callerImage: String,
    status: String,
    onHangUp: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A237E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = callerImage,
                contentDescription = callerName,
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .border(5.dp, Color(0xFF80CBC4), CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = callerName,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = status,
                fontSize = 22.sp,
                color = Color(0xFF80CBC4),
                textAlign = TextAlign.Center
            )
        }
        Button(
            onClick = onHangUp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 48.dp)
                .height(100.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(text = "HANG UP", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}
