package com.kraiapp.silenttimer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * Single source of truth for muting/restoring. Saves the full audio state before
 * muting so it can be restored exactly, manages the ongoing notification and the
 * exact-alarm timer.
 */
object SilenceController {

    private const val PREFS = "silent_timer_prefs"
    const val KEY_IS_SILENCED = "is_silenced"
    private const val KEY_RINGER_MODE = "saved_ringer_mode"
    private const val KEY_RING_VOL = "saved_ring_volume"
    private const val KEY_NOTIF_VOL = "saved_notif_volume"
    private const val KEY_RESTORE_AT = "restore_at_millis"
    const val KEY_VIBRATE = "vibrate_enabled"
    const val KEY_RESTORE_OVERRIDE = "restore_volume_override"

    private const val CHANNEL_ID = "silence_status"
    private const val NOTIF_ID = 1001
    const val ALARM_REQUEST = 42

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSilenced(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_IS_SILENCED, false)

    fun isVibrateEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VIBRATE, true)

    fun setVibrateEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VIBRATE, enabled).apply()
    }

    fun restoreVolumeOverride(ctx: Context): Int =
        prefs(ctx).getInt(KEY_RESTORE_OVERRIDE, 80)

    fun setRestoreVolumeOverride(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_RESTORE_OVERRIDE, value).apply()
    }

    fun restoreAtMillis(ctx: Context): Long = prefs(ctx).getLong(KEY_RESTORE_AT, 0L)

    /**
     * Mute the phone. Saves the current audio state first.
     * @param durationMinutes if > 0, schedules automatic restore; if 0, mute indefinitely (no timer).
     */
    fun mute(ctx: Context, durationMinutes: Int, vibrate: Boolean) {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Save current state so we can restore it exactly
        val p = prefs(ctx).edit()
        p.putInt(KEY_RINGER_MODE, audio.ringerMode)
        p.putInt(KEY_RING_VOL, audio.getStreamVolume(AudioManager.STREAM_RING))
        p.putInt(KEY_NOTIF_VOL, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        p.putBoolean(KEY_IS_SILENCED, true)
        p.putBoolean(KEY_VIBRATE, vibrate)

        // Apply mute
        audio.ringerMode =
            if (vibrate) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_SILENT

        val restoreAt = if (durationMinutes > 0) {
            System.currentTimeMillis() + durationMinutes * 60_000L
        } else 0L
        p.putLong(KEY_RESTORE_AT, restoreAt)
        p.apply()

        if (durationMinutes > 0) {
            scheduleRestore(ctx, restoreAt)
        }
        showNotification(ctx, restoreAt)
    }

    /** Restore the exact audio state that was saved before muting. */
    fun restore(ctx: Context) {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val pr = prefs(ctx)

        val savedRinger = pr.getInt(KEY_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
        audio.ringerMode = savedRinger

        if (savedRinger == AudioManager.RINGER_MODE_NORMAL) {
            val override = pr.getInt(KEY_RESTORE_OVERRIDE, -1)
            if (override in 0..100) {
                // User specified a target ring level via the slider
                val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
                val target = (override / 100f * maxVol).toInt().coerceAtLeast(1)
                audio.setStreamVolume(AudioManager.STREAM_RING, target, 0)
            } else {
                // Otherwise restore the exact saved level
                val savedRing = pr.getInt(KEY_RING_VOL, -1)
                if (savedRing >= 0) audio.setStreamVolume(AudioManager.STREAM_RING, savedRing, 0)
            }
            val savedNotif = pr.getInt(KEY_NOTIF_VOL, -1)
            if (savedNotif >= 0) {
                runCatching { audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotif, 0) }
            }
        }

        pr.edit()
            .putBoolean(KEY_IS_SILENCED, false)
            .putLong(KEY_RESTORE_AT, 0L)
            .apply()

        cancelAlarm(ctx)
        cancelNotification(ctx)
    }

    private fun scheduleRestore(ctx: Context, triggerAtMillis: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, RestoreVolumeReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, ALARM_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun cancelAlarm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, RestoreVolumeReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, ALARM_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    private fun showNotification(ctx: Context, restoreAt: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Stan wyciszenia", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Pokazuje kiedy telefon przestanie być wyciszony" }
            nm.createNotificationChannel(channel)
        }

        val hasTimer = restoreAt > 0
        val text = if (hasTimer) {
            val cal = Calendar.getInstance().apply { timeInMillis = restoreAt }
            val until = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            "Dźwięk wróci o $until"
        } else {
            "Wyciszone — bez limitu czasu"
        }

        val openIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = PendingIntent.getBroadcast(
            ctx, 99,
            Intent(ctx, RestoreVolumeReceiver::class.java).apply {
                action = RestoreVolumeReceiver.ACTION_MANUAL_RESTORE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle(if (hasTimer) "Telefon wyciszony" else "Silent Timer")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_lock_silent_mode_off, "Przywróć dźwięk", restoreIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (hasTimer) {
            // Live count-down: "za X min" updates automatically in the status bar
            builder.setWhen(restoreAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
        }

        nm.notify(NOTIF_ID, builder.build())
    }

    fun cancelNotification(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }
}
