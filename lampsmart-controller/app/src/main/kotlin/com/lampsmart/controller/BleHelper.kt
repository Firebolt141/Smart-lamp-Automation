package com.lampsmart.controller

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlin.random.Random

/**
 * BleHelper — LampSmart Pro BLE Protocol (V1)
 *
 * PROTOCOL OVERVIEW:
 * Commands are sent as encoded 31-byte BLE advertisement broadcasts.
 * No pairing, no connection, no Service/Characteristic UUID.
 * Every lamp in BLE range (~10 m) that matches the group ID responds.
 *
 * ANDROID API NOTE:
 * Android cannot produce the exact non-standard AD type 0x03 the lamp expects.
 * This implementation uses AD type 0xFF (Manufacturer Specific).
 * Works if the lamp does loose byte matching; use an ESP32 bridge for guaranteed compat.
 *
 * COMMAND REFERENCE (from ESPHome lampsmart_pro_light component):
 *   CMD_TURN_ON  0x10  — main light on (last brightness/colour restored)
 *   CMD_TURN_OFF 0x11  — main light off
 *   CMD_DIM      0x21  — set cold (arg1 0-255) + warm (arg2 0-255) channels
 *                        cold=0,warm=255  → full warm white
 *                        cold=255,warm=0  → full cool white
 *                        cold=0,warm=0    → off (same as CMD_TURN_OFF)
 *
 * BACKLIGHT / GROUP:
 *   LampSmart Pro lamps are paired per-group (0-15).
 *   Main light = group 0, backlight circuit = often group 1.
 *   Change CURRENT_GROUP to target the correct group for your setup.
 *
 * HOW TO FIND YOUR LAMP MAC (for diagnostics — not used by this protocol):
 *   1. Install nRF Connect → Scanner tab → start scan.
 *   2. Look for "LampSmart" or a device with manufacturer data marker 0x0F71.
 *   3. The MAC is shown under the device name (e.g. AA:BB:CC:DD:EE:FF).
 */
object BleHelper {

    private const val TAG = "BleHelper"

    // ── Placeholder MAC (not used for broadcast protocol) ─────────────────
    const val MAC_ADDRESS = "REPLACE_WITH_YOUR_LAMP_MAC"

    // ── Command codes ────────────────────────────────────────────────────
    const val CMD_TURN_ON:  Byte = 0x10
    const val CMD_TURN_OFF: Byte = 0x11.toByte()
    const val CMD_DIM:      Byte = 0x21
    const val CMD_PAIR:     Byte = 0x28   // send while lamp is in pairing mode
    const val CMD_UNPAIR:   Byte = 0x45

    // ── Group ID (0-15) ───────────────────────────────────────────────────
    // 0 = main light on most lamps. Change to target the backlight circuit.
    var currentGroup: Byte = 0x00

    // ── Persistent host ID ────────────────────────────────────────────────
    // Generated once on first launch, stored in SharedPreferences.
    // The lamp stores this ID when paired and ignores commands from other IDs.
    // All commands (ON, OFF, DIM, PAIR) must carry the same hostId.
    private var storedHostId0: Byte = 0x00
    private var storedHostId1: Byte = 0x00
    private var hostIdInitialized = false

    fun initHostId(context: Context) {
        if (hostIdInitialized) return
        val prefs = context.getSharedPreferences("lampsmart_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains("host_id")) {
            prefs.edit().putInt("host_id", Random.nextInt(0x10000)).apply()
        }
        val id = prefs.getInt("host_id", 0)
        storedHostId0 = (id and 0xFF).toByte()
        storedHostId1 = ((id shr 8) and 0xFF).toByte()
        hostIdInitialized = true
        Log.d(TAG, "hostId loaded: 0x%02X%02X".format(
            storedHostId1.toInt() and 0xFF, storedHostId0.toInt() and 0xFF))
    }

    // ── Broadcast duration ────────────────────────────────────────────────
    private const val ADVERTISE_DURATION_MS = 3000

