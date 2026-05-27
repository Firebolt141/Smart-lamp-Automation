package com.lampsmart.controller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestBluetoothPermissions()
        updateStatusText()
        setupButtons()
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btn_on).setOnClickListener {
            BleHelper.sendCommand(this, BleHelper.CMD_TURN_ON)
            Toast.makeText(this, "Turning lamp ON…", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_off).setOnClickListener {
            BleHelper.sendCommand(this, BleHelper.CMD_TURN_OFF)
            Toast.makeText(this, "Turning lamp OFF…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatusText() {
        val tv = findViewById<TextView>(R.id.tv_status)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bleEnabled = bluetoothManager?.adapter?.isEnabled == true
        tv.text = if (bleEnabled) {
            "✅ Bluetooth is ON\n\nLampSmart Controller is ready.\n\n" +
            "Use the buttons above, Google Assistant, or app shortcuts to control your lamp.\n\n" +
            "Protocol note: This app broadcasts BLE advertisement packets.\n" +
            "No MAC address is needed — the lamp listens for encoded command packets.\n\n" +
            "See BleHelper.kt and the README for setup details."
        } else {
            "⚠️ Bluetooth is OFF — please enable Bluetooth to send commands.\n\n" +
            "LampSmart Controller is installed. Use Google Assistant or shortcuts " +
            "to control your lamp once Bluetooth is enabled."
        }
    }

    private fun checkAndRequestBluetoothPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires runtime grants for BLUETOOTH_ADVERTISE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                REQUEST_BLUETOOTH_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "Bluetooth permissions granted ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "⚠️ Bluetooth permissions denied — lamp control won't work.",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateStatusText()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusText()
    }
}
