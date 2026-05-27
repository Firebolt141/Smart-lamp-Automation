package com.lampsmart.controller

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent activity that sends the "turn OFF" BLE command and immediately finishes.
 *
 * Triggered by:
 *   - The "Turn lamp off" app shortcut (long-press the app icon).
 *   - A Google Assistant Routine pointing to this activity's deep-link intent.
 *
 * No UI is shown — the activity is defined as transparent in the manifest theme.
 *
 * IMPORTANT — Android 12+ permission note:
 * BLUETOOTH_ADVERTISE must be granted at runtime before this activity can broadcast.
 * Open MainActivity first to grant the permission if you haven't done so already.
 */
class TurnOffActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("TurnOffActivity", "Sending TURN_OFF command")
        BleHelper.sendCommand(this, BleHelper.CMD_TURN_OFF)
        finish()
    }
}
