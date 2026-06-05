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
 * PROTOCOL: ha-ble-adv FanLampEncoderV1 (NicoIIT/ha-ble-adv)
 * Commands broadcast as BLE manufacturer-specific advertisements (AD type 0xFF).
 * 24-byte payload = 8-byte clear prefix + 16-byte encrypted body.
 * No GATT connection, no pairing in the BLE sense — lamp matches by device ID.
 *
 * ANDROID NOTE:
 * Android cannot produce AD type 0x03 (which the lamp spec uses).
 * We use addManufacturerData(0xF877, ...) which produces AD type 0xFF.
 * Both produce identical wire bytes except for the 1-byte AD type field.
 */
object BleHelper {

    private const val TAG = "BleHelper"

    // ── Command codes ─────────────────────────────────────────────────────────
    const val CMD_TURN_ON:  Byte = 0x10
    const val CMD_TURN_OFF: Byte = 0x11.toByte()
    const val CMD_DIM:      Byte = 0x21
    const val CMD_PAIR:     Byte = 0x28
    const val CMD_UNPAIR:   Byte = 0x45

    // ── Group ID (0-15): main light = 0, backlight circuit = 1 ───────────────
    var currentGroup: Byte = 0x00

    // ── Protocol constants (FanLampEncoderV1, conf_seed=0x81) ────────────────
    private val PREFIX = byteArrayOf(
        0xAA.toByte(), 0x98.toByte(), 0x43, 0xAF.toByte(), 0x0B, 0x46, 0x46, 0x46
    )
    private const val CONF_SEED = 0x81
    private const val COMPANY_ID = 0xF877  // little-endian on wire: [0x77, 0xF8]

    // ── Persistent device ID ──────────────────────────────────────────────────
    private var storedId: Int = 0   // 16-bit, kept as id & 0xF0FF
    private var txCount:  Int = 0
    private var initialized = false

    fun initHostId(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences("lampsmart_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains("device_id")) {
            prefs.edit().putInt("device_id", Random.nextInt(0x10000) and 0xF0FF).apply()
        }
        storedId = prefs.getInt("device_id", 0x1234) and 0xF0FF
        txCount  = prefs.getInt("tx_count",  0)
        initialized = true
        Log.d(TAG, "storedId=0x%04X txCount=%d".format(storedId, txCount))
    }

    private fun saveState(context: Context) {
        context.getSharedPreferences("lampsmart_prefs", Context.MODE_PRIVATE)
            .edit().putInt("tx_count", txCount).apply()
    }

    // ── Broadcast duration ────────────────────────────────────────────────────
    private const val ADVERTISE_DURATION_MS = 3000

    // ── CRC16-CCITT table (poly 0x1021) ──────────────────────────────────────
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
    // Encoding pipeline — exact port of ha-ble-adv FanLampEncoderV1
    // =========================================================================

    private fun crc16(data: ByteArray, offset: Int, length: Int, init: Int = 0xFFFF): Int {
        var crc = init and 0xFFFF
        for (i in 0 until length) {
            crc = CRC_TABLE[((crc shr 8) xor (data[offset + i].toInt() and 0xFF)) and 0xFF] xor (crc shl 8)
        }
        return crc and 0xFFFF
    }

    // Reverse all bits in every byte of the buffer.
    private fun reverseAll(buffer: ByteArray): ByteArray = ByteArray(buffer.size) { i ->
        var x = buffer[i].toInt() and 0xFF
        x = ((x and 0x55) shl 1) or ((x and 0xAA) ushr 1)
        x = ((x and 0x33) shl 2) or ((x and 0xCC) ushr 2)
        x = ((x and 0x0F) shl 4) or ((x and 0xF0) ushr 4)
        x.toByte()
    }

    // BLE LFSR whitening, poly x^7+x^4+1 (tap mask 0x11), left-shift variant.
    private fun whiten(buffer: ByteArray, seed: Int): ByteArray {
        val result = ByteArray(buffer.size)
        var r = seed and 0x7F
        for ((idx, byte) in buffer.withIndex()) {
            var b = 0
            for (j in 0 until 8) {
                r = r shl 1
                if (r and 0x80 != 0) {
                    r = r xor 0x11
                    b = b or (1 shl j)
                }
                r = r and 0x7F
            }
            result[idx] = (byte.toInt() xor b).toByte()
        }
        return result
    }

