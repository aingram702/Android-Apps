# Bluetooth Surveillance Scanner — User Manual

**Version 1.0**

This manual covers installation, setup, day-to-day use, interpreting results, and troubleshooting. For a technical overview of the project, see the [README](README.md). For the full record of code reviews and security audits, see the [Audit & Fix History](AUDIT_HISTORY.md).

---

## Contents

1. [What This App Does (and Doesn't Do)](#1-what-this-app-does-and-doesnt-do)
2. [Installation](#2-installation)
3. [First Launch & Permissions](#3-first-launch--permissions)
4. [The Main Screen](#4-the-main-screen)
5. [Starting and Stopping a Scan](#5-starting-and-stopping-a-scan)
6. [Reading the Results](#6-reading-the-results)
7. [Device Details](#7-device-details)
8. [High-Risk Alerts](#8-high-risk-alerts)
9. [Physically Locating a Suspect Device](#9-physically-locating-a-suspect-device)
10. [Exporting Results](#10-exporting-results)
11. [Clearing the List](#11-clearing-the-list)
12. [Behavior You Should Know About](#12-behavior-you-should-know-about)
13. [Troubleshooting](#13-troubleshooting)
14. [Frequently Asked Questions](#14-frequently-asked-questions)
15. [Privacy & Data Handling](#15-privacy--data-handling)
16. [Glossary](#16-glossary)

---

## 1. What This App Does (and Doesn't Do)

Bluetooth Surveillance Scanner continuously monitors the Bluetooth radio environment around your phone and flags devices that match known tracking or surveillance hardware: commercial trackers (AirTags, Tiles, Samsung SmartTags, Chipolo, TrackR), Bluetooth-enabled hidden cameras and spy earpieces, and DIY tracking hardware built on common hobbyist chips (ESP32, Telink, Nordic).

**It does:**
- Scan both BLE (Bluetooth Low Energy) and Classic Bluetooth simultaneously
- Keep scanning in the background with the screen off, via a foreground service
- Classify every detected device into one of four threat levels
- Fire a vibrating notification the moment a high-risk device is detected
- Let you export the full result set as CSV

**It does not:**
- Connect to, pair with, or interrogate any detected device — it only listens passively
- Tell you a device's physical location (Bluetooth doesn't carry position data; see [Section 9](#9-physically-locating-a-suspect-device) for how to home in on a device using signal strength)
- Prove that a flagged device is hostile — detection is heuristic. A HIGH RISK result means "this is a known tracker product or matches a known spy-device signature," not "someone is tracking you." Your own AirTag, your neighbor's Tile, and a legitimate fitness beacon will all be flagged.
- Detect devices that aren't transmitting. A tracker that is out of battery, in a shielded container, or transmitting only on Ultra-Wideband or cellular is invisible to Bluetooth scanning.

---

## 2. Installation

### Requirements

- Android **6.0 (API 23)** or newer
- Bluetooth hardware with BLE support (declared as required; the Play-style install will refuse devices without it)
- Roughly 10 MB of storage

### Building from source

```bash
git clone https://github.com/aingram702/android-apps.git
cd android-apps/BluetoothSurveillanceScanner

# Build a debug APK
./gradlew assembleDebug

# Or build and install straight to a USB-connected device with debugging enabled
./gradlew installDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Sideloading the APK

1. Copy `app-debug.apk` to the phone (USB, cloud drive, `adb push`).
2. Open the file on the phone. If prompted, allow your file manager to install unknown apps (Settings → Apps → Special app access → Install unknown apps).
3. Tap **Install**.

---

## 3. First Launch & Permissions

Android requires several runtime permissions before an app may scan Bluetooth. The app requests all of them in a single batch the first time you tap **Start Scan** — nothing is requested at app launch.

What you'll be asked for depends on your Android version:

| Android version | Permissions requested | Why |
|---|---|---|
| 13+ (API 33+) | Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`), Location (`ACCESS_FINE_LOCATION`), Notifications (`POST_NOTIFICATIONS`) | Scanning, reading device names, unfiltered scan results, and showing the scan status + alerts |
| 12 (API 31–32) | Nearby devices, Location | Scanning, reading device names, unfiltered scan results |
| 6–11 (API 23–30) | Location (`ACCESS_FINE_LOCATION`) | Android mandates location permission for *any* Bluetooth scanning on these versions |

**Grant everything.** Each permission is load-bearing:

- **Nearby devices** — without it, no scanning at all on Android 12+.
- **Location** — the app never reads your GPS position, but Android gates Bluetooth scan results behind this permission. On Android 12+ the app deliberately does *not* use the `neverForLocation` shortcut, because that mode causes the OS to filter beacon-style advertisements out of scan results — which would hide exactly the trackers this app looks for.
- **Notifications** (Android 13+) — without it, the scan-status notification and, critically, the HIGH RISK alerts are silently suppressed.

If Bluetooth is off when you tap **Start Scan**, the app asks for permissions first, then shows the system "Allow this app to turn on Bluetooth?" dialog. Accept it and scanning begins.

If you denied a permission and the prompt no longer appears, enable it manually: **Settings → Apps → BT Surveillance Scanner → Permissions**.

---

## 4. The Main Screen

From top to bottom:

| Element | What it shows / does |
|---|---|
| **Toolbar** | App title plus the overflow menu: **Export Results** (share icon) and **About** |
| **Summary bar** | Live counters: `Devices: N \| High Risk: N \| Suspicious: N`, and the **Clear** button |
| **Status line** (blue text) | What the scanner is doing right now: "Scanning started", "Bluetooth turned off — scanning paused", "BLE scan failed (error N)", etc. |
| **Device list** | One card per unique device (deduplicated by MAC address), sorted most-dangerous first, then by signal strength |
| **Start/Stop Scan button** (bottom-right) | Starts or stops the scanning service |

---

## 5. Starting and Stopping a Scan

**To start:** tap **Start Scan**. After permissions are granted (first run only) and Bluetooth is on, a persistent notification appears ("BT Surveillance Scanner — Scanning for surveillance devices…") and devices begin appearing within seconds.

While scanning:

- **BLE scanning is continuous** — advertising devices appear and refresh in near-real-time (UI updates are throttled to once per 2 seconds per device).
- **Classic Bluetooth discovery loops** — each discovery cycle takes ~12 seconds, then immediately restarts.
- The scan continues with the screen off and while you use other apps. The persistent notification is your indicator that it's running.

**To stop:** return to the app and tap **Stop Scan**, or swipe away nothing — the foreground notification cannot be dismissed while the service runs; stopping must be done from the app.

The device list survives a stop — results stay on screen until you tap **Clear** or the app process ends.

---

## 6. Reading the Results

Each card shows, top to bottom: device name, threat badge, MAC address, connection type (BLE/Classic), manufacturer (when the MAC prefix is recognized), the *reason* the device was flagged, a signal bar, the RSSI in dBm with a plain-language proximity estimate, and how many times the device has been seen this session.

### Threat levels

| Badge | Level | Meaning | What to do |
|---|---|---|---|
| 🔴 **HIGH RISK** | Red | Matches a known tracker product or spy-device signature by name or manufacturer MAC prefix | Identify it. Is it yours? A family member's? If unexplained and persistent, see Section 9 |
| 🟠 **SUSPICIOUS** | Orange | Name or MAC prefix *suggests* tracking/surveillance capability (e.g. contains "cam", "tracker", or a hobbyist-chip OUI) | Worth a look, but expect false positives — many legitimate gadgets match |
| 🟡 **LOW RISK** | Amber | Behavioral flag: unnamed device with a strong (≥ −65 dBm, i.e. nearby) signal | Only interesting if it follows you across locations |
| ⚪ **UNKNOWN** | Grey | No surveillance signature — listed for completeness | Almost always headphones, watches, TVs, cars, etc. |

A device's threat level **never downgrades** during a session. If a device was once classified HIGH (e.g. by a name it only advertises occasionally), it stays HIGH even when later advertisements omit the name. It can still *upgrade* if stronger evidence arrives.

### Signal strength ↔ distance

RSSI is a rough proximity proxy, not a ruler. As a rule of thumb:

| RSSI | Description | Typical distance |
|---|---|---|
| ≥ −50 dBm | Very Strong | Same room, within ~2 m |
| −50 to −65 dBm | Strong | Same room |
| −65 to −75 dBm | Medium | Adjacent room / ~5–10 m |
| −75 to −85 dBm | Weak | ~10–20 m, through walls |
| < −85 dBm | Very Weak | Far, or heavily obstructed |

### The "Seen" counter

`Seen N×` counts how many (throttled) sightings the device has had this session. A device with a high seen-count over a long period is *persistently present* — far more interesting than something that appeared once and vanished (a passing car, a phone in the elevator).

---

## 7. Device Details

Tap any card to open the full detail dialog: complete name, MAC address, type, signal strength with description, manufacturer, threat level, the exact rule that flagged it, and the sighting count.

The MAC address is the device's identity for the session. Note it down if you intend to track a suspect device over time — but be aware that AirTags and other FindMy devices rotate their MAC periodically by design, so the same physical tracker can reappear under a new address (see [Limitations](README.md#limitations--notes)).

---

## 8. High-Risk Alerts

When a device is classified HIGH RISK, the app immediately posts a high-priority notification — vibrating, with the device name, MAC, and the reason — even while the app is in the background and the screen is off.

Behavior details:

- **One alert per device.** The first detection vibrates and shows heads-up; subsequent sightings of the same device update the existing notification silently. You will not be buzzed continuously while a tracker remains in range.
- Each distinct high-risk device gets its own notification, so two trackers produce two alerts.
- Tapping the notification opens the app.
- Alerts use the **"Surveillance Alerts"** notification channel (importance: high). You can adjust its sound/vibration in Settings → Apps → BT Surveillance Scanner → Notifications. The persistent scanning notification lives on the separate, low-importance **"BT Scanner"** channel so it stays quiet.

---

## 9. Physically Locating a Suspect Device

Bluetooth gives you signal strength, not direction. To find a hidden device, use the "hot and cold" method:

1. **Confirm persistence first.** Clear the list, scan for 10–15 minutes. Does the device reappear with a high seen-count? Move to a different location (a friend's house, not just another room) and scan again — a device that follows you is the real concern; a device that stays behind belongs to the place, which matters if the question is "is there a camera in this room?"
2. **Watch the RSSI on the device's card** and walk slowly around the area. The 2-second update throttle means you should pause a few seconds at each position.
3. **Move toward rising RSSI.** Values climbing toward −50 dBm and above mean you're within a couple of meters.
4. **Search the hot zone.** Common hiding spots for trackers: vehicle wheel wells and bumpers, under seats, bag linings, coat pockets, gifted items. For cameras: smoke detectors, clocks, mirrors, vents, chargers, anything with a sightline and power.
5. **Found a tracker you don't own?** AirTags can be physically disabled by removing the battery (twist the metal back). Consider photographing it in place first. If you believe you are being stalked, contact local law enforcement — the tracker's serial number can be traced to its owner by the manufacturer, and in many jurisdictions planting one is a crime. Preserve the evidence.

Tip: iPhones (Apple's Find My alerts) and Android's built-in unknown-tracker alerts complement this app — they detect *separation-mode* trackers over hours, while this app shows you *everything transmitting right now*.

---

## 10. Exporting Results

**Menu (⋮) → Export Results** builds a CSV of every device currently in the list and opens the standard Android share sheet — send it to email, Drive, a messaging app, or any installed target.

Columns:

```
Name, Address, Type, RSSI, ThreatLevel, Reason, Manufacturer, SeenCount
```

Notes:

- Text fields are quoted and escaped per RFC 4180, and any field beginning with `=`, `+`, `-`, or `@` is prefixed with an apostrophe. This is deliberate: Bluetooth device names are controlled by whoever configured the device, and a malicious name could otherwise execute as a spreadsheet formula when you open the export in Excel or Google Sheets. If you see a stray leading `'` in a cell, that's the protection working.
- The export is a snapshot of the current list. Export *before* tapping Clear.
- The CSV contains MAC addresses observed around you — treat it as sensitive and share accordingly.

---

## 11. Clearing the List

The **Clear** button in the summary bar empties the device list, resets all counters, and clears the service's session memory. Scanning (if active) continues — devices still in range will reappear within seconds with fresh "Seen" counts.

Clearing is the right move when you change locations and want a clean baseline, or before a focused sweep of one room.

---

## 12. Behavior You Should Know About

- **Screen rotation / returning to the app:** the list, counters, status, and button state are fully restored from the running service. You lose nothing by rotating the phone or switching apps.
- **Bluetooth toggled off mid-scan:** scanning pauses and the status line says so. The moment Bluetooth comes back on, scanning resumes automatically — no need to touch the app.
- **Battery:** continuous low-latency BLE scanning plus looping Classic discovery is power-hungry by design (it's a sweep tool, not a 24/7 monitor). Expect noticeably elevated drain during long scans.
- **If Android kills the service** under extreme memory pressure, the service is marked sticky and the OS will attempt to restart it. In the rare case the UI still shows "Stop Scan" with no scan running, tap Stop then Start.
- **Duplicate-looking devices:** a MAC-rotating tracker (AirTag in particular) appears as a *new* device after each rotation. A growing list of unnamed Apple-OUI devices with similar RSSI is itself a hint that one physical FindMy device is nearby.

---

## 13. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Tapping Start Scan does nothing | A required permission was permanently denied | Settings → Apps → BT Surveillance Scanner → Permissions → allow Nearby devices, Location, Notifications |
| "Bluetooth, location, and notification permissions are required to scan" toast | You denied one of the runtime prompts | Tap Start Scan again and grant all, or grant manually in Settings |
| No devices ever appear | Bluetooth off; or Location Services (the system-wide toggle) off on Android 6–11 | Enable Bluetooth; enable Location in quick settings |
| Status shows "BLE scan failed (error 2)" | The Bluetooth stack errored (`SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`) | Toggle Bluetooth off and on — the app resumes automatically; reboot the phone if it persists |
| No HIGH RISK notification appeared (Android 13+) | Notification permission denied, or the Surveillance Alerts channel was silenced | Re-enable in Settings → Apps → Notifications |
| Known tracker nearby but not flagged | It isn't currently advertising (paired-with-owner AirTags go quiet), or its OUI/name isn't in the signature lists | Scan again later; trackers in separation mode advertise regularly |
| Device shows wrong/no name | The device hasn't included its name in an advertisement yet | Wait — the name fills in automatically when it arrives; it will never be overwritten back to "Unknown" |
| Phone vibrates once, then the alert seems "stuck" | That's by design — one audible/vibrating alert per device per session; the notification updates silently afterwards | Dismiss the notification; it will re-post (silently) on the next sighting |
| Build fails with "SDK location not found" | `ANDROID_HOME`/`local.properties` not configured | Point `local.properties` `sdk.dir=` at your Android SDK |

---

## 14. Frequently Asked Questions

**Q: A HIGH RISK device appeared. Am I being tracked?**
Not necessarily. Every AirTag, Tile, and SmartTag in radio range is flagged — including yours, your family's, your coworkers', and ones attached to nearby keys, pets, and bicycles. Tracking is indicated by *persistence across locations*: the same device (or a stream of MAC-rotated equivalents) showing up at home, at work, and at the gym.

**Q: Why does the app need Location permission? Is it tracking me?**
No. The app contains no location code, no network code, and no analytics. Android simply gates Bluetooth scan results behind the Location permission, because scan data *could* theoretically be used to infer location. The README and audit log document this in detail.

**Q: Why don't I see my neighbor's phone/headphones in the list?**
You probably do — under UNKNOWN. Phones randomize their BLE addresses and most consumer audio gear advertises nothing suspicious, so they're listed grey at the bottom.

**Q: Can it detect wired cameras, Wi-Fi cameras, or GPS trackers?**
Only if they *also* transmit Bluetooth (many cheap spy cameras do, for their setup app). Pure Wi-Fi devices need a Wi-Fi scanner; pure GPS/cellular trackers need an RF detector.

**Q: Does it work in airplane mode?**
Yes, if you re-enable Bluetooth after enabling airplane mode — useful for scanning with zero outgoing radio noise from your own phone's other radios.

**Q: How current are the detection signatures?**
The name patterns and OUI table are compiled into the app (`SurveillanceDetector.kt`). They cover the major commercial trackers and common spy-device modules as of this release. Pull requests adding OUIs/names are the intended way to extend coverage.

---

## 15. Privacy & Data Handling

- **Nothing leaves your phone.** The app has no `INTERNET` permission — it cannot transmit anything, ever. The only data egress is the CSV export *you* explicitly share.
- **Nothing is stored.** Scan results live in memory only and disappear when the process ends. There is no database, no log file, and Android backup is disabled (`allowBackup="false"`).
- **Scan broadcasts are app-private.** Internal device-found events are package-restricted and the receiver is non-exported, so other apps can neither read your scan results nor inject fake ones.
- The app never connects to detected devices, so it leaves no trace observable by the device's owner.

---

## 16. Glossary

| Term | Meaning |
|---|---|
| **BLE** | Bluetooth Low Energy — the low-power radio mode used by trackers, beacons, and most IoT gear |
| **Classic Bluetooth** | The older, higher-bandwidth Bluetooth mode (headsets, car kits); discovered via ~12 s inquiry cycles |
| **Advertisement** | The small broadcast packet a BLE device transmits periodically to announce itself |
| **RSSI** | Received Signal Strength Indicator, in dBm; closer to 0 = stronger = nearer |
| **dBm** | Decibel-milliwatts; logarithmic power unit, always negative for received Bluetooth |
| **MAC address** | The 48-bit hardware identifier of a Bluetooth radio, shown as six hex pairs |
| **OUI** | Organizationally Unique Identifier — the first three bytes of a MAC, assigned to a manufacturer; the basis of manufacturer detection |
| **MAC rotation** | A privacy/anti-detection technique where a device periodically changes its advertised address (AirTags do this) |
| **Foreground service** | An Android service tied to a visible notification, allowed to run indefinitely in the background |
| **Separation mode** | Tracker state when away from its owner's phone — typically advertising more openly, which is when anti-stalking detectors can spot it |
