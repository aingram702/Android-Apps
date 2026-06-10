# Bluetooth Surveillance Scanner

An Android app that continuously scans for Bluetooth-based surveillance devices and spy gadgets in your environment. It runs as a persistent foreground service, identifies known tracking hardware by device name and MAC address manufacturer prefix, and alerts you in real time when a high-risk device is detected nearby.

**📖 Documentation**

| Document | Contents |
|---|---|
| [User Manual](MANUAL.md) | Installation, permissions walkthrough, using the app, locating hidden devices, troubleshooting, FAQ |
| [Audit & Fix History](AUDIT_HISTORY.md) | Full record of all six code review / security audit passes (28 findings) |

---

## Features

- **Dual-mode scanning** — simultaneously runs BLE (Bluetooth Low Energy) and Classic Bluetooth discovery for maximum coverage
- **Continuous background operation** — foreground service keeps scanning with the screen off; survives screen rotation and app switching with full state restoration
- **Self-healing scan loop** — automatically pauses and resumes if Bluetooth is toggled off and back on mid-scan
- **Threat classification** — every detected device is rated HIGH RISK, SUSPICIOUS, LOW RISK, or UNKNOWN; classifications are sticky and never silently downgrade during a session
- **Instant alerts** — a vibrating push notification fires the moment a high-risk device is found (once per device — no repeat-buzzing while a tracker stays in range)
- **Signal strength analysis** — RSSI reading with proximity description (Very Strong / Strong / Medium / Weak) and a live signal bar
- **Seen counter** — tracks how many times each device has been detected to surface persistent hidden devices
- **Device detail view** — tap any entry to see full details: name, MAC address, manufacturer, type, signal, and the specific reason it was flagged
- **Hardened CSV export** — share a full scan report via any Android share target, with RFC 4180 escaping and spreadsheet formula-injection protection
- **Clear / reset** — wipe the device list and session memory at any time without stopping the scan
- **Zero data egress** — no `INTERNET` permission, no storage, no analytics; results live in memory only

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

