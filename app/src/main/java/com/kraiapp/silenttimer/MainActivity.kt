package com.kraiapp.silenttimer

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kraiapp.silenttimer.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var notifManager: NotificationManager

    private lateinit var pickerUntil: TimePicker
    private lateinit var pickerDuration: TimePicker

    private val step = TimePicker.STEP_MINUTES   // 5-minute granularity
    private var durationSteps = 3            // default 15 min (3 x 5 min)
    private var nowSteps = 0                 // current time rounded to 5-min steps
    private var syncing = false             // guard against mirror feedback loop
    private var exactAlarmAsked = false      // only prompt once per session

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* re-checked on resume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        computeNow()
        setupPickers()
        setupVolumeBar()
        setupVibrateSwitch()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        checkAllPermissions()
        refreshUiForSilenceState()
        updateLabels()
    }

    // ---------- Permissions ----------

    private fun checkAllPermissions() {
        // 1) Do Not Disturb access (required to change ringer mode)
        if (!notifManager.isNotificationPolicyAccessGranted) {
            askDndPermission()
            return
        }
        // 2) POST_NOTIFICATIONS (Android 13+) for the ongoing status notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // 3) Exact alarm (Android 12+) for precise auto-restore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms() && !exactAlarmAsked) {
                exactAlarmAsked = true
                askExactAlarmPermission()
            }
        }
    }

    private fun hasAllCriticalPermissions(): Boolean {
        if (!notifManager.isNotificationPolicyAccessGranted) return false
        return true
    }

    private fun askDndPermission() {
        AlertDialog.Builder(this)
            .setTitle("Wymagane uprawnienie")
            .setMessage("Aplikacja potrzebuje dostępu do trybu \"Nie przeszkadzać\", aby wyciszać i przywracać dźwięk.\n\nKliknij OK, znajdź na liście \"Silent Timer\" i włącz dostęp.")
            .setPositiveButton("Otwórz ustawienia") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            .setNegativeButton("Anuluj", null)
            .setCancelable(false)
            .show()
    }

    private fun askExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        AlertDialog.Builder(this)
            .setTitle("Dokładny timer")
            .setMessage("Aby wyciszenie kończyło się dokładnie o wybranej godzinie, zezwól na \"Alarmy i przypomnienia\".\n\nBez tego dźwięk może wrócić z lekkim opóźnieniem.")
            .setPositiveButton("Otwórz ustawienia") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Pomiń", null)
            .show()
    }

    // ---------- Pickers (with live mirroring) ----------

    private fun computeNow() {
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        nowSteps = Math.round(nowMin.toFloat() / step)
    }

    private fun setupPickers() {
        val parent = binding.rvUntil.parent as ViewGroup
        val leftIndex = parent.indexOfChild(binding.rvUntil)

        pickerUntil = TimePicker(this).apply {
            isEndTime = true
            layoutParams = binding.rvUntil.layoutParams
        }
        pickerDuration = TimePicker(this).apply {
            isEndTime = false
            layoutParams = binding.rvDuration.layoutParams
        }

        parent.removeView(binding.rvUntil)
        parent.removeView(binding.rvDuration)
        parent.addView(pickerUntil, leftIndex)
        parent.addView(pickerDuration, leftIndex + 2) // +2 to sit after the divider

        // Live mirroring: until = nowSteps + duration  (constant offset in 5-min steps)
        pickerDuration.onPositionChanged = { pos ->
            if (!syncing) {
                syncing = true
                pickerUntil.setPosition(nowSteps + pos)
                durationSteps = pos.coerceAtLeast(0f).toInt()
                updateLabelsLive(pos)
                syncing = false
            }
        }
        pickerUntil.onPositionChanged = { pos ->
            if (!syncing) {
                syncing = true
                val durPos = (pos - nowSteps).coerceAtLeast(0f)
                pickerDuration.setPosition(durPos)
                durationSteps = durPos.toInt()
                updateLabelsLive(durPos)
                syncing = false
            }
        }
        pickerDuration.onSelectionChanged = { steps ->
            durationSteps = steps
            updateLabels()
        }
        pickerUntil.onSelectionChanged = {
            durationSteps = (pickerDuration.selectedIndex).coerceAtLeast(0)
            updateLabels()
        }

        pickerDuration.post {
            pickerDuration.setSelectedIndex(durationSteps)
            pickerUntil.setSelectedIndex(nowSteps + durationSteps)
            updateLabels()
        }
    }

    // ---------- Volume bar & vibrate switch ----------

    private fun setupVolumeBar() {
        binding.volumeSeekBar.progress = SilenceController.restoreVolumeOverride(this)
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                SilenceController.setRestoreVolumeOverride(this@MainActivity, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupVibrateSwitch() {
        binding.switchVibrate.isChecked = SilenceController.isVibrateEnabled(this)
        binding.switchVibrate.setOnCheckedChangeListener { _, checked ->
            SilenceController.setVibrateEnabled(this, checked)
        }
    }

    // ---------- Buttons ----------

    private fun setupButtons() {
        binding.btnSilentForAWhile.setOnClickListener {
            if (!hasAllCriticalPermissions()) { askDndPermission(); return@setOnClickListener }
            if (durationSteps <= 0) {
                Toast.makeText(this, "Wybierz czas wyciszenia", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SilenceController.mute(this, durationSteps * step, binding.switchVibrate.isChecked)
            finish()
        }

        binding.btnMuteNow.setOnClickListener {
            if (SilenceController.isSilenced(this)) {
                // Acting as "Restore volume"
                SilenceController.restore(this)
                refreshUiForSilenceState()
                Toast.makeText(this, "Dźwięk przywrócony", Toast.LENGTH_SHORT).show()
            } else {
                if (!hasAllCriticalPermissions()) { askDndPermission(); return@setOnClickListener }
                // Mute indefinitely (no timer)
                SilenceController.mute(this, 0, binding.switchVibrate.isChecked)
                finish()
            }
        }
    }

    /** Swap the right button between "Mute now" and "Restore volume". */
    private fun refreshUiForSilenceState() {
        if (SilenceController.isSilenced(this)) {
            binding.btnMuteNow.text = getString(R.string.restore_volume_btn)
            binding.btnMuteNow.setCompoundDrawablesWithIntrinsicBounds(
                android.R.drawable.ic_lock_silent_mode_off, 0, 0, 0)
        } else {
            binding.btnMuteNow.text = getString(R.string.btn_mute_now)
            binding.btnMuteNow.setCompoundDrawablesWithIntrinsicBounds(
                android.R.drawable.ic_lock_silent_mode, 0, 0, 0)
        }
    }

    // ---------- Labels ----------

    private fun updateLabelsLive(durPos: Float) {
        val totalMin = (durPos * step).toInt()
        renderLabels(totalMin)
    }

    private fun updateLabels() {
        renderLabels(durationSteps * step)
    }

    private fun renderLabels(totalMin: Int) {
        val h = totalMin / 60
        val m = totalMin % 60
        binding.tvForDuration.text = String.format("%02d:%02d", h, m)

        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val untilMin = (nowMin + totalMin) % (24 * 60)
        binding.tvUntilTime.text = String.format("%02d:%02d", untilMin / 60, untilMin % 60)

        binding.tvNowTag.visibility = if (totalMin == 0) View.VISIBLE else View.GONE
    }
}
