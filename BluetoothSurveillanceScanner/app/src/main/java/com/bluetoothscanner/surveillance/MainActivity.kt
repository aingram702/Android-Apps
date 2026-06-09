package com.bluetoothscanner.surveillance

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluetoothscanner.surveillance.databinding.ActivityMainBinding
import com.bluetoothscanner.surveillance.model.BtDeviceInfo

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DeviceAdapter
    private var isScanning = false

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startScanning()
        } else {
            Toast.makeText(this, "Bluetooth permissions required to scan", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) checkPermissionsAndScan()
        else Toast.makeText(this, "Bluetooth must be enabled to scan", Toast.LENGTH_SHORT).show()
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScanService.ACTION_DEVICE_FOUND -> {
                    val device = buildDeviceFromIntent(intent)
                    val isNew = adapter.upsert(device)
                    // Only re-sort when a device is first seen — updating an existing device
                    // doesn't change its threat level, so a full sort+rebind is unnecessary.
                    if (isNew) adapter.sortByThreat()
                    updateCounters()
                }
                ScanService.ACTION_SCAN_STATUS -> {
                    binding.tvStatus.text = intent.getStringExtra(ScanService.EXTRA_STATUS)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = DeviceAdapter { device -> showDeviceDetail(device) }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        binding.fabScan.setOnClickListener {
            if (isScanning) stopScanning() else checkPermissionsAndScan()
        }

        binding.btnClear.setOnClickListener {
            adapter.clear()
            updateCounters()
        }

        updateFabLabel()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ScanService.ACTION_DEVICE_FOUND)
            addAction(ScanService.ACTION_SCAN_STATUS)
        }
        // RECEIVER_NOT_EXPORTED ensures this receiver ignores intents from other apps,
        // preventing intent injection / spoofing of scan results.
        ContextCompat.registerReceiver(
            this, scanReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> { showAboutDialog(); true }
            R.id.action_export -> { exportResults(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkPermissionsAndScan() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScanning()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startScanning() {
        isScanning = true
        updateFabLabel()
        binding.tvStatus.text = "Starting scan…"
        ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
    }

    private fun stopScanning() {
        isScanning = false
        updateFabLabel()
        stopService(Intent(this, ScanService::class.java))
        binding.tvStatus.text = "Scan stopped"
    }

    private fun updateFabLabel() {
        binding.fabScan.text = if (isScanning) "Stop Scan" else "Start Scan"
    }

    private fun updateCounters() {
        val all = adapter.getAll()
        val highCount = all.count { it.threatLevel == BtDeviceInfo.ThreatLevel.HIGH }
        val medCount = all.count { it.threatLevel == BtDeviceInfo.ThreatLevel.MEDIUM }
        binding.tvSummary.text = "Devices: ${all.size}  |  High Risk: $highCount  |  Suspicious: $medCount"
    }

    private fun buildDeviceFromIntent(intent: Intent): BtDeviceInfo {
        // Safely parse the threat level — an unexpected value must not crash the app.
        val threatLevel = try {
            BtDeviceInfo.ThreatLevel.valueOf(intent.getStringExtra("threat") ?: "UNKNOWN")
        } catch (_: IllegalArgumentException) {
            BtDeviceInfo.ThreatLevel.UNKNOWN
        }
        return BtDeviceInfo(
            address = intent.getStringExtra(ScanService.EXTRA_DEVICE) ?: "",
            name = intent.getStringExtra("name"),
            rssi = intent.getIntExtra("rssi", -100),
            deviceType = intent.getStringExtra("type") ?: "Unknown",
            threatLevel = threatLevel,
            threatReason = intent.getStringExtra("reason") ?: "",
            manufacturer = intent.getStringExtra("manufacturer")?.takeIf { it.isNotBlank() }
        )
    }

    private fun showDeviceDetail(device: BtDeviceInfo) {
        val msg = buildString {
            appendLine("Name: ${device.displayName}")
            appendLine("MAC Address: ${device.address}")
            appendLine("Type: ${device.deviceType}")
            appendLine("Signal: ${device.rssi} dBm (${device.signalDescription})")
            appendLine("Manufacturer: ${device.manufacturer ?: "Unknown"}")
            appendLine()
            appendLine("Threat Level: ${device.threatLevel.label}")
            appendLine("Reason: ${device.threatReason}")
            appendLine()
            appendLine("Seen ${device.seenCount} time(s)")
        }
        AlertDialog.Builder(this)
            .setTitle("Device Details")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("BT Surveillance Scanner")
            .setMessage(
                "Scans for Bluetooth surveillance devices including:\n\n" +
                "• AirTags & Apple FindMy trackers\n" +
                "• Tile trackers\n" +
                "• Samsung SmartTags\n" +
                "• Chipolo & TrackR trackers\n" +
                "• Hidden BLE spy cameras\n" +
                "• Spy earpieces\n" +
                "• Unknown DIY tracking devices\n\n" +
                "Detection uses device names, manufacturer MAC prefixes, " +
                "and signal strength analysis.\n\n" +
                "Version 1.0"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun exportResults() {
        val all = adapter.getAll()
        if (all.isEmpty()) {
            Toast.makeText(this, "No devices to export", Toast.LENGTH_SHORT).show()
            return
        }
        val csv = buildString {
            appendLine("Name,Address,Type,RSSI,ThreatLevel,Reason,Manufacturer,SeenCount")
            all.forEach { d ->
                // Escape double-quotes per RFC 4180 to prevent CSV injection
                appendLine(
                    "\"${d.displayName.escapeCsv()}\",${d.address},${d.deviceType},${d.rssi}," +
                    "${d.threatLevel.label},\"${d.threatReason.escapeCsv()}\"," +
                    "\"${(d.manufacturer ?: "").escapeCsv()}\",${d.seenCount}"
                )
            }
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BT Surveillance Scan Results")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(shareIntent, "Export scan results"))
    }

    private fun String.escapeCsv() = replace("\"", "\"\"")
}