    // ── 32-byte base packet ───────────────────────────────────────────────
    private val PACKET_BASE = byteArrayOf(
        0x1F,                0x02, 0x01, 0x01,
        0x1B,                0x03,
        0x71, 0x0F.toByte(),
        0x55.toByte(), 0xAA.toByte(), 0x98.toByte(), 0x43,
        0xAF.toByte(), 0x0B, 0x46, 0x46,
        0x46, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x83.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    )

    // ── CRC16-CCITT table ─────────────────────────────────────────────────
    private val CRC_TABLE = intArrayOf(
        0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
        0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF,
        0x1231, 0x0210, 0x3273, 0x2252, 0x52B5, 0x4294, 0x72F7, 0x62D6,
        0x9339, 0x8318, 0xB37B, 0xA35A, 0xD3BD, 0xC39C, 0xF3FF, 0xE3DE,
        0x2462, 0x3443, 0x0420, 0x1401, 0x64E6, 0x74C7, 0x44A4, 0x5485,
        0xA56A, 0xB54B, 0x8528, 0x9509, 0xE5EE, 0xF5CF, 0xC5AC, 0xD58D,
        0x3653, 0x2672, 0x1611, 0x0630, 0x76D7, 0x66F6, 0x5695, 0x46B4,
        0xB75B, 0xA77A, 0x9719, 0x8738, 0xF7DF, 0xE7FE, 0xD79D, 0xC7BC,
        0x48C4, 0x58E5, 0x6886, 0x78A7, 0x0840, 0x1861, 0x2802, 0x3823,
        0xC9CC, 0xD9ED, 0xE98E, 0xF9AF, 0x8948, 0x9969, 0xA90A, 0xB92B,
        0x5AF5, 0x4AD4, 0x7AB7, 0x6A96, 0x1A71, 0x0A50, 0x3A33, 0x2A12,
        0xDBFD, 0xCBDC, 0xFBBF, 0xEB9E, 0x9B79, 0x8B58, 0xBB3B, 0xAB1A,
        0x6CA6, 0x7C87, 0x4CE4, 0x5CC5, 0x2C22, 0x3C03, 0x0C60, 0x1C41,
        0xEDAE, 0xFD8F, 0xCDEC, 0xDDCD, 0xAD2A, 0xBD0B, 0x8D68, 0x9D49,
        0x7E97, 0x6EB6, 0x5ED5, 0x4EF4, 0x3E13, 0x2E32, 0x1E51, 0x0E70,
        0xFF9F, 0xEFBE, 0xDFDD, 0xCFFC, 0xBF1B, 0xAF3A, 0x9F59, 0x8F78,
        0x9188, 0x81A9, 0xB1CA, 0xA1EB, 0xD10C, 0xC12D, 0xF14E, 0xE16F,
        0x1080, 0x00A1, 0x30C2, 0x20E3, 0x5004, 0x4025, 0x7046, 0x6067,
        0x83B9, 0x9398, 0xA3FB, 0xB3DA, 0xC33D, 0xD31C, 0xE37F, 0xF35E,
        0x02B1, 0x1290, 0x22F3, 0x32D2, 0x4235, 0x5214, 0x6277, 0x7256,
        0xB5EA, 0xA5CB, 0x95A8, 0x8589, 0xF56E, 0xE54F, 0xD52C, 0xC50D,
        0x34E2, 0x24C3, 0x14A0, 0x0481, 0x7466, 0x6447, 0x5424, 0x4405,
        0xA7DB, 0xB7FA, 0x8799, 0x97B8, 0xE75F, 0xF77E, 0xC71D, 0xD73C,
        0x26D3, 0x36F2, 0x0691, 0x16B0, 0x6657, 0x7676, 0x4615, 0x5634,
        0xD94C, 0xC96D, 0xF90E, 0xE92F, 0x99C8, 0x89E9, 0xB98A, 0xA9AB,
        0x5844, 0x4865, 0x7806, 0x6827, 0x18C0, 0x08E1, 0x3882, 0x28A3,
        0xCB7D, 0xDB5C, 0xEB3F, 0xFB1E, 0x8BF9, 0x9BD8, 0xABBB, 0xBB9A,
        0x4A75, 0x5A54, 0x6A37, 0x7A16, 0x0AF1, 0x1AD0, 0x2AB3, 0x3A92,
        0xFD2E, 0xED0F, 0xDD6C, 0xCD4D, 0xBDAA, 0xAD8B, 0x9DE8, 0x8DC9,
        0x7C26, 0x6C07, 0x5C64, 0x4C45, 0x3CA2, 0x2C83, 0x1CE0, 0x0CC1,
        0xEF1F, 0xFF3E, 0xCF5D, 0xDF7C, 0xAF9B, 0xBFBA, 0x8FD9, 0x9FF8,
        0x6E17, 0x7E36, 0x4E55, 0x5E74, 0x2E93, 0x3EB2, 0x0ED1, 0x1EF0
    )

