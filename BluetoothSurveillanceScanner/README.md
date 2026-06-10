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

5. **Broadcast** — `ScanService` sends a package-restricted local broadcast with the device data. `MainActivity` receives it and calls `DeviceAdapter.upsert()`, which either adds a new row or updates an existing one in place, then re-sorts by threat level only when a new device arrives.

6. **Alert** — if the threat level is HIGH, `ScanService` also fires a high-priority system notification with vibration.

---

## Security Audit & Remediations

A full code review and security audit was performed after the initial release. The following issues were identified and fixed:

### SEC-1 — Implicit broadcasts exposed device data to all apps
**Severity:** High  
**File:** `ScanService.kt`

**Issue:** `sendBroadcast(intent)` without a target package sent Bluetooth device data (MAC addresses, threat levels, signal strength) as a system-wide implicit broadcast. Any app on the device could register a receiver for `com.bluetoothscanner.DEVICE_FOUND` and silently collect the scan results.

**Fix:** `intent.setPackage(packageName)` is now called on every outgoing broadcast, restricting delivery to this app only.

---

### SEC-2 — BroadcastReceiver accepted spoofed intents from other apps
**Severity:** High  
**File:** `MainActivity.kt`

**Issue:** `registerReceiver(scanReceiver, filter)` without export restrictions allowed any third-party app to craft a fake `ACTION_DEVICE_FOUND` intent and inject arbitrary device data into the UI, or trigger a denial-of-service via malformed extras.

**Fix:** `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)` is now used, which sets `Context.RECEIVER_NOT_EXPORTED` on API 33+ and falls back safely on older versions. Combined with SEC-1's `setPackage()`, the broadcast channel is now fully private.

---

### SEC-3 — `ThreatLevel.valueOf()` crash via malformed intent extra
**Severity:** High  
**File:** `MainActivity.kt` line 179

**Issue:** `BtDeviceInfo.ThreatLevel.valueOf(string)` throws `IllegalArgumentException` if the string doesn't match an enum constant. A spoofed or corrupted intent with `threat="INVALID"` would crash `MainActivity`.

**Fix:** The call is now wrapped in a `try/catch` block that falls back to `ThreatLevel.UNKNOWN` on any parse failure.

---

### SEC-4 — `android:allowBackup="true"` exposed scan history via ADB
**Severity:** Medium  
**File:** `AndroidManifest.xml`

**Issue:** With backup enabled, an attacker with USB debugging access (or physical device access) could run `adb backup` to extract any data the app writes to internal storage, including future additions like scan history persistence.

**Fix:** `android:allowBackup="false"` is now set in the manifest.

---

### SEC-5 — Notification ID collisions via `hashCode()`
**Severity:** Medium  
**File:** `ScanService.kt` line 183

**Issue:** `NOTIF_ID_ALERT + info.address.hashCode()` produced notification IDs that could be negative (causing unpredictable behaviour on some OEMs) and could collide with the foreground notification ID (`1`) or with other alert notifications, silently replacing a HIGH-risk alert with a different device's alert.

**Fix:** A `alertNotifIds: MutableMap<String, Int>` now assigns stable, sequential integer IDs starting from `1000`, one per unique MAC address.

---

### BUG-1 — `hasBluetoothPermission()` always returned `false` on Android ≤ 11
**Severity:** High  
**File:** `ScanService.kt` line 216

**Issue:** The method checked for `BLUETOOTH_SCAN` unconditionally. On API ≤ 30, that permission does not exist, so `checkSelfPermission` always returned `PERMISSION_DENIED`, silently preventing all Bluetooth operations on pre-Android-12 devices — the app appeared to scan but never actually did.

**Fix:** The check now branches on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`: API 31+ checks `BLUETOOTH_SCAN`; API 23–30 checks `BLUETOOTH`.

---

### BUG-2 — `isLeScanning` flag corrupted when BLE scanner is unavailable
**Severity:** Medium  
**File:** `ScanService.kt` line 106

**Issue:** `leScanner?.startScan(...)` used a null-safe call that silently did nothing if `leScanner` was null (BLE unavailable or adapter off), but `isLeScanning = true` was set unconditionally on the next line. This locked the service into a state where it believed BLE scanning was active but no callback would ever fire, and `startLeScanning()` could never be retried.

**Fix:** The scanner is now unwrapped with `?: return` before the scan starts; `isLeScanning = true` is only reached when the scan actually starts.

---

### BUG-3 — Classic scan receiver double-registration on discovery restart
**Severity:** Medium  
**File:** `ScanService.kt` line 117

**Issue:** `startClassicScan()` tried to `unregisterReceiver` before re-registering using a bare `try/catch` with no tracking of whether the receiver was actually registered. This could produce duplicate receiver registrations in certain timing windows and a silent memory leak.

**Fix:** A `classicReceiverRegistered: Boolean` flag now guards all register/unregister calls, guaranteeing exactly one registration at a time.

---

### BUG-4 — CSV injection in exported results
**Severity:** Low  
**File:** `MainActivity.kt` line 236

**Issue:** Device names, threat reasons, and manufacturer strings were interpolated directly into CSV fields without escaping. A device with a name containing `"` (e.g., `He said "hello"`) would produce malformed CSV that broke field boundaries and could cause incorrect parsing in spreadsheet software.

**Fix:** An `escapeCsv()` extension is applied to all string fields, replacing `"` with `""` per RFC 4180.

---

### PERF-1 — O(n log n) full sort on every BLE advertisement
**Severity:** Medium  
**File:** `MainActivity.kt` / `DeviceAdapter.kt`

**Issue:** `sortByThreat()` (which calls `notifyDataSetChanged()`) was invoked after every single call to `adapter.upsert()`, including updates to already-known devices. With 50+ devices in view and BLE callbacks firing at ~10 Hz, this caused constant full RecyclerView rebinds and visible jank.

