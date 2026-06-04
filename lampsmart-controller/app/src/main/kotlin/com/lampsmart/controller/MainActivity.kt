package com.lampsmart.controller

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
    }

    private var currentBrightness = 0.8f
    private var currentWarmth = 0.5f
    private var backlightActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkAndRequestBluetoothPermissions()
        setupButtons()
        updateStatusText()
    }

    private fun setupButtons() {
        // Power
        findViewById<Button>(R.id.btn_on).setOnClickListener {
            BleHelper.sendCommand(this, BleHelper.CMD_TURN_ON)
            Toast.makeText(this, "Turning lamp ON…", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_off).setOnClickListener {
            BleHelper.sendCommand(this, BleHelper.CMD_TURN_OFF)
            Toast.makeText(this, "Turning lamp OFF…", Toast.LENGTH_SHORT).show()
        }

        // Brightness SeekBar
        val tvBrightness = findViewById<TextView>(R.id.tv_brightness_value)
        val seekBrightness = findViewById<SeekBar>(R.id.seek_brightness)
        seekBrightness.progress = (currentBrightness * 100).toInt()
        tvBrightness.text = "${(currentBrightness * 100).toInt()}%"
        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBrightness = progress / 100f
                tvBrightness.text = "$progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Color temperature SeekBar: left = Warm (warmth=1.0), right = Cool (warmth=0.0)
        val tvWarmth = findViewById<TextView>(R.id.tv_warmth_value)
        val seekWarmth = findViewById<SeekBar>(R.id.seek_warmth)
        seekWarmth.progress = ((1f - currentWarmth) * 100).toInt()
        tvWarmth.text = warmthLabel(currentWarmth)
        seekWarmth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                currentWarmth = 1f - progress / 100f
                tvWarmth.text = warmthLabel(currentWarmth)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Apply
        findViewById<Button>(R.id.btn_apply).setOnClickListener {
            BleHelper.dim(this, currentBrightness, currentWarmth)
            Toast.makeText(this,
                "${(currentBrightness * 100).toInt()}% · ${warmthLabel(currentWarmth)}",
                Toast.LENGTH_SHORT).show()
        }

        // Presets
        findViewById<Button>(R.id.btn_preset_warm).setOnClickListener {
            applyPreset(0.75f, 1.0f, "Warm White")
        }
        findViewById<Button>(R.id.btn_preset_natural).setOnClickListener {
            applyPreset(0.8f, 0.5f, "Natural")
        }
        findViewById<Button>(R.id.btn_preset_cool).setOnClickListener {
            applyPreset(1.0f, 0.0f, "Cool White")
        }
        findViewById<Button>(R.id.btn_preset_night).setOnClickListener {
            applyPreset(0.1f, 1.0f, "Night Mode")
        }

        // Backlight toggle — switches BleHelper.currentGroup between 0 (main) and 1 (backlight)
        val btnBacklight = findViewById<Button>(R.id.btn_backlight)
        updateBacklightButton(btnBacklight)
        btnBacklight.setOnClickListener {
            backlightActive = !backlightActive
            BleHelper.currentGroup = (if (backlightActive) 1 else 0).toByte()
            updateBacklightButton(btnBacklight)
            Toast.makeText(this,
                if (backlightActive) "Controlling backlight (group 1)" else "Controlling main light (group 0)",
                Toast.LENGTH_SHORT).show()
        }

        // Voice commands
        findViewById<Button>(R.id.btn_voice).setOnClickListener {
            showVoiceCommandDialog()
        }
    }

    private fun applyPreset(brightness: Float, warmth: Float, name: String) {
        currentBrightness = brightness
        currentWarmth = warmth
        findViewById<SeekBar>(R.id.seek_brightness).progress = (brightness * 100).toInt()
        findViewById<SeekBar>(R.id.seek_warmth).progress = ((1f - warmth) * 100).toInt()
        findViewById<TextView>(R.id.tv_brightness_value).text = "${(brightness * 100).toInt()}%"
        findViewById<TextView>(R.id.tv_warmth_value).text = warmthLabel(warmth)
        BleHelper.dim(this, brightness, warmth)
        Toast.makeText(this, "$name applied", Toast.LENGTH_SHORT).show()
    }

    private fun warmthLabel(warmth: Float): String = when {
        warmth < 0.2f -> "Cool White"
        warmth < 0.4f -> "Cool Natural"
        warmth < 0.6f -> "Natural"
        warmth < 0.8f -> "Warm Natural"
        else           -> "Warm White"
    }

    private fun updateBacklightButton(btn: Button) {
        if (backlightActive) {
            btn.text = "Backlight Mode  (tap for Main Light)"
            btn.backgroundTintList = ColorStateList.valueOf(0xFF8855AA.toInt())
        } else {
            btn.text = "Main Light  (tap to control Backlight)"
            btn.backgroundTintList = ColorStateList.valueOf(0xFF334466.toInt())
        }
    }

    private fun showVoiceCommandDialog() {
        AlertDialog.Builder(this)
            .setTitle("Voice Commands")
            .setMessage(
                "Shortcuts work with Google Assistant — no extra setup:\n\n" +
                "  \"Hey Google, open Turn lamp on\"\n" +
                "  \"Hey Google, open Turn lamp off\"\n" +
                "  \"Hey Google, open Warm white preset\"\n" +
                "  \"Hey Google, open Cool white preset\"\n" +
                "  \"Hey Google, open Night mode\"\n\n" +
                "For a custom phrase (e.g. just \"lamp on\"), create a Routine:\n" +
                "  1. Open Google Home → Routines → +\n" +
                "  2. Starter: \"When I say…\" → enter your phrase\n" +
                "  3. Action: Open app feature → LampSmart Controller → choose shortcut\n" +
                "  4. Save — done.\n\n" +
                "Tap Open Google Home to get started."
            )
            .setPositiveButton("Open Google Home") { _, _ -> openGoogleHome() }
            .setNegativeButton("Got it", null)
            .show()
    }

    private fun openGoogleHome() {
        val pkg = "com.google.android.apps.chromecast.app"
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        startActivity(intent)
    }

    private fun updateStatusText() {
        val tv = findViewById<TextView>(R.id.tv_status)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bleEnabled = bluetoothManager?.adapter?.isEnabled == true
        tv.text = if (bleEnabled) "Bluetooth ON — ready to control your lamp"
                  else "Bluetooth OFF — please enable Bluetooth to send commands"
    }

    private fun checkAndRequestBluetoothPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED)
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(this,
                if (allGranted) "Bluetooth permissions granted"
                else "Bluetooth permissions denied — lamp control won't work",
                if (allGranted) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            updateStatusText()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusText()
    }
}