    // =========================================================================
    // Encoding pipeline (direct port of lampsmart_utils.cpp)
    // =========================================================================

    private fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = CRC_TABLE[((crc shr 8) xor (data[offset + i].toInt() and 0xFF)) and 0xFF] xor (crc shl 8)
        }
        return crc and 0xFFFF
    }

    private fun bitReverse(data: ByteArray): ByteArray {
        val result = ByteArray(25)
        for (i in 0 until 25) {
            var rev = 0
            for (j in 0 until 8) {
                rev = rev or (((data[i].toInt() and 0xFF) shr (7 - j) and 1) shl j)
            }
            result[i] = rev.toByte()
        }
        return result
    }

    private fun bleWhitening(bArr: ByteArray): ByteArray {
        val whArr = ByteArray(38)
        var i2 = 83
        var i3 = 0
        while (i3 < 38) {
            var i4 = i2
            var b = 0
            var i5 = 0
            while (i5 < 8) {
                val i6 = i4 and 255
                b = b or ((((i6 and 64) shr 6) shl i5) xor (bArr[i3].toInt() and 255)) and (1 shl i5)
                val i7 = i6 shl 1
                val i8 = (i7 shr 7) and 1
                val i9 = (i7 and -2) or i8
                i4 = ((i9 xor (i8 shl 4)) and 16) or (i9 and -17)
                i5++
            }
            whArr[i3] = b.toByte()
            i2 = i4
            i3++
        }
        return whArr
    }

    private fun bleWhiteningForPacket(data: ByteArray): ByteArray {
        val whArr = ByteArray(38)
        for (i in 0 until 25) whArr[i + 13] = data[i]
        val whitened = bleWhitening(whArr)
        return whitened.sliceArray(13..37)
    }

    fun buildPacket(
        command:  Byte,
        groupId:  Byte = currentGroup,
        arg1:     Byte = 0x00,
        arg2:     Byte = 0x00,
        hostId0:  Byte = storedHostId0,
        hostId1:  Byte = storedHostId1
    ): ByteArray {
        val msgBase = ByteArray(25)
        for (i in 0 until 25) msgBase[i] = PACKET_BASE[i + 6]

        msgBase[11] = command
        msgBase[12] = hostId0
        msgBase[13] = ((hostId1.toInt() and 0xF0) or (groupId.toInt() and 0x0F)).toByte()
        msgBase[14] = arg1
        msgBase[15] = arg2
        msgBase[17] = Random.nextInt(256).toByte()

        val crc = crc16(msgBase, 11, 12)
        msgBase[23] = ((crc shr 8) and 0xFF).toByte()
        msgBase[24] = (crc and 0xFF).toByte()

        val reversed = bitReverse(msgBase)
        val whitened = bleWhiteningForPacket(reversed)

        val packet = ByteArray(32)
        for (i in 0 until 6)  packet[i]     = PACKET_BASE[i]
        for (i in 0 until 25) packet[i + 6] = whitened[i]
        packet[31] = PACKET_BASE[31]
        return packet
    }

    // =========================================================================
    // Public command API
    // =========================================================================

    /** Turn main light on (restores last brightness + colour). */
    fun turnOn(context: Context)  = sendCommand(context, CMD_TURN_ON)

    /** Turn main light off. */
    fun turnOff(context: Context) = sendCommand(context, CMD_TURN_OFF)

    /**
     * Pair this controller with the lamp.
     * Turn the lamp OFF then ON (or cycle power 3× quickly on some models) to
     * enter pairing mode, then call this within ~5 seconds.
     * The lamp stores the app's hostId and will only respond to it from then on.
     */
    fun pair(context: Context)    = sendCommand(context, CMD_PAIR)

    /** Clear the lamp's stored pairing (lamp reverts to factory state). */
    fun unpair(context: Context)  = sendCommand(context, CMD_UNPAIR)

    /**
     * Set brightness and colour temperature in one call.
     *
     * @param brightness  0.0 (off) – 1.0 (full brightness)
     * @param warmth      0.0 (full cool/natural white) – 1.0 (full warm white)
     */
    fun dim(context: Context, brightness: Float, warmth: Float) {
        val bri   = brightness.coerceIn(0f, 1f)
        val warm  = warmth.coerceIn(0f, 1f)
        val cold  = ((1f - warm) * bri * 255f).toInt().coerceIn(0, 255).toByte()
        val warmB = (warm * bri * 255f).toInt().coerceIn(0, 255).toByte()
        Log.d(TAG, "dim: brightness=%.0f%% warmth=%.0f%% cold=%d warm=%d"
            .format(bri * 100, warm * 100, cold.toInt() and 0xFF, warmB.toInt() and 0xFF))
        sendCommand(context, CMD_DIM, cold, warmB)
    }

    /**
     * Low-level command sender. All public helpers route through here.
     * @param arg1 cold-white channel (0-255) for CMD_DIM; 0 otherwise
     * @param arg2 warm-white channel (0-255) for CMD_DIM; 0 otherwise
     */
    fun sendCommand(
        context:  Context,
        command:  Byte,
        arg1:     Byte = 0x00,
        arg2:     Byte = 0x00
    ) {
        initHostId(context)  // ensure persistent hostId is loaded before building any packet
        Log.d(TAG, "sendCommand: 0x%02X arg1=%d arg2=%d group=%d hostId=0x%02X%02X"
            .format(command, arg1.toInt() and 0xFF, arg2.toInt() and 0xFF,
                    currentGroup.toInt() and 0xFF,
                    storedHostId1.toInt() and 0xFF, storedHostId0.toInt() and 0xFF))
        try {
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                Log.e(TAG, "Bluetooth adapter not available or disabled"); return
            }
            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(TAG, "BluetoothLeAdvertiser not available"); return
            }

            val packet    = buildPacket(command = command, arg1 = arg1, arg2 = arg2)
            val companyId = ((packet[7].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
            val mfrData   = packet.sliceArray(8..30)

            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(companyId, mfrData)
                .build()

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .setTimeout(ADVERTISE_DURATION_MS)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    Log.d(TAG, "Advertising started (${ADVERTISE_DURATION_MS}ms)")
                }
                override fun onStartFailure(errorCode: Int) {
                    val reason = when (errorCode) {
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                            "BLE advertising not supported on this device — use ESP32 bridge"
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                            "Too many BLE advertisers active — close other apps and try again"
                        ADVERTISE_FAILED_DATA_TOO_LARGE ->
                            "Advertising data too large (internal error)"
                        else -> "BLE advertising failed (error $errorCode)"
                    }
                    Log.e(TAG, reason)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context.applicationContext, reason, Toast.LENGTH_LONG).show()
                    }
                }
            }

            advertiser.startAdvertising(settings, advertiseData, callback)

            Handler(Looper.getMainLooper()).postDelayed({
                try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
            }, ADVERTISE_DURATION_MS.toLong() + 500L)

        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_ADVERTISE not granted: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext,
                    "Bluetooth permission denied — open the app and tap Allow on all permission prompts",
                    Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand failed: ${e.message}", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext,
                    "Command failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
