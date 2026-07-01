package com.elderlycaller

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When Easy Caller is the default phone app, Android routes all call UI through
 * this service instead of showing the system dialer's in-call screen.
 * CallingActivity stays in the foreground — no overlay battle needed.
 */
class EasyCallerInCallService : InCallService() {

    companion object {
        @Volatile var activeCall: Call? = null
            private set

        @Volatile var activeService: EasyCallerInCallService? = null
            private set

        // CallingActivity collects this instead of using TelephonyManager, which
        // fires a spurious IDLE callback immediately on registration and can cause
        // premature finish() before the call has even started.
        private val _callState = MutableStateFlow(Call.STATE_NEW)
        val callState: StateFlow<Int> = _callState.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeService == this) activeService = null
    }

    private val callStateCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            _callState.value = state
        }
    }

    override fun onCallAdded(call: Call) {
        activeCall = call
        call.registerCallback(callStateCallback)
        _callState.value = call.details.state

        // Outgoing calls: CallingActivity is already on screen.
        // Incoming calls: launch CallingActivity so the user can answer or decline.
        if (call.details.state == Call.STATE_RINGING) {
            val number = call.details.handle?.schemeSpecificPart ?: ""
            startActivity(
                Intent(this, CallingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(CallingActivity.EXTRA_PHONE, number)
                    putExtra(CallingActivity.EXTRA_NAME, "Incoming Call")
                    putExtra(CallingActivity.EXTRA_IMAGE, "")
                }
            )
        }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callStateCallback)
        if (activeCall == call) {
            activeCall = null
            _callState.value = Call.STATE_DISCONNECTED
        }
    }
}
