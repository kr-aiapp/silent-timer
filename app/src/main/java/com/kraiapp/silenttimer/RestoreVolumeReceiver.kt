package com.kraiapp.silenttimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager

class RestoreVolumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val volume = intent.getIntExtra(EXTRA_VOLUME, 80)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
        val targetVol = (volume / 100f * maxVol).toInt().coerceAtLeast(1)
        audio.setStreamVolume(AudioManager.STREAM_RING, targetVol, 0)
    }

    companion object {
        const val EXTRA_VOLUME = "restore_volume"
        const val ACTION = "com.kraiapp.silenttimer.RESTORE_VOLUME"
    }
}
