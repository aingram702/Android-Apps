# Bluetooth Surveillance Scanner

An Android app that continuously scans for Bluetooth-based surveillance devices and spy gadgets in your environment. It runs as a persistent foreground service, identifies known tracking hardware by device name and MAC address manufacturer prefix, and alerts you in real time when a high-risk device is detected nearby.

---

## Features

- **Dual-mode scanning** — simultaneously runs BLE (Bluetooth Low Energy) and Classic Bluetooth discovery for maximum coverage
- **Continuous background operation** — foreground service keeps scanning with the screen off
- **Threat classification** — every detected device is rated HIGH RISK, SUSPICIOUS, LOW RISK, or UNKNOWN based on multiple signals
- **Instant alerts** — vibrating push notification fires the moment a high-risk device is found
- **Signal strength analysis** — RSSI reading with proximity description (Very Strong / Strong / Medium / Weak) and a live signal bar
- **Seen counter** — tracks how many times each device has been detected to surface persistent hidden devices
- **Device detail view** — tap any entry to see full details: name, MAC address, manufacturer, type, signal, and the specific reason it was flagged
- **CSV export** — share a full scan report via any Android share target (email, Drive, etc.)
- **Clear / reset** — wipe the device list at any time without stopping the scan

---

## Detection Coverage

The app identifies devices across four categories:

### Commercial Trackers (HIGH RISK)
| Device | Detection Method |
|--------|-----------------|
| Apple AirTag | Device name + MAC OUI (Apple FindMy range) |
| Tile (Mate, Slim, Pro, Sport, Sticker) | Device name + MAC OUI (Tile Inc.) |
| Samsung Galaxy SmartTag / SmartTag+ | Device name + MAC OUI (Samsung) |
| Chipolo One | Device name + MAC OUI |
| TrackR | Device name + MAC OUI |
| Orbit / Cube / Pebblebee / Jiobit | Device name pattern |

### Hidden Cameras with Bluetooth (HIGH / MEDIUM RISK)
- SQ8, SQ11, SQ12, SQ13 spy camera modules
- P1/P2 cam, keychain cam, clock cam, mirror cam, smoke cam
- Generic "IP Camera", "Mini Camera", "Hidden Cam" advertisement names

### Spy Audio Devices (HIGH / MEDIUM RISK)
- Nano earpieces and spy earpieces advertising over Bluetooth
- Devices advertising names containing "spy", "mini earphone", or "wireless earpiece"

### DIY / Unknown Tracking Hardware (MEDIUM RISK)
Devices whose MAC address OUI matches chip manufacturers commonly used in custom or cheap tracking hardware:
- **Espressif Systems** (ESP32 / ESP8266) — widely used in DIY trackers
- **Telink Semiconductor** — common in low-cost BLE spy gadgets
- **Nordic Semiconductor** — popular BLE SoC in custom hardware

### Unnamed Strong-Signal Devices (LOW RISK)
Any device broadcasting with no name (or a generic placeholder) and a signal stronger than -65 dBm is flagged — a pattern consistent with a hidden device concealed nearby.

---

## Threat Levels

| Level | Color | Meaning |
|-------|-------|---------|
| **HIGH RISK** | Red | Matches a known tracking or surveillance device by name or manufacturer |
| **SUSPICIOUS** | Orange | Name or OUI suggests tracking/surveillance capability |
| **LOW RISK** | Amber | Behavioral flag (e.g., unnamed device with strong signal) |
| **UNKNOWN** | Grey | No known surveillance signatures — shown for completeness |

---

## Screenshots

