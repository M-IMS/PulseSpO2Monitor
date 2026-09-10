package com.example.pulsespo2monitor

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.example.pulsespo2monitor.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long,
    val bpm: Int,
    val spo2: Int,
)

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager

    private var heartRateSensor: Sensor? = null
    private var spo2Sensor: Sensor? = null
    private var rawRedSensor: Sensor? = null
    private var rawIrSensor: Sensor? = null

    private var lastRedValue: Float = 0f
    private var lastIrValue: Float = 0f

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    // BPM history for averaging (last 5 readings)
    private val bpmHistory = ArrayDeque<Float>(5)
    private val spo2History = ArrayDeque<Float>(5)

    // Pulse animation
    private var pulseAnimator: ValueAnimator? = null

    // Timeout runnable – if no reading after 30s, show guidance
    private val timeoutRunnable = Runnable {
        if (isRunning) {
            updateStatus(getString(R.string.status_no_signal), isError = true)
        }
    }

    companion object {
        private const val PERMISSION_CODE = 101

        // Samsung Sensor Types
        private const val SAM_HRM_SENSOR_TYPE = 65562
        private const val SAM_RED_SENSOR_TYPE = 65572
        private const val SAM_IR_SENSOR_TYPE = 65571
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        detectSensors()
        setupUI()
    }

    // ─── Sensor Detection ───────────────────────────────────────────────────

    private fun detectSensors() {
        // Standard Android heart rate sensor (works on Note 9)
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        // Find candidate sensors for SpO2
        spo2Sensor = sensorManager.getDefaultSensor(SAM_HRM_SENSOR_TYPE)
        rawRedSensor = sensorManager.getDefaultSensor(SAM_RED_SENSOR_TYPE)
        rawIrSensor = sensorManager.getDefaultSensor(SAM_IR_SENSOR_TYPE)

        // Update the sensor info panel
        val hrStatus = if (heartRateSensor != null) {
            getString(R.string.sensor_check_hr_ok, heartRateSensor!!.name)
        } else {
            getString(R.string.sensor_check_hr_fail)
        }

        val spo2Status = if (spo2Sensor != null) {
            getString(R.string.sensor_check_spo2_ok, spo2Sensor!!.name)
        } else {
            getString(R.string.sensor_check_spo2_fail)
        }

        binding.tvSensorInfo.text = getString(R.string.sensor_info_format, hrStatus, spo2Status)

        if ((rawRedSensor != null) && (rawIrSensor != null)) {
            binding.tvSensorInfo.append("\n\n" + getString(R.string.sensor_check_raw_ok))
        }
    }

    // ─── UI Setup ───────────────────────────────────────────────────────────

    private fun setupUI() {
        resetDisplays()
        updateStatus(getString(R.string.status_start_prompt))

        binding.btnToggle.setOnClickListener {
            if (!isRunning) requestPermissionAndStart() else stopMeasurement()
        }

        binding.btnSave.setOnClickListener {
            saveCurrentData()
        }

        // Tab Switching
        binding.tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) {
                    binding.layoutMonitor.isVisible = true
                    binding.layoutLogs.isVisible = false
                } else {
                    binding.layoutMonitor.isVisible = false
                    binding.layoutLogs.isVisible = true
                    refreshLogsUI()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        },
        )

        // Show/hide sensor info panel
        binding.tvSensorInfoToggle.setOnClickListener {
            if (binding.tvSensorInfo.isGone) {
                binding.tvSensorInfo.isVisible = true
                binding.tvSensorInfoToggle.text = getString(R.string.sensor_info_hide)
            } else {
                binding.tvSensorInfo.isGone = true
                binding.tvSensorInfoToggle.text = getString(R.string.sensor_info_show)
            }
        }
    }

    private fun saveCurrentData() {
        val bpmText = binding.tvBpm.text.toString()
        val spo2Text = binding.tvSpo2.text.toString().replace("%", "")

        if ((bpmText == getString(R.string.default_empty_value)) || (spo2Text == getString(R.string.default_empty_value))) {
            Toast.makeText(this, R.string.toast_wait_stable, Toast.LENGTH_SHORT).show()
            return
        }

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            bpm = bpmText.toInt(),
            spo2 = spo2Text.toInt(),
        )

        val logs = getSavedLogs().toMutableList()
        logs.add(0, entry) // Add to top
        saveLogs(logs)

        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    private fun getSavedLogs(): List<LogEntry> {
        val prefs = getSharedPreferences("measurements", MODE_PRIVATE)
        val json = prefs.getString("logs", null) ?: return emptyList()
        val type = object : TypeToken<List<LogEntry>>() {}.type
        return Gson().fromJson(json, type)
    }

    private fun saveLogs(logs: List<LogEntry>) {
        val prefs = getSharedPreferences("measurements", MODE_PRIVATE)
        val json = Gson().toJson(logs)
        prefs.edit { putString("logs", json) }
    }

    private fun deleteLog(entry: LogEntry) {
        val logs = getSavedLogs().toMutableList()
        logs.removeIf { it.timestamp == entry.timestamp }
        saveLogs(logs)
        refreshLogsUI()
        Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun refreshLogsUI() {
        binding.logsItemsContainer.removeAllViews()

        val logs = getSavedLogs()
        binding.tvNoLogs.isVisible = logs.isEmpty()

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())

        for (log in logs) {
            val logView = LayoutInflater.from(this).inflate(
                android.R.layout.simple_list_item_2,
                binding.logsItemsContainer,
                false,
            )
            val text1 = logView.findViewById<TextView>(android.R.id.text1)
            val text2 = logView.findViewById<TextView>(android.R.id.text2)

            text1.text = getString(R.string.log_format_main, log.bpm, log.spo2)
            text1.setTextColor(ContextCompat.getColor(this, R.color.color_title))
            text1.textSize = 16f

            text2.text = dateFormat.format(Date(log.timestamp))
            text2.setTextColor(ContextCompat.getColor(this, R.color.color_subtitle))

            // Add margin/padding to the log item
            logView.setPadding(0, 24, 0, 24)

            logView.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.delete_confirm))
                    .setPositiveButton(R.string.dialog_delete) { _, _ ->
                        deleteLog(log)
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
                true
            }

            binding.logsItemsContainer.addView(logView)

            // Add divider
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    2,
                )
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.color_card_bg))
            }
            binding.logsItemsContainer.addView(divider)
        }
    }

    private fun resetDisplays() {
        binding.tvBpm.text = getString(R.string.default_empty_value)
        binding.tvSpo2.text = getString(R.string.default_empty_value)
        binding.tvBpmAvg.text = ""
        binding.tvSpo2Avg.text = ""
        binding.tvAccuracy.text = ""
        bpmHistory.clear()
        spo2History.clear()
        redBuffer.clear()
        irBuffer.clear()
    }

    private fun updateStatus(msg: String, isError: Boolean = false) {
        binding.tvStatus.text = msg
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (isError) R.color.color_error else R.color.color_status
            )
        )
    }

    // ─── Permissions ────────────────────────────────────────────────────────

    private fun requestPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startMeasurement()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BODY_SENSORS),
                PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && (grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                startMeasurement()
            } else {
                Toast.makeText(
                    this,
                    "Body Sensors permission is required to measure pulse & SpO2.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ─── Measurement Control ────────────────────────────────────────────────

    private fun startMeasurement() {
        if ((heartRateSensor == null) && (spo2Sensor == null)) {
            updateStatus(getString(R.string.status_no_sensors), isError = true)
            return
        }

        isRunning = true
        resetDisplays()
        binding.btnToggle.text = getString(R.string.btn_stop)
        binding.btnToggle.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.color_stop_btn)
        binding.measurementCard.isVisible = true

        updateStatus(getString(R.string.status_adjust))

        // Try to register SpO2/HRM sensor FIRST
        val registeredSpo2 = spo2Sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        } ?: false

        if (!registeredSpo2) {
            // If HRM failed, fallback to standard Heart Rate
            heartRateSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }

        rawRedSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        rawIrSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        // Start 30s timeout
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 30_000)
    }

    private fun stopMeasurement() {
        isRunning = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(timeoutRunnable)
        stopPulseAnimation()

        binding.btnToggle.text = getString(R.string.btn_start)
        binding.btnToggle.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.color_start_btn)

        updateStatus(getString(R.string.status_stopped))
    }

    // ─── SensorEventListener ────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        // Reschedule timeout whenever ANY health sensor event arrives
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 30_000)

        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values[0]
                if (bpm > 0f) {
                    handleBpmReading(bpm)
                }
            }
            SAM_HRM_SENSOR_TYPE -> {
                // Samsung HRM sensor often provides BPM in index 0
                val bpm = event.values[0]
                if (bpm in 30f..220f) {
                    handleBpmReading(bpm)
                }
            }
            SAM_RED_SENSOR_TYPE -> {
                lastRedValue = event.values[0]
                updateBufferAndCalculate(isRed = true)
            }
            SAM_IR_SENSOR_TYPE -> {
                lastIrValue = event.values[0]
                updateBufferAndCalculate(isRed = false)
            }
        }
    }

    private val redBuffer = ArrayDeque<Float>(200)
    private val irBuffer = ArrayDeque<Float>(200)

    private fun updateBufferAndCalculate(isRed: Boolean) {
        if (isRed) {
            if (redBuffer.size >= 200) redBuffer.removeFirst()
            redBuffer.addLast(lastRedValue)
        } else {
            if (irBuffer.size >= 200) irBuffer.removeFirst()
            irBuffer.addLast(lastIrValue)
        }

        if (redBuffer.size >= 200 && irBuffer.size >= 200) {
            calculateSpO2FromRaw()
        }
    }

    private fun calculateSpO2FromRaw() {
        // Calculate DC (average)
        val redDc = redBuffer.average().toFloat()
        val irDc = irBuffer.average().toFloat()

        // Calculate AC (peak-to-peak)
        val redAc = (redBuffer.maxOrNull() ?: 0f) - (redBuffer.minOrNull() ?: 0f)
        val irAc = (irBuffer.maxOrNull() ?: 0f) - (irBuffer.minOrNull() ?: 0f)

        if (redDc > 0 && irDc > 0 && irAc > 0) {
            val r = (redAc / redDc) / (irAc / irDc)

            // Standard ratio-of-ratios formula
            var spo2 = 110 - (25 * r)

            if (spo2 > 100f) spo2 = 100f
            if (spo2 < 70f) spo2 = 70f

            // Update if signal looks valid (AC > 0.05% of DC)
            if (redAc > redDc * 0.0005f && irAc > irDc * 0.0005f) {
                handleSpo2Reading(spo2)
                detectHeartRateFromBuffer()
            }
        }
    }

    private var lastPeakTime: Long = 0
    private fun detectHeartRateFromBuffer() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPeakTime < 400) return // Max 150 BPM debounce

        val irMax = irBuffer.maxOrNull() ?: 0f
        val irMin = irBuffer.minOrNull() ?: 0f
        val threshold = irMin + (irMax - irMin) * 0.9f // Look for peak at 90%

        if (lastIrValue > threshold) {
            if (lastPeakTime > 0) {
                val duration = currentTime - lastPeakTime
                val bpm = 60000f / duration
                if (bpm in 45f..180f) {
                    handleBpmReading(bpm)
                }
            }
            lastPeakTime = currentTime
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_HEART_RATE || sensor.type == SAM_HRM_SENSOR_TYPE) {
            val (text, color) = when (accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH ->
                    getString(R.string.accuracy_high) to R.color.color_accuracy_high
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
                    getString(R.string.accuracy_medium) to R.color.color_accuracy_med
                SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
                    getString(R.string.accuracy_low) to R.color.color_accuracy_low
                else ->
                    getString(R.string.accuracy_none) to R.color.color_error
            }
            binding.tvAccuracy.text = text
            binding.tvAccuracy.setTextColor(ContextCompat.getColor(this, color))

            if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH ||
                accuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            ) {
                updateStatus(getString(R.string.status_measuring))
                startPulseAnimation()
            } else {
                updateStatus(getString(R.string.status_adjust))
                stopPulseAnimation()
            }
        }
    }

    // ─── Reading Handlers ───────────────────────────────────────────────────

    private fun handleBpmReading(bpm: Float) {
        binding.tvBpm.text = bpm.toInt().toString()

        // Maintain rolling average of last 5 readings
        if (bpmHistory.size >= 5) bpmHistory.removeFirst()
        bpmHistory.addLast(bpm)

        if (bpmHistory.size >= 2) {
            val avg = bpmHistory.average().toInt()
            binding.tvBpmAvg.text = getString(R.string.bpm_avg_format, avg)
        }

        // Color-code BPM (normal 60-100)
        val color = when (bpm) {
            in 50f..120f -> {
                if (bpm in 60f..100f) R.color.color_bpm else R.color.color_bpm_caution
            }
            else -> R.color.color_warning
        }
        binding.tvBpm.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun handleSpo2Reading(spo2: Float) {
        if (spo2History.size >= 20) spo2History.removeFirst()
        spo2History.addLast(spo2)

        val avg = spo2History.average().toFloat()
        binding.tvSpo2.text = getString(R.string.spo2_percent_format, avg.toInt())
        binding.tvSpo2Avg.text = getString(R.string.spo2_raw_format, spo2.toInt())

        // Color-code SpO2 (normal ≥ 95%)
        val color = when {
            avg < 90 -> R.color.color_error
            avg < 95 -> R.color.color_warning
            else -> R.color.color_spo2
        }
        binding.tvSpo2.setTextColor(ContextCompat.getColor(this, color))
    }

    // ─── Pulse Animation ────────────────────────────────────────────────────

    private fun startPulseAnimation() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                binding.ivHeartIcon.scaleX = scale
                binding.ivHeartIcon.scaleY = scale
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.ivHeartIcon.scaleX = 1f
        binding.ivHeartIcon.scaleY = 1f
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        if (isRunning) sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (isRunning) {
            heartRateSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
            spo2Sensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
            rawRedSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
            rawIrSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
    }
}
