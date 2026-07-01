package com.elderlycaller

import android.app.Activity
import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import java.lang.ref.WeakReference
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

        // Weak reference to MainActivity — used to launch CallingActivity for incoming
        // calls within the existing (potentially kiosk-pinned) task rather than creating
        // a new task that lock task mode would block.
        var mainActivity: WeakReference<Activity>? = null

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
        // Reset to STATE_NEW before processing so CallingActivity's StateFlow collector
        // never sees a stale STATE_DISCONNECTED left over from a prior call.
        _callState.value = Call.STATE_NEW
        activeCall = call
        call.registerCallback(callStateCallback)
        _callState.value = call.details.state

        // Outgoing calls: CallingActivity is already on screen.
        // Incoming calls: launch CallingActivity via the stored activity reference so it
        // starts within the existing (kiosk-pinned) task rather than a new task that lock
        // task mode would block. Fall back to FLAG_ACTIVITY_NEW_TASK if no activity ref.
        if (call.details.state == Call.STATE_RINGING) {
            val number = call.details.handle?.schemeSpecificPart ?: ""
            val intent = Intent(this, CallingActivity::class.java).apply {
                putExtra(CallingActivity.EXTRA_PHONE, number)
                putExtra(CallingActivity.EXTRA_NAME, "Incoming Call")
                putExtra(CallingActivity.EXTRA_IMAGE, "")
            }
            val activity = mainActivity?.get()
            if (activity != null && !activity.isFinishing) {
                activity.startActivity(intent)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
            }
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
