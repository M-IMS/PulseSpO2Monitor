package com.example.pulsespo2monitor

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    val spo2: Int
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
            updateStatus("No signal. Ensure the finger covers the sensor completely.", isError = true)
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
        val hrStatus = if (heartRateSensor != null)
            "✓  Heart Rate — ${heartRateSensor!!.name}"
        else
            "✗  Heart Rate sensor not detected"

        val spo2Status = if (spo2Sensor != null)
            "✓  HRM/SpO2 — ${spo2Sensor!!.name}"
        else
            "✗  SpO2 hardware not detected"

        binding.tvSensorInfo.text = "$hrStatus\n\n$spo2Status"
        
        if (rawRedSensor != null && rawIrSensor != null) {
            binding.tvSensorInfo.append("\n\n✓  Raw Red/IR available")
        }
    }

    // ─── UI Setup ───────────────────────────────────────────────────────────

    private fun setupUI() {
        resetDisplays()
        updateStatus("Press START to begin measurement")

        binding.btnToggle.setOnClickListener {
            if (!isRunning) requestPermissionAndStart() else stopMeasurement()
        }

        binding.btnSave.setOnClickListener {
            saveCurrentData()
        }

        // Tab Switching
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) {
                    binding.layoutMonitor.visibility = View.VISIBLE
                    binding.layoutLogs.visibility = View.GONE
                } else {
                    binding.layoutMonitor.visibility = View.GONE
                    binding.layoutLogs.visibility = View.VISIBLE
                    refreshLogsUI()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Show/hide sensor info panel
        binding.tvSensorInfoToggle.setOnClickListener {
            if (binding.tvSensorInfo.visibility == View.GONE) {
                binding.tvSensorInfo.visibility = View.VISIBLE
                binding.tvSensorInfoToggle.text = "Hide sensor info ▲"
            } else {
                binding.tvSensorInfo.visibility = View.GONE
                binding.tvSensorInfoToggle.text = "Show sensor info ▼"
            }
        }
    }

    private fun saveCurrentData() {
        val bpmText = binding.tvBpm.text.toString()
        val spo2Text = binding.tvSpo2.text.toString().replace("%", "")

        if (bpmText == "--" || spo2Text == "--") {
            Toast.makeText(this, "Wait for stable reading before saving", Toast.LENGTH_SHORT).show()
            return
        }

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            bpm = bpmText.toInt(),
            spo2 = spo2Text.toInt()
        )

        val logs = getSavedLogs().toMutableList()
        logs.add(0, entry) // Add to top
        saveLogs(logs)

        Toast.makeText(this, "Measurement saved to logs", Toast.LENGTH_SHORT).show()
    }

    private fun getSavedLogs(): List<LogEntry> {
        val prefs = getSharedPreferences("measurements", Context.MODE_PRIVATE)
        val json = prefs.getString("logs", null) ?: return emptyList()
        val type = object : TypeToken<List<LogEntry>>() {}.type
        return Gson().fromJson(json, type)
    }

    private fun saveLogs(logs: List<LogEntry>) {
        val prefs = getSharedPreferences("measurements", Context.MODE_PRIVATE)
        val json = Gson().toJson(logs)
        prefs.edit().putString("logs", json).apply()
    }

    private fun deleteLog(entry: LogEntry) {
        val logs = getSavedLogs().toMutableList()
        logs.removeIf { it.timestamp == entry.timestamp }
        saveLogs(logs)
        refreshLogsUI()
        Toast.makeText(this, "Record deleted", Toast.LENGTH_SHORT).show()
    }

    private fun refreshLogsUI() {
        binding.logsContainer.removeAllViews()
        
        // Re-add title
        val title = TextView(this).apply {
            text = "Measurement History"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_title))
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 48) }
        }
        binding.logsContainer.addView(title)

        val logs = getSavedLogs()
        if (logs.isEmpty()) {
            binding.logsContainer.addView(binding.tvNoLogs)
            return
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())

        for (log in logs) {
            val logView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, null)
            val text1 = logView.findViewById<TextView>(android.R.id.text1)
            val text2 = logView.findViewById<TextView>(android.R.id.text2)

            text1.text = "${log.bpm} BPM  |  ${log.spo2}% SpO2"
            text1.setTextColor(ContextCompat.getColor(this, R.color.color_title))
            text1.textSize = 16f

            text2.text = dateFormat.format(Date(log.timestamp))
            text2.setTextColor(ContextCompat.getColor(this, R.color.color_subtitle))
            
            // Add margin/padding to the log item
            logView.setPadding(0, 24, 0, 24)

            // Setup Long Click or adding a button for deletion
            // For simplicity and matching user request "option to delete", let's use a long click with a toast/dialog
            logView.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.delete_confirm))
                    .setPositiveButton("Delete") { _, _ ->
                        deleteLog(log)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            
            binding.logsContainer.addView(logView)
            
            // Add divider
            val divider = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    2
                )
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.color_card_bg))
            }
            binding.logsContainer.addView(divider)
        }
    }

    private fun resetDisplays() {
        binding.tvBpm.text = "--"
        binding.tvSpo2.text = "--"
        binding.tvBpmAvg.text = ""
        binding.tvSpo2Avg.text = ""
        binding.tvAccuracy.text = ""
        bpmHistory.clear()
        spo2History.clear()
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
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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
        if (heartRateSensor == null && spo2Sensor == null) {
            updateStatus("No health sensors found on this device.", isError = true)
            return
        }

        isRunning = true
        resetDisplays()
        binding.btnToggle.text = "■  Stop"
        binding.btnToggle.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.color_stop_btn)
        binding.measurementCard.visibility = View.VISIBLE

        updateStatus("Place your finger firmly on the rear sensor…")

        // Try to register SpO2/HRM sensor FIRST
        // On Note 9, multiple registrations on the biosensor might fail
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
        handler.postDelayed(timeoutRunnable, 30_000)
    }

    private fun stopMeasurement() {
        isRunning = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(timeoutRunnable)
        stopPulseAnimation()

        binding.btnToggle.text = "▶  Start"
        binding.btnToggle.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.color_start_btn)

        updateStatus("Measurement stopped")
    }

    // ─── SensorEventListener ────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        handler.removeCallbacks(timeoutRunnable)

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
                calculateSpO2FromRaw()
            }
            SAM_IR_SENSOR_TYPE -> {
                lastIrValue = event.values[0]
                calculateSpO2FromRaw()
            }
        }
    }

    private val redBuffer = ArrayDeque<Float>(200)
    private val irBuffer = ArrayDeque<Float>(200)

    private fun calculateSpO2FromRaw() {
        if (lastRedValue <= 0 || lastIrValue <= 0) return

        // Maintain sliding windows
        if (redBuffer.size >= 200) redBuffer.removeFirst()
        redBuffer.addLast(lastRedValue)
        
        if (irBuffer.size >= 200) irBuffer.removeFirst()
        irBuffer.addLast(lastIrValue)

        if (redBuffer.size < 200) return

        // Calculate DC (average)
        val redDc = redBuffer.average().toFloat()
        val irDc = irBuffer.average().toFloat()

        // Calculate AC (peak-to-peak as a proxy for pulsatile component)
        val redMin = redBuffer.minOrNull() ?: 0f
        val redMax = redBuffer.maxOrNull() ?: 0f
        val irMin = irBuffer.minOrNull() ?: 0f
        val irMax = irBuffer.maxOrNull() ?: 0f
        
        val redAc = redMax - redMin
        val irAc = irMax - irMin

        if (redDc > 0 && irDc > 0 && irAc > 0) {
            val r = (redAc / redDc) / (irAc / irDc)
            
            // Standard ratio-of-ratios formula
            var spo2 = 110 - (25 * r)
            
            if (spo2 > 100f) spo2 = 100f
            if (spo2 < 70f) spo2 = 70f

            // Update if signal looks valid (AC > 0.05% of DC)
            if (redAc > redDc * 0.0005f && irAc > irDc * 0.0005f) {
                handleSpo2Reading(spo2)
                
                // --- HEART RATE CALCULATION (Independent from system) ---
                // Simple peak detection from the IR signal (higher quality than Red)
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
        if (sensor.type == Sensor.TYPE_HEART_RATE) {
            val (text, color) = when (accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH ->
                    "Accuracy: High ●" to R.color.color_accuracy_high
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
                    "Accuracy: Medium ●" to R.color.color_accuracy_med
                SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
                    "Accuracy: Low — press finger firmly ●" to R.color.color_accuracy_low
                else ->
                    "No contact — cover the rear sensor ●" to R.color.color_error
            }
            binding.tvAccuracy.text = text
            binding.tvAccuracy.setTextColor(ContextCompat.getColor(this, color))

            if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH ||
                accuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            ) {
                updateStatus("Measuring — keep your finger still")
                startPulseAnimation()
            } else {
                updateStatus("Adjust finger placement on the sensor")
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
            binding.tvBpmAvg.text = "Avg: $avg BPM"
        }

        // Color-code BPM (normal 60-100)
        val color = when {
            bpm < 50 || bpm > 120 -> R.color.color_warning
            bpm < 60 || bpm > 100 -> R.color.color_bpm_caution
            else -> R.color.color_bpm
        }
        binding.tvBpm.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun handleSpo2Reading(spo2: Float) {
        if (spo2History.size >= 20) spo2History.removeFirst()
        spo2History.addLast(spo2)

        val avg = spo2History.average().toFloat()
        binding.tvSpo2.text = "${avg.toInt()}%"
        binding.tvSpo2Avg.text = "Raw: ${spo2.toInt()}%"

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
