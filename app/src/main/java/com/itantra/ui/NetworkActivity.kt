package com.itantra.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.itantra.ai4bharat.ModelCapabilityRegistry
import com.itantra.benchmark.BenchmarkLogger
import com.itantra.databinding.ActivityNetworkBinding
import com.itantra.identity.NodeIdentity
import com.itantra.transport.DeliveryStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Network Map + Diagnostics screen. Reads LIVE state from the shared
 * orchestrator — never fabricates nodes or numbers. Auto-refreshes as network
 * state changes (node discovered, route changed, packet forwarded, etc.).
 */
class NetworkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkBinding
    private var autoRefreshJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        render()
        binding.btnRefresh.setOnClickListener { render() }

        // Live auto-refresh driven by real backend StateFlows:
        // delivery status, latency, and topology changes.
        val orch = (application as com.itantra.iTantraApp).orchestrator
        if (orch != null) {
            lifecycleScope.launch { orch.deliveryStatus.collect { render() } }
            lifecycleScope.launch { orch.topologyTick.collect { render() } }
            lifecycleScope.launch { orch.lastLatencyMetrics.collect { render() } }
        }
        // Fallback poller for transports/peers not surfaced via StateFlow.
        autoRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(2000)
                render()
            }
        }
    }

    override fun onDestroy() {
        autoRefreshJob?.cancel()
        super.onDestroy()
    }

    private fun render() {
        val app = application as com.itantra.iTantraApp
        val orch = app.orchestrator

        // --- Node identity ---
        val profile = NodeIdentity.current()
        binding.tvNodeId.text = if (profile != null) {
            "THIS NODE  ${profile.nodeId}\nROLE ${profile.role}   PROTOCOL v${profile.protocolVersion}"
        } else {
            "THIS NODE  (uninitialized)"
        }

        // --- Connection + security summary (real backend) ---
        val connected = orch?.transport?.isConnected() == true
        val transType = orch?.meshRoutingManager?.discovery?.neighbors?.values.orEmpty()
            .joinToString("/") { it.transportType.name }
        binding.tvSecurity.text = buildString {
            appendLine(if (connected) "STATUS   ● Connected" else "STATUS   ● Offline")
            appendLine("TRANSPORT   ${transType.ifEmpty { "Bluetooth / Wi-Fi Direct" }}")
            appendLine("SECURITY   ECDH P-256  ·  AES-256-GCM")
            append("OFFLINE   No internet required — proximity radio links only")
        }

        // --- Neighbors ---
        val discovery = orch?.meshRoutingManager?.discovery
        val neighbors = discovery?.neighbors?.values ?: emptyList()
        binding.tvNeighbors.text = if (neighbors.isEmpty()) {
            "No neighbors discovered yet\n\nDiscovery packets (NODE_HELLO) are exchanged automatically once a peer connects."
        } else {
            neighbors.sortedByDescending { it.lastSeenMs }.joinToString("\n") { n ->
                "${n.nodeId}  ${n.displayName}\n   role=${n.role}  quality=${"%.0f".format(n.linkQuality * 100)}%  seen=${rel(n.lastSeenMs)}\n   transport=${n.transportType}"
            }
        }

        // --- Routing table ---
        val routes = discovery?.getAllRoutes() ?: emptyList()
        binding.tvRoutes.text = if (routes.isEmpty()) {
            "No routes learned yet (learned via ROUTE_REQUEST/RESPONSE and NODE_ANNOUNCE)"
        } else {
            routes.sortedBy { it.hopCount }.joinToString("\n") { r ->
                "${r.destinationId}  (${r.destinationMode})  → via ${r.nextHopId}  hops=${r.hopCount}  conf=${"%.0f".format(r.routeConfidence * 100)}%  expires=${rel(r.expiryMs)}"
            }
        }

        // --- Model capability (verified against real assets) ---
        ModelCapabilityRegistry.initialize(applicationContext)
        val sb = StringBuilder()
        for (cap in ModelCapabilityRegistry.getAllCapabilities()) {
            val stt = if (cap.sttAvailable) "STT ✓" else "STT ✗"
            val tts = if (cap.ttsAvailable) "TTS ✓" else "TTS ✗"
            sb.appendLine("${cap.language.displayName.padEnd(10)} $stt  $tts")
        }
        sb.appendLine("\nSTT: Whisper base int8 (shared, all 10 languages) — offline on device")
        sb.appendLine("TTS: downloadable Piper/VITS voices (hi, en, ml, gu, bn) — offline on device")
        // ML device profile + active model
        orch?.speechModelManager?.let { smm ->
            sb.appendLine()
            sb.appendLine("DEVICE: ${smm.describeDevice()}")
            sb.appendLine("LANG: ${smm.currentLanguage().code}")
            sb.appendLine("STT: ${smm.registry().bestAvailable(smm.currentLanguage().code, com.itantra.speech.ModelRole.STT, smm.deviceClass)?.modelName ?: "none"}")
            sb.appendLine("TTS: ${smm.registry().bestAvailable(smm.currentLanguage().code, com.itantra.speech.ModelRole.TTS, smm.deviceClass)?.modelName ?: "none"}")
        }
        binding.tvModels.text = sb.toString()

        // --- Delivery status ---
        val tracker = orch?.deliveryTracker
        val statuses = tracker?.getAll()?.takeLast(10) ?: emptyList()
        binding.tvDelivery.text = if (statuses.isEmpty()) {
            "No messages tracked yet. Send or receive a message to see its live status."
        } else {
            statuses.joinToString("\n") { s ->
                "${icon(s.status)}  ${s.recipientId}  [${s.recipientMode}]  hops=${s.hopCount}  ${s.status}"
            }
        }

        // --- Performance ---
        val records = BenchmarkLogger.getRecords().takeLast(5)
        binding.tvPerf.text = if (records.isEmpty()) {
            "No latency samples yet. STT / transport / TTS / E2E latency appear after a message."
        } else {
            records.joinToString("\n") { r ->
                "STT ${r.sttLatencyMs}ms  RTF ${"%.2f".format(r.rtf)}  NET ${r.transportLatencyMs}ms  TTS ${r.ttsLatencyMs}ms  E2E ${r.totalE2eLatencyMs}ms"
            }
        }
    }

    private fun icon(status: DeliveryStatus): String = when (status) {
        DeliveryStatus.ACKNOWLEDGED -> "✓"
        DeliveryStatus.DELIVERED -> "▸"
        DeliveryStatus.PLAYING -> "▶"
        DeliveryStatus.EXPIRED -> "✗"
        DeliveryStatus.FAILED -> "✗"
        else -> "•"
    }

    private fun rel(ms: Long): String {
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 60_000 -> "${diff / 1000}s ago"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            else -> "${diff / 3_600_000}h ago"
        }
    }
}
