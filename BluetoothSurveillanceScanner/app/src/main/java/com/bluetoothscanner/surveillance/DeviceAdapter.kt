package com.bluetoothscanner.surveillance

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.bluetoothscanner.surveillance.model.BtDeviceInfo

class DeviceAdapter(
    private val onItemClick: (BtDeviceInfo) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<BtDeviceInfo>()
    // address -> list position for O(1) update lookups
    private val indexMap = mutableMapOf<String, Int>()

    /**
     * Insert or update a device. Returns true when the list may need re-sorting:
     * a brand-new device, or an existing device whose threat level changed.
     */
    fun upsert(incoming: BtDeviceInfo): Boolean {
        val existing = indexMap[incoming.address]
        return if (existing != null) {
            val current = devices[existing]
            // A later advertisement may omit the name the HIGH/MEDIUM classification
            // was based on — never let the threat level silently downgrade.
            // (ThreatLevel ordinal 0 = HIGH is the most severe.)
            val best = if (incoming.threatLevel.ordinal <= current.threatLevel.ordinal) incoming else current
            devices[existing] = current.copy(
                name = incoming.name ?: current.name,
                deviceType = incoming.deviceType,
                manufacturer = incoming.manufacturer ?: current.manufacturer,
                rssi = incoming.rssi,
                lastSeen = incoming.lastSeen,
                seenCount = current.seenCount + 1,
                threatLevel = best.threatLevel,
                threatReason = best.threatReason
            )
            notifyItemChanged(existing)
            best.threatLevel != current.threatLevel
        } else {
            devices.add(incoming)
            indexMap[incoming.address] = devices.size - 1
            notifyItemInserted(devices.size - 1)
            true
        }
    }

    fun sortByThreat() {
        devices.sortWith(
            compareBy<BtDeviceInfo> { it.threatLevel.ordinal }
                .thenByDescending { it.rssi }
        )
        indexMap.clear()
        devices.forEachIndexed { i, d -> indexMap[d.address] = i }
        notifyDataSetChanged()
    }

    fun clear() {
        val size = devices.size
        devices.clear()
        indexMap.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun getAll(): List<BtDeviceInfo> = devices.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount() = devices.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: CardView = itemView.findViewById(R.id.card_device)
        private val tvName: TextView = itemView.findViewById(R.id.tv_device_name)
        private val tvAddress: TextView = itemView.findViewById(R.id.tv_device_address)
        private val tvThreat: TextView = itemView.findViewById(R.id.tv_threat_level)
        private val tvReason: TextView = itemView.findViewById(R.id.tv_threat_reason)
        private val tvType: TextView = itemView.findViewById(R.id.tv_device_type)
        private val tvRssi: TextView = itemView.findViewById(R.id.tv_rssi)
        private val tvManufacturer: TextView = itemView.findViewById(R.id.tv_manufacturer)
        private val tvSeenCount: TextView = itemView.findViewById(R.id.tv_seen_count)
        private val signalBar: ProgressBar = itemView.findViewById(R.id.signal_bar)

        fun bind(info: BtDeviceInfo) {
            tvName.text = info.displayName
            tvAddress.text = info.address
            tvThreat.text = info.threatLevel.label
            tvReason.text = info.threatReason
            tvType.text = info.deviceType
            tvRssi.text = "${info.rssi} dBm (${info.signalDescription})"
            tvManufacturer.text = info.manufacturer ?: "Unknown manufacturer"
            tvSeenCount.text = "Seen ${info.seenCount}×"

            signalBar.progress = (info.signalBars * 25).coerceIn(0, 100)

            val (cardColor, threatColor) = when (info.threatLevel) {
                BtDeviceInfo.ThreatLevel.HIGH    -> Pair(Color.parseColor("#FFEBEE"), Color.parseColor("#C62828"))
                BtDeviceInfo.ThreatLevel.MEDIUM  -> Pair(Color.parseColor("#FFF3E0"), Color.parseColor("#E65100"))
                BtDeviceInfo.ThreatLevel.LOW     -> Pair(Color.parseColor("#FFFDE7"), Color.parseColor("#F57F17"))
                BtDeviceInfo.ThreatLevel.UNKNOWN -> Pair(Color.parseColor("#F5F5F5"), Color.parseColor("#616161"))
            }

            card.setCardBackgroundColor(cardColor)
            // Tint the badge background per threat level — bg_badge is solid #C62828,
            // so without this the HIGH-risk badge text was invisible (same color as background)
            // and every other level showed a misleading red "HIGH RISK"-style badge.
            tvThreat.backgroundTintList = ColorStateList.valueOf(threatColor)
            tvThreat.setTextColor(Color.WHITE)
            signalBar.progressTintList = ColorStateList.valueOf(threatColor)

            card.setOnClickListener { onItemClick(info) }
        }
    }
}