```
┌─────────────────────────────────────────┐
│  BT Surveillance Scanner          [≡]   │
├─────────────────────────────────────────┤
│ Devices: 4  │  High Risk: 1  │ Susp: 2  │
│ Scanning…                               │
├─────────────────────────────────────────┤
│ ┌─────────────────────────── HIGH RISK┐ │
│ │ AirTag                              │ │
│ │ AC:BC:32:xx:xx:xx          BLE      │ │
│ │ Apple (AirTag / FindMy)             │ │
│ │ ─────────────────────────────────  │ │
│ │ Known tracking device: 'airtag'     │ │
│ │ ████████░░  -52 dBm (Strong)  Seen 3│ │
│ └─────────────────────────────────────┘ │
│ ┌──────────────────────── SUSPICIOUS ┐  │
│ │ Unknown Device              BLE     │ │
│ │ A4:C1:38:xx:xx:xx                   │ │
│ │ Telink Semiconductor (BLE module)   │ │
│ │ ─────────────────────────────────   │ │
│ │ MAC matches DIY/cheap BLE module    │ │
│ │ ██████░░░░  -68 dBm (Medium) Seen 1 │ │
│ └─────────────────────────────────────┘ │
│                                         │
│                        [ Stop Scan ]    │
└─────────────────────────────────────────┘
```

---

## Requirements

- Android **6.0 (API 23)** or higher
- Bluetooth hardware (BLE support required)
- Permissions granted at runtime:
  - `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (Android 12+)
  - `BLUETOOTH` + `BLUETOOTH_ADMIN` (Android 6–11)
  - `ACCESS_FINE_LOCATION` (required by Android for BLE scanning on API 23–30)

---

## Building

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 8+
- Gradle 8.2

### Steps

```bash
# Clone the repository
git clone https://github.com/aingram702/android-apps.git
cd android-apps/BluetoothSurveillanceScanner

# Build a debug APK
./gradlew assembleDebug

# Install directly to a connected device
./gradlew installDebug
```

The debug APK will be output to:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Project Structure

```
BluetoothSurveillanceScanner/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/bluetoothscanner/surveillance/
│       │   ├── MainActivity.kt          # UI, permissions, export
│       │   ├── ScanService.kt           # Foreground service, BLE + Classic scan loops
│       │   ├── SurveillanceDetector.kt  # Detection engine (names, OUIs, heuristics)
│       │   ├── DeviceAdapter.kt         # RecyclerView adapter with live upsert
│       │   └── model/
│       │       └── BtDeviceInfo.kt      # Device data model + threat level enum
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml    # Main screen layout
│           │   └── item_device.xml      # Device list row layout
│           ├── menu/main_menu.xml
│           ├── drawable/bg_badge.xml
│           └── values/
│               ├── colors.xml
│               ├── strings.xml
│               └── themes.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## How It Works

1. **Service start** — tapping "Start Scan" launches `ScanService` as a foreground service with a persistent notification, keeping it alive while the screen is off.

2. **BLE scan** — uses `BluetoothLeScanner` in `SCAN_MODE_LOW_LATENCY` with a `ScanCallback`. Results stream continuously as devices advertise.

3. **Classic scan** — calls `BluetoothAdapter.startDiscovery()`. On `ACTION_DISCOVERY_FINISHED` the scan is immediately restarted, creating a continuous loop.

4. **Analysis** — each discovered device is passed to `SurveillanceDetector.analyze()`, which checks the device name against pattern lists and the MAC OUI against a manufacturer table, then assigns a `ThreatLevel`.

5. **Broadcast** — `ScanService` sends a local broadcast with the device data. `MainActivity` receives it and calls `DeviceAdapter.upsert()`, which either adds a new row or updates an existing one in place, then re-sorts by threat level.

6. **Alert** — if the threat level is HIGH, `ScanService` also fires a high-priority system notification with vibration.

---

## Limitations & Notes

- **BLE-only devices** (like AirTags) are only visible during their advertisement windows. Apple's AirTag rotates its MAC address periodically to prevent tracking — this app will show it as a new device each rotation cycle.
- **Classic Bluetooth** discovery takes approximately 12 seconds per cycle and requires the remote device to be in discoverable mode. Many spy devices only use BLE.
- **MAC OUI matching** is heuristic — the same chip vendor supplies components for both legitimate consumer devices and spy hardware. MEDIUM-risk flags for OUI matches should be investigated, not treated as confirmed.
- **Location permission** is not used for location purposes — Android mandates it for any app that performs Bluetooth scanning on API 23–30.
- The app does **not** connect to any detected device, only passively observes advertisements and inquiry responses.

---

## License

This project is provided for defensive security and personal privacy protection purposes.
