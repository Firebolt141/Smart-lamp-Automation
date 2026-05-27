package com.lampsmart.controller

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.random.Random

/**
 * BleHelper — LampSmart Pro BLE Protocol Implementation
 *
 * IMPORTANT PROTOCOL NOTE:
 * LampSmart Pro does NOT use GATT (no BLE Service UUID, no Characteristic UUID).
 * Commands are sent as raw 31-byte BLE advertisement broadcasts.
 * The lamp passively scans for matching packets; there is NO connection.
 * This is a pure broadcast protocol — no MAC address is needed.
 *
 * The MAC_ADDRESS constant below is kept as a placeholder per the project spec but
 * is NOT used for sending commands. All lamps within BLE range will respond.
 *
 * Protocol source: https://github.com/powjie/lampsmart_pro_light (ESPHome component)
 * Encoding pipeline: build base packet → fill command bytes → CRC16 → bit-reverse → BLE whiten
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * IMPORTANT — Android API limitation:
 * Android's BluetoothLeAdvertiser does not allow arbitrary raw advertisement data.
 * The standard AdvertiseData.Builder only supports structured AD types (0x01 flags,
 * 0x03 service UUIDs, 0x16 service data, 0xFF manufacturer data, etc.).
 * The lamp expects a NON-STANDARD extension of AD type 0x03 carrying 25 encoded
 * bytes, which Android cannot produce via its public BLE API.
 *
 * This implementation uses AD type 0xFF (Manufacturer Specific) to carry the
 * encoded payload. Whether the lamp accepts it depends on how strictly it pattern-
 * matches the raw advertisement bytes:
 *   • Strict match (checks AD type) → won't work; use ESP32 bridge instead.
 *   • Loose match (raw byte scan)   → may work; worth trying.
 *
 * For guaranteed compatibility, pair with an ESP32 running the ESPHome component
 * (https://github.com/powjie/lampsmart_pro_light) and control it via MQTT/HTTP.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * HOW TO FIND YOUR LAMP MAC (for documentation / future GATT use):
 *   1. Install "nRF Connect" from the Play Store.
 *   2. Open nRF Connect → Scanner tab → start scan.
 *   3. Look for a device named "LampSmart" or similar (may show as "Unknown").
 *   4. The MAC address is shown under the device name (e.g., AA:BB:CC:DD:EE:FF).
 *
 * For the broadcast protocol, the MAC is irrelevant — skip this step.
 */
object BleHelper {

    private const val TAG = "BleHelper"

    // ── Placeholder MAC address ───────────────────────────────────────────────
    // NOTE: Not used for the broadcast protocol. Kept for reference.
    // Replace with your lamp's BT MAC if a future firmware version adds GATT.
    const val MAC_ADDRESS = "REPLACE_WITH_YOUR_LAMP_MAC"

    // ── Protocol command codes (from LampSmart Pro ESPHome source) ────────────
    const val CMD_TURN_ON: Byte  = 0x10
    const val CMD_TURN_OFF: Byte = 0x11.toByte()
    const val CMD_DIM: Byte      = 0x21

    // ── Group ID ─────────────────────────────────────────────────────────────
    // Change to 0–15 to target a specific lamp group programmed via the app.
    // 0 = broadcast to all groups.
    private const val GROUP_ID: Byte = 0x00

    // ── Advertisement broadcast duration (milliseconds) ───────────────────────
    // Lamps typically respond after 1–3 repeated broadcasts.
    private const val ADVERTISE_DURATION_MS = 3000

    // ── 32-byte base packet template ─────────────────────────────────────────
    // Byte [0] = 0x1F is a length prefix that is NOT sent over the air.
    // Bytes [1..3] = BLE Flags AD.
    // Bytes [4..5] = AD2 length + type (0x1B, 0x03 — custom 16-bit UUID structure).
    // Bytes [6..30] = 25-byte payload (overwritten during encoding).
    // Byte [31] = padding zero.
    private val PACKET_BASE = byteArrayOf(
        0x1F,       0x02, 0x01, 0x01,
        0x1B,       0x03,
        0x71, 0x0F.toByte(),
        0x55.toByte(), 0xAA.toByte(), 0x98.toByte(), 0x43,
        0xAF.toByte(), 0x0B, 0x46, 0x46,
        0x46, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x83.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    )