Threat levels are **sticky-maximum** within a session: once a device is classified HIGH (e.g. by a name it only advertises intermittently), later sightings cannot downgrade it — only escalate it. See the [User Manual](MANUAL.md#6-reading-the-results) for interpretation guidance.

---

## Requirements

- Android **6.0 (API 23)** or higher
- Bluetooth hardware (BLE support required)
- Permissions granted at runtime:
  - `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (Android 12+)
  - `BLUETOOTH` + `BLUETOOTH_ADMIN` (Android 6–11)
  - `ACCESS_FINE_LOCATION` (required by Android for BLE scan results on API 23–30, and kept on 12+ because the app does not assert `neverForLocation` — see [BUG-11](AUDIT_HISTORY.md#bug-11--neverforlocation-made-android-hide-the-very-beacons-the-app-hunts))
  - `POST_NOTIFICATIONS` (Android 13+, required to show the scan status and HIGH RISK alert notifications)

See the [User Manual](MANUAL.md#3-first-launch--permissions) for a permission-by-permission explanation of what each one is used for.

---

## Building

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 8+
- Gradle 8.2 (the Gradle wrapper is committed — no local Gradle install needed)

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
├── README.md                            # This file — project overview
├── MANUAL.md                            # User manual
├── AUDIT_HISTORY.md                     # Full code review & security audit record
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/bluetoothscanner/surveillance/
│       │   ├── MainActivity.kt          # UI, permissions, state restoration, export
│       │   ├── ScanService.kt           # Foreground service, BLE + Classic scan loops,
│       │   │                            #   alerts, session device snapshot
│       │   ├── SurveillanceDetector.kt  # Detection engine (names, OUIs, heuristics)
│       │   ├── DeviceAdapter.kt         # RecyclerView adapter with merge-aware upsert
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
├── gradle.properties
├── gradlew / gradlew.bat                # Committed Gradle 8.2 wrapper
└── gradle/wrapper/
```

---

## How It Works

1. **Service start** — tapping "Start Scan" launches `ScanService` as a foreground service with a persistent notification, keeping it alive while the screen is off.

2. **BLE scan** — uses `BluetoothLeScanner` in `SCAN_MODE_LOW_LATENCY` with a `ScanCallback`. Results stream continuously as devices advertise.

3. **Classic scan** — calls `BluetoothAdapter.startDiscovery()`. On `ACTION_DISCOVERY_FINISHED` the scan is immediately restarted, creating a continuous loop. A separate receiver watches `ACTION_STATE_CHANGED` so both scan loops pause when Bluetooth turns off and resume automatically when it returns.

4. **Analysis** — each sighting is passed to `SurveillanceDetector.analyze()`, which checks the device name against pattern lists and the MAC OUI against a manufacturer table, then assigns a `ThreatLevel`.

5. **Throttle & snapshot** — per-device broadcasts are rate-limited to one per 2 seconds. Each broadcast also updates the service's session snapshot (`knownDevices`), merging in late-arriving names/manufacturers and keeping the most severe classification seen so far.

6. **Broadcast → UI** — `ScanService` sends a package-restricted broadcast; `MainActivity`'s non-exported receiver feeds `DeviceAdapter.upsert()`, which inserts or merges in place and re-sorts only when ordering can change (new device, or threat escalation). If the activity is recreated (rotation, backgrounding), it restores the full list and scan state from the service.

7. **Alert** — the first time a device is classified HIGH, `ScanService` fires a high-priority vibrating notification (one per device; later sightings update it silently).

---

## Audit & Fix History

The codebase has been through six review/audit passes — 28 findings, all fixed. Full analysis of every finding is in [AUDIT_HISTORY.md](AUDIT_HISTORY.md).

| Pass | Focus | Findings (all fixed) |
|------|-------|----------------------|
| 1 — Initial security audit | Broadcast privacy, crash hardening, performance | SEC-1…SEC-5, BUG-1…BUG-4, PERF-1, PERF-2, CLEANUP-1, CLEANUP-2 |
| 2 — Second review | First-run crash, badge rendering, concurrency | CRASH-4, UI-1, CONC-1 |
| 3 — Third review | Android 13 notifications, build tooling | CRASH-5, BUILD-1 |
| 4 — Fourth review | Service/UI state sync, Bluetooth-toggle recovery | BUG-7, BUG-8 |
| 5 — Fifth review | Alert vibration storm, list loss on rotation | BUG-9, BUG-10 |
| 6 — Security audit | CSV formula injection, beacon filtering, threat-level regressions | SEC-6, BUG-11…BUG-13 |

Highlights:
- **No data leaves the device** — broadcasts are package-private, receivers non-exported, backup disabled, and there is no network code (SEC-1, SEC-2, SEC-4).
- **Export is injection-hardened** — RFC 4180 escaping plus spreadsheet formula neutralization, since device names are attacker-controlled (BUG-4, SEC-6).
- **Detection integrity** — the OS-level beacon filtering pitfall (`neverForLocation`) is avoided, and classifications can't silently downgrade (BUG-11, BUG-13).

---

## Limitations & Notes

- **BLE-only devices** (like AirTags) are only visible during their advertisement windows. Apple's AirTag rotates its MAC address periodically to prevent tracking — this app will show it as a new device each rotation cycle.
- **Classic Bluetooth** discovery takes approximately 12 seconds per cycle and requires the remote device to be in discoverable mode. Many spy devices only use BLE.
- **MAC OUI matching** is heuristic — the same chip vendor supplies components for both legitimate consumer devices and spy hardware. MEDIUM-risk flags for OUI matches should be investigated, not treated as confirmed.
- **`isScanning` UI flag** — if the OS kills the foreground service due to memory pressure (rather than the activity being recreated), the FAB may still show "Stop Scan" while no scan is actually running, since `ScanService.isRunning` would also be reset to `false` only if `onDestroy()` runs. Tapping "Stop Scan" then "Start Scan" will recover the state.
- **Location permission** is not used for location purposes — Android mandates it for Bluetooth scan results on API 23–30, and on 12+ it remains required because the app deliberately does not assert `neverForLocation` (doing so would filter beacon advertisements out of scan results — see [BUG-11](AUDIT_HISTORY.md#bug-11--neverforlocation-made-android-hide-the-very-beacons-the-app-hunts)).
- The app does **not** connect to any detected device, only passively observes advertisements and inquiry responses.

---

## License

This project is provided for defensive security and personal privacy protection purposes.
