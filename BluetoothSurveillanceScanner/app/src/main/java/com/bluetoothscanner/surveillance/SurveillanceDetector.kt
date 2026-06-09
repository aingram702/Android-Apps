package com.bluetoothscanner.surveillance

import android.bluetooth.BluetoothDevice
import com.bluetoothscanner.surveillance.model.BtDeviceInfo

/**
 * Identifies known surveillance and tracking devices from Bluetooth scan results.
 * Uses device names, MAC OUI prefixes, and behavioral patterns.
 */
object SurveillanceDetector {

    // Known tracking device name patterns (case-insensitive)
    private val HIGH_RISK_NAME_PATTERNS = listOf(
        "airtag", "air tag",
        "tile slim", "tile mate", "tile pro", "tile sticker", "tile sport",
        "galaxy smarttag", "smarttag+", "galaxy tag",
        "chipolo", "chipolo one",
        "trackr", "track r",
        "nut mini", "nutmini", "nut3",
        "pebblebee", "pebblebee clip",
        "cube tracker", "cube shadow",
        "orbit", "orbit keys",
        "jiobit",
        "pixie", "pixie point",
        "esky", "gps tracker", "gpstracker",
        "spy cam", "spycam", "hidden cam", "hiddencam",
        "mini spy", "mini camera",
        "sq8", "sq11", "sq12", "sq13",   // common spy cam models
        "p1 cam", "p2 cam",
        "keychain cam",
        "smoke cam", "clock cam",
        "mirror cam",
        "ivel eyes",
    )

    private val MEDIUM_RISK_NAME_PATTERNS = listOf(
        "tile",
        "find my",
        "smarttag",
        "tracker",
        "gps",
        "finder",
        "locator",
        "beacon",
        "tag",
        "spy",
        "cam",
        "camera",
        "surveillance",
        "monitor",
        "watch dog",
        "watchdog",
        "ispy",
        "mini earphone",
        "spy earpiece",
        "nano earpiece",
        "wireless earpiece spy",
    )

    // Single source of truth: OUI → manufacturer name.
    // High-risk OUIs are those belonging to known tracker manufacturers;
    // medium-risk OUIs belong to cheap/DIY BLE module vendors.
    private val OUI_TO_MANUFACTURER = mapOf(
        // Apple (AirTag / FindMy)
        "AC:BC:32" to "Apple (AirTag / FindMy)",
        "A4:C3:F0" to "Apple (AirTag / FindMy)",
        "70:3C:69" to "Apple (AirTag / FindMy)",
        "F0:18:98" to "Apple (AirTag / FindMy)",
        "3C:22:FB" to "Apple (AirTag / FindMy)",
        "7C:9A:54" to "Apple (AirTag / FindMy)",
        "DC:9B:9C" to "Apple (AirTag / FindMy)",
        // Samsung SmartTag
        "3C:BD:D8" to "Samsung (SmartTag)",
        "CC:07:AB" to "Samsung (SmartTag)",
        "8C:79:F5" to "Samsung (SmartTag)",
        "00:07:AB" to "Samsung (SmartTag)",
        // Tile
        "E8:FE:6A" to "Tile Inc.",
        "D4:61:9D" to "Tile Inc.",
        "54:EF:44" to "Tile Inc.",
        // Chipolo
        "CC:4B:73" to "Chipolo",
        "F4:B7:8D" to "Chipolo",
        // TrackR
        "E0:28:6D" to "TrackR",
        // Telink Semiconductor — common in cheap BLE spy gadgets
        "A4:C1:38" to "Telink Semiconductor (BLE module)",
        // Nordic Semiconductor — used in DIY trackers
        "B4:99:4C" to "Nordic Semiconductor (BLE module)",
        // Generic BLE module
        "00:1A:7D" to "Generic BLE module",
        // Espressif (ESP32/ESP8266) — widely used in DIY spy devices
        "60:A4:4C" to "Espressif Systems (ESP32/ESP8266)",
        "24:6F:28" to "Espressif Systems (ESP32/ESP8266)",
        "A0:20:A6" to "Espressif Systems (ESP32/ESP8266)",
        "3C:71:BF" to "Espressif Systems (ESP32/ESP8266)",
        "24:0A:C4" to "Espressif Systems (ESP32/ESP8266)",
        "DC:54:75" to "Espressif Systems (ESP32/ESP8266)",
    )

