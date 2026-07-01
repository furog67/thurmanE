package com.elderlycaller

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallManager(
    private val telecomManager: TelecomManager,
    private val audioManager: AudioManager,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    val callActive = mutableStateOf(false)
    val callStatus = mutableStateOf("Calling…")
    val speakerOn = mutableStateOf(false)

    private var callEnded = false
    private var callStarted = false
    private var callRegistered = false

    fun startCall(phone: String) {
        callEnded = false
        callStarted = false
        callRegistered = false
        callStatus.value = "Calling…"
        callActive.value = true
        placeCall(phone)
        watchSpeakerState()
        collectCallState()
    }

    private fun placeCall(phone: String) {
        val uri = android.net.Uri.fromParts("tel", phone, null)
        runCatching { telecomManager.placeCall(uri, Bundle()) }

        lifecycleScope.launch {
            delay(2_000)
            if (!callEnded) enableSpeaker()
        }
        lifecycleScope.launch {
            delay(45_000)
            if (!callStarted && !callEnded) endCall()
        }
    }

    private fun collectCallState() {
        lifecycleScope.launch {
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
                        }
                    }
                }
            }
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
}