    fun buildPacket(
        command: Byte,
        arg0:    Byte = 0,
        arg1:    Byte = 0,
        arg2:    Byte = 0,
        param:   Byte = 0
    ): ByteArray {
        val isPair   = command == CMD_PAIR || command == CMD_UNPAIR
        val index    = currentGroup.toInt() and 0x0F
        val uidField = (storedId and 0xF0FF) or (index shl 8)

        val B = ByteArray(14)
        B[0] = command
        B[1] = (uidField and 0xFF).toByte()
        B[2] = ((uidField shr 8) and 0xFF).toByte()
        B[3] = if (isPair) (storedId and 0xFF).toByte() else arg0
        B[4] = if (isPair) ((storedId shr 8) and 0xF0).toByte() else arg1
        B[5] = arg2
        B[6] = (txCount and 0xFF).toByte().also { txCount = (txCount + 1) and 0xFF }
        B[7] = if (isPair) 0x77.toByte() else param

        // Seed identification bytes (conf_seed=0x81, xor1=True)
        B[8]  = (CONF_SEED xor 1).toByte()             // 0x80
        B[9]  = (CONF_SEED xor 1).toByte()             // 0x80
        B[10] = ((CONF_SEED shr 8) and 0xFF).toByte()  // 0x00
        B[11] = (CONF_SEED and 0xFF).toByte()           // 0x81

        // CRC1 over B[0..11], init = CONF_SEED ^ 0xFFFF = 0xFF7E
        val crc1 = crc16(B, 0, 12, CONF_SEED xor 0xFFFF)
        B[12] = ((crc1 shr 8) and 0xFF).toByte()
        B[13] = (crc1 and 0xFF).toByte()

        // CRC2 over B[0..13], init = 0xA5BE
        val crc2 = crc16(B, 0, 14, 0xA5BE)
        val crc2Bytes = byteArrayOf(((crc2 shr 8) and 0xFF).toByte(), (crc2 and 0xFF).toByte())

        // 16-byte encrypted body: whiten(reverseAll(B + crc2Bytes), seed=0x0C)
        val encrypted  = whiten(reverseAll(B + crc2Bytes), 0x0C)

        // 8-byte clear prefix: whiten(reverseAll(PREFIX), seed=0x6F)
        val clearPrefix = whiten(reverseAll(PREFIX), 0x6F)

        // Final 24-byte manufacturer payload
        return clearPrefix + encrypted
    }

    // =========================================================================
    // Public command API
    // =========================================================================

    fun turnOn(context: Context)  = sendCommand(context, CMD_TURN_ON)
    fun turnOff(context: Context) = sendCommand(context, CMD_TURN_OFF)
    fun pair(context: Context)    = sendCommand(context, CMD_PAIR)
    fun unpair(context: Context)  = sendCommand(context, CMD_UNPAIR)

    fun dim(context: Context, brightness: Float, warmth: Float) {
        val bri   = brightness.coerceIn(0f, 1f)
        val warm  = warmth.coerceIn(0f, 1f)
        val cold  = ((1f - warm) * bri * 255f).toInt().coerceIn(0, 255).toByte()
        val warmB = (warm * bri * 255f).toInt().coerceIn(0, 255).toByte()
        Log.d(TAG, "dim: brightness=%.0f%% warmth=%.0f%% cold=%d warm=%d"
            .format(bri * 100, warm * 100, cold.toInt() and 0xFF, warmB.toInt() and 0xFF))
        sendCommand(context, CMD_DIM, cold, warmB)
    }

    fun sendCommand(
        context: Context,
        command: Byte,
        arg0:    Byte = 0x00,
        arg1:    Byte = 0x00
    ) {
        initHostId(context)
        Log.d(TAG, "sendCommand: 0x%02X arg0=%d arg1=%d group=%d storedId=0x%04X txCount=%d"
            .format(command, arg0.toInt() and 0xFF, arg1.toInt() and 0xFF,
                    currentGroup.toInt() and 0xFF, storedId, txCount))
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

            val mfrData = buildPacket(command = command, arg0 = arg0, arg1 = arg1)
            saveState(context)

            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(COMPANY_ID, mfrData)
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
