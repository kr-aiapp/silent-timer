package com.kraiapp.silenttimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.app.NotificationManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kraiapp.silenttimer.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audio: AudioManager
    private lateinit var notifManager: NotificationManager

    // selectedIndex in steps of 15 min (0=now, 1=15min, 2=30min, ...)
    private var durationSteps = 1   // default 15 min
    private var restoreVolume = 80  // 0..100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        setupPickers()
        setupVolumeBar()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updateLabels()
    }

    private fun setupPickers() {
        // Left picker: end time (isEndTime=true)
        binding.rvUntil.apply {
            // We'll reuse TimePicker as a custom View embedded in a container
            // But since the layout uses RecyclerView, replace with TimePicker programmatically
        }

        // Replace RecyclerViews with TimePicker custom views
        val parent = binding.rvUntil.parent as android.view.ViewGroup
        val leftIndex = parent.indexOfChild(binding.rvUntil)
        val rightIndex = parent.indexOfChild(binding.rvDuration)

        val pickerUntil = TimePicker(this).apply {
            isEndTime = true
            layoutParams = binding.rvUntil.layoutParams
        }
        val pickerDuration = TimePicker(this).apply {
            isEndTime = false
            layoutParams = binding.rvDuration.layoutParams
        }

        parent.removeView(binding.rvUntil)
        parent.removeView(binding.rvDuration)
        parent.addView(pickerUntil, leftIndex)
        // divider is now at leftIndex+1, duration goes after
        parent.addView(pickerDuration, leftIndex + 2)

        // Sync: when duration changes, update "until" display and vice versa
        pickerDuration.selectedIndex = 1  // 15 min default
        syncUntilFromDuration(pickerDuration.selectedIndex, pickerUntil)

        pickerDuration.onSelectionChanged = { steps ->
            durationSteps = steps
            syncUntilFromDuration(steps, pickerUntil)
            updateLabels()
        }

        pickerUntil.onSelectionChanged = { untilSteps ->
            // Calculate duration from current time
            val now = Calendar.getInstance()
            val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val untilMinutes = untilSteps * 15
            var diff = untilMinutes - nowMinutes
            if (diff < 0) diff += 24 * 60
            val dSteps = (diff / 15).coerceAtLeast(0)
            durationSteps = dSteps
            pickerDuration.setSelectedIndex(dSteps, smooth = true)
            updateLabels()
        }
    }

    private fun syncUntilFromDuration(durationSteps: Int, pickerUntil: TimePicker) {
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val untilMinutes = (nowMinutes + durationSteps * 15) % (24 * 60)
        val untilSteps = untilMinutes / 15
        pickerUntil.setSelectedIndex(untilSteps, smooth = true)
    }

    private fun setupVolumeBar() {
        binding.volumeSeekBar.progress = restoreVolume
        binding.volumeSeekBar.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                restoreVolume = p
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }

    private fun setupButtons() {
        binding.btnMuteNow.setOnClickListener {
            if (!hasDoNotDisturbPermission()) {
                requestDoNotDisturbPermission()
                return@setOnClickListener
            }
            mutePhone()
            finish()
        }

        binding.btnSilentForAWhile.setOnClickListener {
            if (!hasDoNotDisturbPermission()) {
                requestDoNotDisturbPermission()
                return@setOnClickListener
            }
            if (durationSteps == 0) {
                Toast.makeText(this, "Wybierz czas wyciszenia", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mutePhone()
            scheduleRestore(durationSteps * 15L)
            finish()
        }
    }

    private fun updateLabels() {
        // "Silence for HH:MM"
        val totalMin = durationSteps * 15
        val h = totalMin / 60
        val m = totalMin % 60
        binding.tvForDuration.text = String.format("%02d:%02d", h, m)

        // "Silence until HH:MM"
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val untilMinutes = (nowMinutes + totalMin) % (24 * 60)
        val uh = untilMinutes / 60
        val um = untilMinutes % 60
        binding.tvUntilTime.text = String.format("%02d:%02d", uh, um)

        if (durationSteps == 0) {
            binding.tvNowTag.visibility = android.view.View.VISIBLE
        } else {
            binding.tvNowTag.visibility = android.view.View.GONE
        }
    }

    private fun mutePhone() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

    private fun scheduleRestore(delayMinutes: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, RestoreVolumeReceiver::class.java).apply {
            putExtra(RestoreVolumeReceiver.EXTRA_VOLUME, restoreVolume)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + delayMinutes * 60 * 1000
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } catch (e: SecurityException) {
            // Fallback to inexact alarm if exact not allowed
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun hasDoNotDisturbPermission(): Boolean =
        notifManager.isNotificationPolicyAccessGranted

    private fun requestDoNotDisturbPermission() {
        AlertDialog.Builder(this)
            .setTitle("Wymagane uprawnienie")
            .setMessage("Aplikacja potrzebuje dostępu do trybu Nie przeszkadzać, aby wyciszać telefon.\n\nKliknij OK, a następnie znajdź 'Silent Timer' na liście i włącz dostęp.")
            .setPositiveButton("Otwórz ustawienia") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }
}
