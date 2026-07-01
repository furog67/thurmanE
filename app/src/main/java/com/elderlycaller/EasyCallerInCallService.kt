package com.elderlycaller

import android.telecom.Call
import android.telecom.InCallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When Easy Caller is the default phone app, Android routes all call UI through
 * this service instead of showing the system dialer's in-call screen.
 * The call screen is now shown as a Compose state within MainActivity — no
 * separate activity launch is needed, so lock task mode never interferes.
 */
class EasyCallerInCallService : InCallService() {

    companion object {
        @Volatile var activeCall: Call? = null
            private set

        @Volatile var activeService: EasyCallerInCallService? = null
            private set

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
        // Reset to STATE_NEW before processing so that collectors never see a
        // stale STATE_DISCONNECTED left over from a prior call.
        _callState.value = Call.STATE_NEW
        activeCall = call
        call.registerCallback(callStateCallback)
        _callState.value = call.details.state
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callStateCallback)
        if (activeCall == call) {
            activeCall = null
            _callState.value = Call.STATE_NEW
        }
    }
}
