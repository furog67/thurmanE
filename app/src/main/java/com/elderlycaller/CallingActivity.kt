package com.elderlycaller

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.WindowManager
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
import coil.compose.AsyncImage
import com.elderlycaller.ui.theme.ElderlyCallerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    private var callEnded = false
    private val callStatus = mutableStateOf("Calling…")

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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        onBackPressedDispatcher.addCallback(this) {
            if (callEnded) finish()
            // else swallow — user must tap HANG UP
        }

        registerCallListener()
        placeCall(phone)

        setContent {
            ElderlyCallerTheme {
                CallingScreen(
                    callerName  = name,
                    callerImage = image,
                    status      = callStatus.value,
                    onHangUp    = ::endCall
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterCallListener()
        if (!callEnded) resetAudio()
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
        // Prefer Call.disconnect() via InCallService; fall back to TelecomManager
        EasyCallerInCallService.activeCall?.disconnect()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { telecomManager.endCall() }
            }
        resetAudio()
        finish()
    }

    private fun handleState(state: Int) {
        runOnUiThread {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    callStatus.value = "Connected"
                    enableSpeaker()
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (!callEnded) {
                        callEnded = true
                        resetAudio()
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

// ── Compose UI ────────────────────────────────────────────────────────────────

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
            Text(
                text = "HANG UP",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
