package com.elderlycaller

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import androidx.compose.runtime.mutableStateOf
import com.elderlycaller.data.Tile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Singleton call controller. Lives for the process lifetime so that if Telecom
 * recreates MainActivity mid-call (it fires an intent at the default dialer to
 * bring the call UI to front), the new instance restores the call screen
 * immediately from the preserved state here.
 */
object CallManager {

    // Application context — set once from MainActivity.onCreate().
    private var appContext: Context? = null
    private val telecomManager get() =
        appContext!!.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val audioManager get() =
        appContext!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Persistent coroutine scope — not tied to any Activity lifecycle.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var callJob: Job? = null

    // Compose-observable state — readable from any recomposition of App().
    val callActive = mutableStateOf(false)
    val callStatus = mutableStateOf("Calling…")
    val speakerOn  = mutableStateOf(false)

    // Which tile is being called — persists across activity recreations.
    var activeTile: Tile? = null
        private set

    private var callEnded    = false
    private var callStarted  = false
    private var callRegistered = false

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun startCall(tile: Tile) {
        callJob?.cancel()
        activeTile     = tile
        callEnded      = false
        callStarted    = false
        callRegistered = false
        callStatus.value = "Calling…"
        callActive.value = true

        callJob = scope.launch {
            launch { placeCall(tile.phoneNumber) }
            launch { watchSpeakerState() }
            launch { collectCallState() }
        }
    }

    fun endCall() {
        if (callEnded) return
        callEnded = true
        val call = EasyCallerInCallService.activeCall
        if (call != null) {
            call.disconnect()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { telecomManager.endCall() }
        }
        resetAudio()
        callActive.value = false
        activeTile = null
        callJob?.cancel()
        callJob = null
    }

    private suspend fun placeCall(phone: String) {
        val uri = android.net.Uri.fromParts("tel", phone, null)
        runCatching { telecomManager.placeCall(uri, Bundle()) }

        delay(2_000)
        if (!callEnded) enableSpeaker()

        delay(43_000) // 2 + 43 = 45s total
        if (!callStarted && !callEnded) endCall()
    }

    private suspend fun collectCallState() {
        EasyCallerInCallService.callState.collect { state ->
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
                    if (!callEnded && callRegistered) {
                        callEnded = true
                        resetAudio()
                        callActive.value = false
                        activeTile = null
                        callJob?.cancel()
                    }
                }
            }
        }
    }

    private fun enableSpeaker() {
        if (audioManager.isWiredHeadsetOn) return
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

    private suspend fun watchSpeakerState() {
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