    // ── CRC16-CCITT lookup table ──────────────────────────────────────────────
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
    // Encoding pipeline (direct translation of lampsmart_utils.cpp)
    // =========================================================================

    /**
     * CRC16-CCITT (init=0xFFFF) over [length] bytes of [data] starting at [offset].
     */
    private fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = CRC_TABLE[((crc shr 8) xor (data[offset + i].toInt() and 0xFF)) and 0xFF] xor (crc shl 8)
        }
        return crc and 0xFFFF
    }

    /**
     * Reverse the bit order of each byte in a 25-byte array.
     * e.g. 0b10110001 → 0b10001101
     */
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

    /**
     * BLE whitening over a 38-byte array using a 7-bit LFSR with polynomial x^7+x^4+1,
     * initial seed = 83 (0x53).  Direct port of the C++ bleWhitening() function.
     *
     * The input array is processed byte-by-byte; each output byte is the XOR of the
     * LFSR bit-stream with the corresponding input byte.
     */
    private fun bleWhitening(bArr: ByteArray): ByteArray {
        val whArr = ByteArray(38)
        var i2 = 83          // LFSR initial state
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

    /**
     * Apply bleWhitening to a 25-byte payload by placing it at offset 13 of a
     * 38-byte working buffer (bytes 0-12 are zero) and extracting the whitened
     * result from positions 13-37.
     *
     * This matches the bleWhiteningForPacket() function in lampsmart_utils.cpp.
     */
    private fun bleWhiteningForPacket(data: ByteArray): ByteArray {
        val whArr = ByteArray(38)  // zero-initialised
        for (i in 0 until 25) {
            whArr[i + 13] = data[i]
        }
        val whitened = bleWhitening(whArr)
        return whitened.sliceArray(13..37)
    }

    /**
     * Build the full 32-byte encoded packet for a given command.
     *
     * @param command  Protocol command byte (CMD_TURN_ON / CMD_TURN_OFF / CMD_DIM).
     * @param groupId  Lamp group (0-15). Default 0 = all groups.
     * @param arg1     First argument (e.g. cold-white value 0-255 for CMD_DIM).
     * @param arg2     Second argument (e.g. warm-white value 0-255 for CMD_DIM).
     * @param hostId0  First byte of host identifier (uses Bluetooth adapter address tail).
     * @param hostId1  Second byte of host identifier.
     * @return         32-byte packet; byte[0]=0x1F is the length prefix (not transmitted).
     *                 Bytes [1..31] are the actual 31-byte BLE advertisement payload.
     */
    fun buildPacket(
        command: Byte,
        groupId: Byte  = GROUP_ID,
        arg1: Byte     = 0x00,
        arg2: Byte     = 0x00,
        hostId0: Byte  = 0x00,
        hostId1: Byte  = 0x00
    ): ByteArray {
        // msgBase = copy of PACKET_BASE[6..30] (25 bytes)
        val msgBase = ByteArray(25)
        for (i in 0 until 25) {
            msgBase[i] = PACKET_BASE[i + 6]
        }

        // Fill command fields (indices relative to msgBase):
        //   msgBase[11] maps to PACKET_BASE[17]
        msgBase[11] = command
        msgBase[12] = hostId0
        msgBase[13] = ((hostId1.toInt() and 0xF0) or (groupId.toInt() and 0x0F)).toByte()
        msgBase[14] = arg1
        msgBase[15] = arg2
        msgBase[17] = Random.nextInt(256).toByte()   // anti-replay nonce

        // CRC16 over msgBase[11..22] (12 bytes)
        val crc = crc16(msgBase, 11, 12)
        msgBase[23] = ((crc shr 8) and 0xFF).toByte()
        msgBase[24] = (crc and 0xFF).toByte()

        Log.d(TAG, "buildPacket: cmd=0x%02X crc=0x%04X".format(command, crc))

        // Encoding: bit-reverse then BLE-whiten
        val reversed  = bitReverse(msgBase)
        val whitened  = bleWhiteningForPacket(reversed)

        // Assemble final 32-byte packet
        val packet = ByteArray(32)
        for (i in 0 until 6)  { packet[i]     = PACKET_BASE[i] }
        for (i in 0 until 25) { packet[i + 6] = whitened[i]    }
        packet[31] = PACKET_BASE[31]

        return packet
    }

    // =========================================================================
    // BLE advertising
    // =========================================================================

    /**
     * Send a LampSmart Pro command by broadcasting a BLE advertisement.
     *
     * The encoded 25-byte payload is transmitted as Manufacturer Specific Data
     * (AD type 0xFF) because Android's public BLE API does not allow raw
     * advertisement byte injection.  The first 2 bytes of the payload are used
     * as the Bluetooth company ID field (little-endian), and the remaining 23
     * bytes follow as manufacturer data.
     *
     * Broadcasting continues for ADVERTISE_DURATION_MS (default 3 s) to give
     * the lamp enough time to receive the packet on all three advertising channels.
     *
     * @param context  Android context (activity or application).
     * @param command  Protocol command byte.
     * @param arg1     Argument 1 (0 for ON/OFF; cold-white 0-255 for DIM).
     * @param arg2     Argument 2 (0 for ON/OFF; warm-white 0-255 for DIM).
     */
    fun sendCommand(
        context: Context,
        command: Byte,
        arg1: Byte = 0x00,
        arg2: Byte = 0x00
    ) {
        Log.d(TAG, "sendCommand: 0x%02X".format(command))
        try {
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                Log.e(TAG, "Bluetooth adapter not available or disabled")
                return
            }

            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(TAG, "BluetoothLeAdvertiser not available (BLE not supported?)")
                return
            }

            // Build the encoded packet (32 bytes)
            val packet = buildPacket(command = command, arg1 = arg1, arg2 = arg2)

            // Bytes [6..30] are the 25 encoded payload bytes.
            // Split into: company ID (bytes [6..7] as little-endian uint16)
            //             + manufacturer data (bytes [8..30], 23 bytes).
            val companyId   = ((packet[7].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
            val mfrData     = packet.sliceArray(8..30)

            Log.d(TAG, "Advertising company=0x%04X payload=%s"
                .format(companyId, mfrData.joinToString("") { "%02X".format(it) }))

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
                    Log.d(TAG, "Advertising started — broadcasting for ${ADVERTISE_DURATION_MS}ms")
                }
                override fun onStartFailure(errorCode: Int) {
                    val reason = when (errorCode) {
                        ADVERTISE_FAILED_ALREADY_STARTED    -> "already started"
                        ADVERTISE_FAILED_DATA_TOO_LARGE     -> "data too large"
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                        ADVERTISE_FAILED_INTERNAL_ERROR     -> "internal error"
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                        else -> "unknown ($errorCode)"
                    }
                    Log.e(TAG, "Advertising failed: $reason")
                }
            }

            advertiser.startAdvertising(settings, advertiseData, callback)
            Log.d(TAG, "startAdvertising() called")

            // Auto-stop after ADVERTISE_DURATION_MS (belt-and-suspenders alongside setTimeout)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    advertiser.stopAdvertising(callback)
                    Log.d(TAG, "Advertising stopped")
                } catch (e: Exception) {
                    Log.w(TAG, "stopAdvertising: ${e.message}")
                }
            }, ADVERTISE_DURATION_MS.toLong() + 500L)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException — BLUETOOTH_ADVERTISE permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand failed: ${e.message}", e)
        }
    }
}
