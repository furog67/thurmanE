package com.elderlycaller

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
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

    private var callEnded = false
    private var callStarted = false
    // Set to true the first time InCallService reports any in-progress call state
    // for this session. Guards against a stale STATE_DISCONNECTED left in the
    // StateFlow from a previous call triggering an immediate finish().
    private var callRegistered = false
    private val callStatus = mutableStateOf("Calling…")
    private val speakerOn = mutableStateOf(false)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val phone = intent.getStringExtra(EXTRA_PHONE) ?: run { finish(); return }
        val name  = intent.getStringExtra(EXTRA_NAME)  ?: ""
        val image = intent.getStringExtra(EXTRA_IMAGE) ?: ""

        telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
        audioManager   = getSystemService(AUDIO_SERVICE)   as AudioManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        onBackPressedDispatcher.addCallback(this) {
            if (callEnded) finish()
            // else swallow — user must tap HANG UP
        }

        // Collect authoritative call state from InCallService — no spurious
        // initial-value callback unlike TelephonyManager's PhoneStateListener.
        lifecycleScope.launch {
            EasyCallerInCallService.callState.collect { handleCallState(it) }
        }

        placeCall(phone)
        watchSpeakerState()

        setContent {
            ElderlyCallerTheme {
                CallingScreen(
                    callerName  = name,
                    callerImage = image,
                    status      = callStatus.value,
                    speakerOn   = speakerOn.value,
                    onHangUp    = ::endCall
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
            // Only auto-hangup if the call was never answered — once callStarted
            // is true the recipient is on the line and must not be cut off.
            if (!callStarted && !callEnded) endCall()
        }
    }

    private fun enableSpeaker() {
        if (audioManager.isWiredHeadsetOn) return
        // Calls placed through Telecom (required once Easy Caller is the default
        // dialer) have their audio route managed by Telecom itself — setting
        // AudioManager.isSpeakerphoneOn directly gets silently overridden. The
        // InCallService's setAudioRoute() is the only API that actually sticks.
        val service = EasyCallerInCallService.activeService
        if (service != null) {
            service.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        } else {
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun resetAudio() {
        val service = EasyCallerInCallService.activeService
        if (service != null) {
            service.setAudioRoute(CallAudioState.ROUTE_EARPIECE)
        } else {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    // Polls the actual OS audio state rather than trusting what we asked for,
    // so the label reflects reality if a headset/Bluetooth device changes the
    // route out from under us mid-call.
    private fun watchSpeakerState() {
        lifecycleScope.launch {
            while (!callEnded) {
                val service = EasyCallerInCallService.activeService
                speakerOn.value = if (service != null) {
                    service.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
                } else {
                    audioManager.isSpeakerphoneOn
                }
                delay(500)
            }
        }
    }

    fun endCall() {
        if (callEnded) return
        callEnded = true
        // Prefer Call.disconnect() via InCallService; fall back to TelecomManager
        val call = EasyCallerInCallService.activeCall
        if (call != null) {
            call.disconnect()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { telecomManager.endCall() }
        }
        resetAudio()
        finish()
    }

    private fun handleCallState(state: Int) {
        when (state) {
            Call.STATE_CONNECTING,
            Call.STATE_DIALING,
            Call.STATE_RINGING -> callRegistered = true
            Call.STATE_ACTIVE -> {
                callRegistered = true
                callStarted = true
                callStatus.value = "Connected"
                enableSpeaker()
            }
            Call.STATE_DISCONNECTED,
            Call.STATE_DISCONNECTING -> {
                // Only finish if InCallService has confirmed this call is live —
                // without callRegistered, STATE_DISCONNECTED is stale from a prior call.
                if (!callEnded && callRegistered) {
                    callEnded = true
                    resetAudio()
                    finish()
                }
            }
        }
    }
}

// ── Compose UI ────────────────────────────────────────────────────────────────

@Composable
private fun CallingScreen(
    callerName: String,
    callerImage: String,
    status: String,
    speakerOn: Boolean,
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
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (speakerOn) "🔊 Speakerphone ON" else "🔇 Speakerphone OFF",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = if (speakerOn) Color(0xFFA5D6A7) else Color(0xFFEF9A9A),
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
