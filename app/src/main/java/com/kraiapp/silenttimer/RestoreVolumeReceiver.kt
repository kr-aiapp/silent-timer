package com.kraiapp.silenttimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when the timer elapses (automatic restore) or when the user taps the
 * "Przywróć dźwięk" action on the ongoing notification (manual restore).
 */
class RestoreVolumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SilenceController.restore(context)
    }

    companion object {
        const val ACTION_MANUAL_RESTORE = "com.kraiapp.silenttimer.MANUAL_RESTORE"
    }
}
