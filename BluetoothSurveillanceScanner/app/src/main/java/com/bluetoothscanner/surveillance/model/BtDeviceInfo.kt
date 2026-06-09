package com.bluetoothscanner.surveillance.model

data class BtDeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val deviceType: String,        // BLE, Classic, or Dual
    val threatLevel: ThreatLevel,
    val threatReason: String,
    val manufacturer: String?,
    val firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var seenCount: Int = 1
) {
    enum class ThreatLevel(val label: String, val colorRes: Int) {
        HIGH("HIGH RISK", android.R.color.holo_red_light),
        MEDIUM("SUSPICIOUS", android.R.color.holo_orange_light),
        LOW("LOW RISK", android.R.color.holo_orange_dark),
        UNKNOWN("UNKNOWN", android.R.color.darker_gray)
    }

    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: "Unknown Device"

    val signalBars: Int
        get() = when {
            rssi >= -50 -> 4
            rssi >= -65 -> 3
            rssi >= -75 -> 2
            rssi >= -85 -> 1
            else -> 0
        }

    val signalDescription: String
        get() = when {
            rssi >= -50 -> "Very Strong (nearby)"
            rssi >= -65 -> "Strong"
            rssi >= -75 -> "Medium"
            rssi >= -85 -> "Weak"
            else -> "Very Weak"
        }
}
