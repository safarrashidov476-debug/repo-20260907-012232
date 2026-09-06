package com.ekranyozuvchi.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main (and only) screen of the recorder: two buttons — start and stop — plus
 * a status line. Flow:
 *
 *   Start -> permissions (audio + notifications) -> screen-capture consent
 *         -> start the foreground recording service.
 *
 *   Stop  -> ask the service to finish; the UI goes back to "Tayyor" when the
 *         service broadcasts ACTION_RECORDING_STOPPED.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var isRecording = false
    private var isStopping = false

    /** Called by the service when recording has actually ended. */
    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success =
                intent?.getBooleanExtra(ScreenRecordService.EXTRA_RECORDING_SUCCEEDED, false)
                    ?: false
            if (success && isStopping) {
                Toast.makeText(
                    this@MainActivity,
                    "Video saqlandi: Movies/ScreenRecorder",
                    Toast.LENGTH_LONG
                ).show()
            }
            isStopping = false
            resetToIdleUi()
        }
    }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Activity.RESULT_OK is -1, so checking resultCode alone is
            // unreliable; a granted consent always carries a non-null data.
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
                isStopping = false
                setRecordingUi()
            } else {
                Toast.makeText(this, "Ruxsat berilmadi", Toast.LENGTH_SHORT).show()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val allGranted = perms.values.all { it }
            if (allGranted) {
                requestScreenCapture()
            } else {
                Toast.makeText(
                    this,
                    "Ruxsatlar berilmadi. Mikrofon ruxsati talab qilinadi.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener { onStopClicked() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ScreenRecordService.ACTION_RECORDING_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stoppedReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(stoppedReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered; nothing to do.
        }
    }

    // ------------------------------------------------------------------ //
    // Actions
    // ------------------------------------------------------------------ //

    private fun onStartClicked() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

    private fun onStopClicked() {
        if (!isRecording) return
        isStopping = true
        // The service is running in the foreground, so starting it again with
        // ACTION_STOP is allowed even though the app may be backgrounded.
        startService(Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        })
        setStoppingUi()
    }

    private fun requestScreenCapture() {
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    // ------------------------------------------------------------------ //
    // UI state
    // ------------------------------------------------------------------ //

    private fun setRecordingUi() {
        isRecording = true
        statusText.text = getString(R.string.recording_status)
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun setStoppingUi() {
        statusText.text = getString(R.string.stopping_status)
        btnStart.isEnabled = false
        btnStop.isEnabled = false
    }

    private fun resetToIdleUi() {
        isRecording = false
        isStopping = false
        statusText.text = getString(R.string.ready_status)
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }
}