    private val HIGH_RISK_OUI = setOf(
        "AC:BC:32", "A4:C3:F0", "70:3C:69", "F0:18:98",
        "3C:22:FB", "7C:9A:54", "DC:9B:9C",
        "3C:BD:D8", "CC:07:AB", "8C:79:F5", "00:07:AB",
        "E8:FE:6A", "D4:61:9D", "54:EF:44",
        "CC:4B:73", "F4:B7:8D",
        "E0:28:6D",
    )

    private val MEDIUM_RISK_OUI = setOf(
        "A4:C1:38", "B4:99:4C", "00:1A:7D",
        "60:A4:4C", "24:6F:28", "A0:20:A6",
        "3C:71:BF", "24:0A:C4", "DC:54:75",
    )

    private val SUSPICIOUS_BLANK_NAME_INDICATORS = listOf(
        "", " ", "n/a", "null", "unknown", "device", "ble device",
    )

    fun analyze(
        device: BluetoothDevice,
        rssi: Int,
        deviceTypeName: String
    ): BtDeviceInfo {
        // device.name requires BLUETOOTH_CONNECT on API 31+; catch SecurityException defensively
        val name = try { device.name?.trim() } catch (_: SecurityException) { null }
        val address = device.address?.uppercase() ?: "00:00:00:00:00:00"
        // Guard against malformed MACs shorter than 8 chars
        val oui = if (address.length >= 8) address.take(8) else ""

        val (threatLevel, reason) = classifyDevice(name, oui, rssi)
        val manufacturer = resolveManufacturer(oui)

        return BtDeviceInfo(
            address = address,
            name = name,
            rssi = rssi,
            deviceType = deviceTypeName,
            threatLevel = threatLevel,
            threatReason = reason,
            manufacturer = manufacturer
        )
    }

    private fun classifyDevice(
        name: String?,
        oui: String,
        rssi: Int
    ): Pair<BtDeviceInfo.ThreatLevel, String> {
        val lowerName = name?.lowercase() ?: ""

        for (pattern in HIGH_RISK_NAME_PATTERNS) {
            if (lowerName.contains(pattern)) {
                return Pair(
                    BtDeviceInfo.ThreatLevel.HIGH,
                    "Known tracking/surveillance device: matches '$pattern'"
                )
            }
        }

        if (oui.isNotEmpty() && oui in HIGH_RISK_OUI) {
            return Pair(
                BtDeviceInfo.ThreatLevel.HIGH,
                "MAC prefix matches known tracker manufacturer ($oui)"
            )
        }

        for (pattern in MEDIUM_RISK_NAME_PATTERNS) {
            if (lowerName.contains(pattern)) {
                return Pair(
                    BtDeviceInfo.ThreatLevel.MEDIUM,
                    "Device name suggests surveillance/tracking capability: '$pattern'"
                )
            }
        }

        if (oui.isNotEmpty() && oui in MEDIUM_RISK_OUI) {
            return Pair(
                BtDeviceInfo.ThreatLevel.MEDIUM,
                "MAC prefix matches DIY/cheap BLE module manufacturer often used in spy devices ($oui)"
            )
        }

        val isBlankName = name.isNullOrBlank() ||
            SUSPICIOUS_BLANK_NAME_INDICATORS.any { lowerName == it }
        if (isBlankName && rssi >= -65) {
            return Pair(
                BtDeviceInfo.ThreatLevel.LOW,
                "Unnamed device with strong signal — could be a hidden/disguised device"
            )
        }

        return Pair(
            BtDeviceInfo.ThreatLevel.UNKNOWN,
            "No known surveillance signatures detected"
        )
    }

    // Derived from OUI_TO_MANUFACTURER — single source of truth, no duplication.
    fun resolveManufacturer(oui: String): String? = OUI_TO_MANUFACTURER[oui.uppercase()]
}
