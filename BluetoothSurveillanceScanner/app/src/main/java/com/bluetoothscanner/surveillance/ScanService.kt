package com.bluetoothscanner.surveillance

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.bluetoothscanner.surveillance.model.BtDeviceInfo

class ScanService : Service() {

    companion object {
        const val ACTION_DEVICE_FOUND = "com.bluetoothscanner.DEVICE_FOUND"
        const val ACTION_SCAN_STATUS = "com.bluetoothscanner.SCAN_STATUS"
        const val EXTRA_DEVICE = "extra_device"
        const val EXTRA_STATUS = "extra_status"

        private const val NOTIF_CHANNEL_ID = "bt_scanner_channel"
        private const val NOTIF_CHANNEL_ALERT_ID = "bt_alert_channel"
        private const val NOTIF_ID_FOREGROUND = 1
        // Alert IDs start at 1000 to avoid collisions with NOTIF_ID_FOREGROUND
        private const val NOTIF_ID_ALERT_BASE = 1000

        // Throttle: suppress repeat broadcasts for the same device within this window
        private const val BROADCAST_THROTTLE_MS = 2_000L
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var leScanner: BluetoothLeScanner? = null
    private var isLeScanning = false
    private var isClassicScanning = false

    // Stable per-address notification IDs — avoids hashCode collisions and negative IDs
    private val alertNotifIds = mutableMapOf<String, Int>()
    private var nextAlertNotifId = NOTIF_ID_ALERT_BASE

    // Throttle: track last broadcast time per MAC address
    private val lastBroadcastTime = mutableMapOf<String, Long>()

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processDevice(result.device, result.rssi, "BLE")
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { processDevice(it.device, it.rssi, "BLE") }
        }

        override fun onScanFailed(errorCode: Int) {
            broadcastStatus("BLE scan failed (error $errorCode)")
        }
    }

    private val classicScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    device?.let { processDevice(it, rssi, "Classic") }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    startClassicScan()
                }
            }
        }
    }

    private var classicReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        leScanner = bluetoothAdapter?.bluetoothLeScanner
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID_FOREGROUND, buildForegroundNotification())
        startLeScanning()
        startClassicScan()
        broadcastStatus("Scanning started")
        return START_STICKY
    }

    override fun onDestroy() {
        stopLeScanning()
        stopClassicScan()
        if (classicReceiverRegistered) {
            try { unregisterReceiver(classicScanReceiver) } catch (_: Exception) {}
            classicReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLeScanning() {
        if (isLeScanning) return
        if (!hasBluetoothPermission()) return
        // Guard: BLE may not be available if adapter is off or hardware is missing
        val scanner = leScanner ?: run {
            broadcastStatus("BLE scanner unavailable")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, leScanCallback)
        isLeScanning = true
    }

    private fun stopLeScanning() {
        if (!isLeScanning) return
        if (!hasBluetoothPermission()) return
        leScanner?.stopScan(leScanCallback)
        isLeScanning = false
    }

    private fun startClassicScan() {
        if (!hasBluetoothPermission()) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Unregister first to avoid duplicate registrations across restart cycles
        if (classicReceiverRegistered) {
            try { unregisterReceiver(classicScanReceiver) } catch (_: Exception) {}
        }
        registerReceiver(classicScanReceiver, filter)
        classicReceiverRegistered = true
        bluetoothAdapter?.startDiscovery()
        isClassicScanning = true
    }

    private fun stopClassicScan() {
        if (!isClassicScanning) return
        if (!hasBluetoothPermission()) return
        bluetoothAdapter?.cancelDiscovery()
        isClassicScanning = false
    }

    private fun processDevice(device: BluetoothDevice, rssi: Int, type: String) {
        if (!hasBluetoothPermission()) return
        val info = SurveillanceDetector.analyze(device, rssi, type)
        broadcastDevice(info)
        if (info.threatLevel == BtDeviceInfo.ThreatLevel.HIGH) {
            sendHighRiskAlert(info)
        }
    }

    private fun broadcastDevice(info: BtDeviceInfo) {
        // Throttle: skip if we broadcast this device recently to avoid UI thrashing
        val now = System.currentTimeMillis()
        val lastSent = lastBroadcastTime[info.address] ?: 0L
        if (now - lastSent < BROADCAST_THROTTLE_MS) return
        lastBroadcastTime[info.address] = now

        // setPackage restricts delivery to this app only — prevents other apps
        // from snooping device data via the implicit broadcast
        val intent = Intent(ACTION_DEVICE_FOUND).apply {
            setPackage(packageName)
            putExtra(EXTRA_DEVICE, info.address)
            putExtra("name", info.displayName)
            putExtra("rssi", info.rssi)
            putExtra("type", info.deviceType)
            putExtra("threat", info.threatLevel.name)
            putExtra("reason", info.threatReason)
            putExtra("manufacturer", info.manufacturer ?: "")
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_SCAN_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
        })
    }

    private fun sendHighRiskAlert(info: BtDeviceInfo) {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val launchIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ALERT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Surveillance Device Detected!")
            .setContentText("${info.displayName} — ${info.threatReason}")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Device: ${info.displayName}\nMAC: ${info.address}\n${info.threatReason}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setContentIntent(launchIntent)
            .setAutoCancel(true)
            .build()
        // Use a stable per-address ID to avoid hashCode collisions and negative IDs
        val notifId = alertNotifIds.getOrPut(info.address) { nextAlertNotifId++ }
        notifManager.notify(notifId, notification)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val scanChannel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "BT Scanner",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Background Bluetooth scanning status" }

        val alertChannel = NotificationChannel(
            NOTIF_CHANNEL_ALERT_ID,
            "Surveillance Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when surveillance devices are detected"
            enableVibration(true)
        }

        manager.createNotificationChannel(scanChannel)
        manager.createNotificationChannel(alertChannel)
    }

    private fun buildForegroundNotification() = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setContentTitle("BT Surveillance Scanner")
        .setContentText("Scanning for surveillance devices…")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    // Check the correct permission for the running API level:
    // BLUETOOTH_SCAN was introduced in API 31 (Android 12); older devices use BLUETOOTH.
    private fun hasBluetoothPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ActivityCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