**Fix:** `upsert()` now returns `Boolean` (true = new device inserted). `sortByThreat()` is only called when a new device is first seen; updating an existing device uses `notifyItemChanged()` in-place without a re-sort.

---

### PERF-2 — No deduplication of high-frequency BLE advertisements
**Severity:** Medium  
**File:** `ScanService.kt`

**Issue:** The same BLE device can advertise many times per second. Each advertisement triggered a broadcast → UI update cycle with no throttling, wasting CPU and causing UI thrashing.

**Fix:** A `lastBroadcastTime: MutableMap<String, Long>` throttle in `broadcastDevice()` suppresses repeat broadcasts for the same MAC address within a 2-second window.

---

### CLEANUP-1 — OUI strings duplicated between sets and `resolveManufacturer()`
**Severity:** Low  
**File:** `SurveillanceDetector.kt`

**Issue:** All OUI strings were listed twice: once in `HIGH_RISK_OUI`/`MEDIUM_RISK_OUI` sets and again in the `resolveManufacturer()` when-expression. Adding or correcting an OUI required updating two separate locations.

**Fix:** `OUI_TO_MANUFACTURER: Map<String, String>` is the single source of truth. `resolveManufacturer()` is a one-line map lookup. `HIGH_RISK_OUI` and `MEDIUM_RISK_OUI` remain as explicit sets for fast classification.

---

### CLEANUP-2 — `colorRes` in data model violated separation of concerns
**Severity:** Low  
**File:** `BtDeviceInfo.kt`

**Issue:** `ThreatLevel` enum carried a `colorRes: Int` field referencing `android.R.color.*` resources. The data model should not know about Android UI resources; the field was also unused (the adapter used hardcoded hex colors in `bind()`).

**Fix:** `colorRes` removed from the enum. Color mapping lives exclusively in `DeviceAdapter.bind()`.

---

## Second Review Pass — Additional Fixes

A follow-up code review (after the initial crash-fix round) found and fixed the following issues:

### CRASH-4 — `SecurityException` on first launch with Bluetooth off (Android 12+)
**Severity:** Critical  
**File:** `MainActivity.kt`

**Issue:** `checkPermissionsAndScan()` checked `btAdapter.isEnabled` and launched `ACTION_REQUEST_ENABLE` *before* requesting runtime permissions. On API 31+, starting the Bluetooth-enable system dialog requires `BLUETOOTH_CONNECT`. On a fresh install with Bluetooth off — the most common first-run state — tapping "Start Scan" threw a `SecurityException` and crashed the app immediately, before the permission prompt was ever shown.

**Fix:** `checkPermissionsAndScan()` now requests any missing runtime permissions first and returns; only once all permissions are granted does it check whether Bluetooth is enabled and prompt to enable it. The permission-result callback re-invokes `checkPermissionsAndScan()` (rather than calling `startScanning()` directly) so the Bluetooth-enabled check still runs afterward.

---

### UI-1 — High-risk threat badge text invisible (and badge color misleading for all levels)
**Severity:** Medium  
**File:** `DeviceAdapter.kt` / `bg_badge.xml`

**Issue:** `tv_threat_level`'s background (`bg_badge.xml`) is a solid `#C62828` (red) shape, but `bind()` only changed the badge's *text* color per threat level — it never changed the background. For a HIGH RISK device, the text color was also `#C62828`, making the "HIGH RISK" label completely invisible against its own background. For MEDIUM/LOW/UNKNOWN devices, the badge still rendered with the same red "high risk" background regardless of actual threat level, which was visually misleading.

**Fix:** `bind()` now applies `tvThreat.backgroundTintList` using the same per-threat-level color used for the signal bar, and sets the badge text to white for contrast against all four threat colors.

---

### CONC-1 — Non-atomic `getOrPut` on `ConcurrentHashMap` could assign duplicate alert IDs
**Severity:** Low  
**File:** `ScanService.kt`

**Issue:** `sendHighRiskAlert()` used `alertNotifIds.getOrPut(info.address) { ... }`. Kotlin's `getOrPut` extension is a plain get-then-put and is **not atomic**, even on a `ConcurrentHashMap`. If the same high-risk device was detected by the BLE and Classic scan callbacks at nearly the same time (on different threads), both could miss the cached ID, each allocate a new sequential ID via `nextAlertNotifId.getAndIncrement()`, and overwrite each other's map entry — resulting in two different notification IDs (and two separate alert notifications) for the same device.

**Fix:** Replaced with `alertNotifIds.computeIfAbsent(info.address) { ... }`, which is guaranteed atomic on `ConcurrentHashMap`.

---

## Limitations & Notes

- **BLE-only devices** (like AirTags) are only visible during their advertisement windows. Apple's AirTag rotates its MAC address periodically to prevent tracking — this app will show it as a new device each rotation cycle.
- **Classic Bluetooth** discovery takes approximately 12 seconds per cycle and requires the remote device to be in discoverable mode. Many spy devices only use BLE.
- **MAC OUI matching** is heuristic — the same chip vendor supplies components for both legitimate consumer devices and spy hardware. MEDIUM-risk flags for OUI matches should be investigated, not treated as confirmed.
- **`isScanning` UI flag** — if the OS kills the foreground service due to memory pressure, the FAB may show "Stop Scan" while no scan is actually running. Tapping "Stop Scan" then "Start Scan" will recover the state.
- **Location permission** is not used for location purposes — Android mandates it for any app that performs Bluetooth scanning on API 23–30.
- The app does **not** connect to any detected device, only passively observes advertisements and inquiry responses.

---

## License

This project is provided for defensive security and personal privacy protection purposes.
