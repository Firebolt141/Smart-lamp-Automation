# LampSmart Pro BLE Android Controller

[![Build & Release APK](https://github.com/Firebolt141/Smart-lamp-Automation/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Firebolt141/Smart-lamp-Automation/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/Firebolt141/Smart-lamp-Automation?label=latest%20APK&color=gold)](https://github.com/Firebolt141/Smart-lamp-Automation/releases/latest)

Control your **LampSmart Pro** ceiling lamp with a single voice command or tap,  
no cloud or hub required — just your Android phone's BLE radio.

> **Always get the latest APK →** [Releases page](https://github.com/Firebolt141/Smart-lamp-Automation/releases/latest)  
> Every commit to `main` automatically builds and publishes a new versioned release.

---

## How it works

LampSmart Pro lamps use a **BLE advertising broadcast** protocol, not a GATT  
connection. There is no pairing and no dedicated Service/Characteristic UUID.  
Instead, commands are sent as specially-encoded 31-byte advertisement packets  
that the lamp "hears" while scanning.

The encoding pipeline (from the reverse-engineered  
[ESPHome component](https://github.com/powjie/lampsmart_pro_light)):

```
build base packet  →  fill command bytes  →  CRC16-CCITT  →  bit-reverse each byte  →  BLE-whiten  →  broadcast
```

Command codes:
| Action     | Byte   |
|------------|--------|
| Power ON   | `0x10` |
| Power OFF  | `0x11` |
| Brightness | `0x21` |

---

## ⚠️ Android API limitation

Android's public BLE API (`BluetoothLeAdvertiser`) does **not** allow raw  
advertisement byte injection. The lamp expects a non-standard extension of  
AD type `0x03` carrying 25 encoded bytes; Android cannot produce this with  
its `AdvertiseData.Builder`.

This app uses **AD type `0xFF` (Manufacturer Specific Data)** to carry the  
encoded payload as a best-effort approach:

- **If your lamp does loose byte-pattern matching** → it will likely work.  
- **If your lamp checks the AD type strictly** → use an ESP32 bridge (see below).

---

## Setup

### 1. Build the APK

Trigger the GitHub Actions workflow:

1. Push to the `main` branch **or** click **Actions → Build LampSmart Controller APK → Run workflow**.
2. Wait ~3–5 minutes for the build to complete.
3. Download the artifact: **Actions → (latest run) → Artifacts → LampSmart-Controller-APK → `app-debug.apk`**.

### 2. Install the APK on Android

1. Transfer `app-debug.apk` to your phone (USB, email, Google Drive, etc.).
2. On your phone: **Settings → Apps → Special app access → Install unknown apps**  
   (exact path varies by Android version), then enable for your file manager or browser.
3. Tap the APK file and follow the prompts.

### 3. Grant Bluetooth permissions (Android 12+)

On first launch, **MainActivity** requests three runtime permissions:

- `BLUETOOTH_ADVERTISE` — broadcast commands (**required**)
- `BLUETOOTH_CONNECT` — check adapter state
- `BLUETOOTH_SCAN` — used by the advertising stack on some chipsets

Tap **Allow** for each. Without `BLUETOOTH_ADVERTISE` the commands won't be sent.

### 4. Find your lamp's Bluetooth MAC (optional / informational)

The broadcast protocol does **not** need your lamp's MAC address — all lamps  
in BLE range (~10 m) respond to matching broadcasts. However, if you want the  
MAC for diagnostics or to filter in `nRF Connect`:

1. Install **nRF Connect** from the Play Store.
2. **Scanner** tab → tap **Scan**.
3. Look for a device advertising "LampSmart" or with manufacturer data matching  
   the protocol marker `0x0F71`.
4. The MAC is shown under the device name (e.g. `AA:BB:CC:DD:EE:FF`).

> The `MAC_ADDRESS` constant in `BleHelper.kt` is a placeholder for future  
> use (e.g. a GATT firmware variant). It is not used for sending commands.

### 5. Test the app

Open **LampSmart Controller** → tap **Turn ON** / **Turn OFF**.  
LED broadcast lasts 3 seconds; your lamp should respond within that window.

---

## Google Assistant voice commands

### Option A — App Shortcuts (simplest)

1. Long-press the **LampSmart Controller** icon on your home screen.
2. You'll see two shortcuts: **Turn lamp on** and **Turn lamp off**.
3. Drag them to your home screen for one-tap control.

Google Assistant can run these shortcuts with:
> *"Hey Google, open Turn lamp on"*  
> *"Hey Google, open Turn lamp off"*

### Option B — Google Assistant Routines

1. Open the **Google Home** or **Google Assistant** app.
2. Go to **Routines** → tap **+** to create a new routine.
3. **Starter**: choose "When I say…" and enter your phrase (e.g. *"lamp on"*).
4. **Action**: tap **Add action → App actions → Open app feature**.
5. Select **LampSmart Controller** and choose the **Turn lamp on** shortcut.
6. Save. Repeat for "lamp off".

Now say: *"Hey Google, lamp on"* — the routine triggers the shortcut.

### Option C — Direct Activity Launch (advanced)

If you have a Tasker/Automate/IFTTT setup, you can trigger the activities directly:

```
Component: com.lampsmart.controller/.TurnOnActivity
Action:    android.intent.action.VIEW
```

```
Component: com.lampsmart.controller/.TurnOffActivity
Action:    android.intent.action.VIEW
```

---

## Adjusting the lamp group

The lamp can be programmed into groups (0–15) using the LampSmart Pro app.  
To target a specific group, edit `BleHelper.kt`:

```kotlin
private const val GROUP_ID: Byte = 0x00   // ← change to 1–15
```

Group `0` broadcasts to all groups.

---

## ESP32 bridge (for guaranteed compatibility)

If the Android BLE API limitation prevents reliable control, use an  
**ESP32 as a BLE bridge**:

1. Flash the [ESPHome LampSmart Pro Light component](https://github.com/powjie/lampsmart_pro_light).
2. Configure ESPHome with Home Assistant or standalone MQTT.
3. Call the lamp switch via HTTP/MQTT from any device on your local network.

The ESP32 uses `esp_ble_gap_config_adv_data_raw()` to send the exact raw  
31-byte advertisement, bypassing the Android API limitation.

---

## Project structure

```
lampsmart-controller/
├── .github/workflows/build.yml          # GitHub Actions CI — builds the APK
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/lampsmart/controller/
│       │   ├── BleHelper.kt             ← Full protocol encoding + BLE advertising
│       │   ├── MainActivity.kt          ← Launcher, permission handler, test buttons
│       │   ├── TurnOnActivity.kt        ← Transparent activity: sends ON command
│       │   └── TurnOffActivity.kt       ← Transparent activity: sends OFF command
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/strings.xml
│           ├── values/themes.xml
│           └── xml/shortcuts.xml        ← Static app shortcuts
├── build.gradle                         # Root Gradle config (AGP 8.1.4 + Kotlin 1.9)
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
└── gradle/wrapper/
    ├── gradle-wrapper.jar
    └── gradle-wrapper.properties        # Gradle 8.4 bin distribution
```

---

## Building locally

```bash
cd lampsmart-controller
chmod +x gradlew
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and Android SDK 34.

---

## Protocol reference

- ESPHome component (V1 protocol): <https://github.com/powjie/lampsmart_pro_light>  
- Homebridge plugin (V2 with AES): <https://github.com/SanderSteeghs/homebridge-lampsmart-pro-plugin>  
- Original HA integration (archived): `georgezhao2015/lampsmart_pro`

The V1 protocol used in this app:
- Packet base: 32 bytes, first byte (`0x1F`) is a length prefix not sent over the air.
- Encoding: CRC16-CCITT (init=`0xFFFF`) over 12 bytes → bit-reverse each of 25 bytes →  
  BLE-whiten (7-bit LFSR, seed=83, poly `x⁷+x⁴+1`) over 38-byte padded buffer.
- Advertising: `ADV_TYPE_NONCONN_IND`, non-connectable, no scan response.
- Broadcast duration: ~3 seconds (across all three advertising channels: 37, 38, 39).

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Buttons do nothing | Bluetooth OFF or permission denied | Enable BT; open MainActivity and grant permissions |
| "Advertising failed: feature unsupported" | Device doesn't support BLE peripheral mode | Use a different phone; most Android 5+ phones support it |
| "Advertising failed: too many advertisers" | Other apps are advertising | Stop them or reboot the phone |
| Lamp doesn't respond | AD type mismatch (see limitation note) | Try ESP32 bridge |
| Lamp responds intermittently | BLE range / interference | Move closer; 2.4 GHz Wi-Fi can interfere |
| Android 12+ permission crash | BLUETOOTH_ADVERTISE not granted | Open MainActivity, tap Allow on all prompts |
