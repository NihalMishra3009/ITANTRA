package com.itantra.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.itantra.R
import com.itantra.databinding.ActivityLanguageModelsBinding
import com.itantra.speech.LanguageModelPack
import com.itantra.speech.PackStatus
import com.itantra.speech.SpeechModelManager
import java.util.Locale

/**
 * Language Models screen. Lists every required language with independent STT / TTS
 * Download | Delete actions. All sizes/statuses reflect ACTUAL catalog metadata +
 * installed filesystem state — no fake availability, no hardcoded sizes.
 */
class LanguageModelsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLanguageModelsBinding
    private val smm: SpeechModelManager by lazy {
        (application as com.itantra.iTantraApp).orchestrator?.speechModelManager
            ?: SpeechModelManager(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanguageModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        binding.container.removeAllViews()
        val packs = smm.catalog()
        for (pack in packs) {
            binding.container.addView(packRow(pack))
        }
        renderStorageSummary()
        autoLoadInstalledTtsVoices()
    }

    /** Automatically (lazily) load any installed TTS voice so downloaded voices become
     *  usable without re-downloading. Crash-guarded inside the engine. */
    private fun autoLoadInstalledTtsVoices() {
        val loaded = smm.loadDownloadedVoice(smm.currentLanguage().code)
        if (loaded) {
            android.widget.Toast.makeText(this, "TTS voice loaded", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** One row per STT or TTS pack: language label (grouped by STT then TTS),
     *  pack name + genuine size, then a Download / Delete / status button. */
    private fun packRow(pack: LanguageModelPack): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
            background = getDrawable(R.drawable.bg_surface_chip)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
        }

        // Title: "Hindi — STT (IndicConformer-600m-multilingual)"
        val title = TextView(this).apply {
            val langName = pack.language.displayName
            text = "$langName — ${pack.role.name} (${pack.modelName})"
            setTextColor(getColor(R.color.text_white))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 14, 16, 0)
        }
        row.addView(title)

        // Honest shared-checkpoint note
        val note = TextView(this).apply {
            text = if (pack.notes.isNotBlank()) pack.notes else ""
            setTextColor(getColor(R.color.text_faint))
            textSize = 11f
            setPadding(16, 2, 16, 0)
        }
        row.addView(note)

        // Bottom bar: size + status + action
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 12)
        }
        val sizeLabel = TextView(this).apply {
            val mb = if (pack.sizeBytes > 0) pack.sizeMb else 0.0
            text = String.format(Locale.US, "%.0f MB", mb)
            setTextColor(getColor(R.color.comm_amber))
            setPadding(0, 0, 10, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(sizeLabel)

        val status = smm.distributionManager().status(pack)
        val statusText = statusText(status)
        val statusLabel = TextView(this).apply {
            text = statusText
            setTextColor(getColor(statusColor(status)))
            textSize = 13f
            setPadding(0, 0, 10, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(statusLabel)

        when {
            status == PackStatus.INSTALLED -> bar.addView(
                smallButton("Delete", R.color.comm_red) {
                    smm.distributionManager().deletePack(pack)
                    render()
                }
            )
            // No converted loadable artifact -> no download offered (honest).
            status == PackStatus.NOT_INSTALLED && pack.downloadUrl == null -> bar.addView(
                smallButton("Needs conversion", R.color.text_faint) { /* no-op */ }
            )
            status == PackStatus.NOT_INSTALLED -> bar.addView(
                smallButton("Download", R.color.comm_green) {
                    startDownload(pack, bar, sizeLabel)
                }
            )
            else -> {
                // transient statuses (downloading / verifying / failed / corrupted)
                bar.addView(
                    smallButton("Retry", R.color.comm_amber) { startDownload(pack, bar, sizeLabel) }
                )
            }
        }
        row.addView(bar)
        return row
    }

    private fun smallButton(label: String, colorRes: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(colorRes))
            setOnClickListener { onClick() }
        }

    private fun startDownload(pack: LanguageModelPack, bar: LinearLayout, sizeLabel: TextView) {
        if (!pack.supportsLanguage || pack.downloadUrl == null) {
            sizeLabel.text = "No source for ${pack.language.code}"
            return
        }
        val pbar = android.widget.TextView(this).apply {
            text = "Starting…"
            setTextColor(getColor(R.color.comm_amber))
            setPadding(0, 0, 10, 0)
            textSize = 13f
        }
        bar.addView(pbar, 0)
        smm.installLanguagePack(
            pack,
            onProgress = { f ->
                runOnUiThread { pbar.text = "Downloading ${(f * 100).toInt()}%" }
            },
            onDone = { result ->
                runOnUiThread {
                    if (result.isSuccess && pack.role == com.itantra.speech.ModelRole.TTS) {
                        // Try to load the just-installed voice (Piper uses espeak-ng).
                        val loaded = smm.loadDownloadedVoice(pack.language.code)
                        pbar.text = if (loaded) "Voice loaded" else "Installed (load deferred)"
                    }
                    render()
                }
            }
        )
    }

    private fun statusText(s: PackStatus): String = when (s) {
        PackStatus.NOT_INSTALLED -> "Not installed"
        PackStatus.DOWNLOADING -> "Downloading"
        PackStatus.VERIFYING -> "Verifying"
        PackStatus.INSTALLED -> "Installed"
        PackStatus.LOADING -> "Loading"
        PackStatus.LOADED -> "Loaded"
        PackStatus.FAILED -> "Failed"
        PackStatus.CORRUPTED -> "Corrupted"
        PackStatus.UPDATE_AVAILABLE -> "Update available"
    }

    private fun statusColor(s: PackStatus): Int = when (s) {
        PackStatus.INSTALLED, PackStatus.LOADED -> R.color.comm_green
        PackStatus.NOT_INSTALLED -> R.color.text_muted
        PackStatus.FAILED, PackStatus.CORRUPTED -> R.color.comm_red
        else -> R.color.comm_amber
    }

    private fun renderStorageSummary() {
        val st = smm.storageManager()
        val sttBytes = st.installedStt().values.sum()
        val ttsBytes = st.installedTts().values.sum()
        val totalMb = (sttBytes + ttsBytes) / (1024.0 * 1024.0)
        val sb = StringBuilder()
        if (sttBytes > 0) {
            sb.append("STT:\n")
            st.installedStt().forEach { (lang, bytes) ->
                sb.append("  ${lang.uppercase(Locale.US).padEnd(4)} ")
                sb.append(String.format(Locale.US, "%.0f MB\n", bytes / (1024.0 * 1024.0)))
            }
        }
        if (ttsBytes > 0) {
            sb.append("TTS:\n")
            st.installedTts().forEach { (lang, bytes) ->
                sb.append("  ${lang.uppercase(Locale.US).padEnd(4)} ")
                sb.append(String.format(Locale.US, "%.0f MB\n", bytes / (1024.0 * 1024.0)))
            }
        }
        sb.append("Total: ${"%.0f".format(totalMb)} MB")
        binding.tvStorageSummary.text = sb.toString()
    }
}