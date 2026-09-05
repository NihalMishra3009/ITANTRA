package com.itantra.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.itantra.R
import com.itantra.databinding.ActivityLanguageModelsBinding
import com.itantra.speech.LanguageModelPack
import com.itantra.speech.ModelRole
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
        // Prototype leads with Hindi + English; remaining languages follow alphabetically.
        val priority = listOf("hi", "en")
        val grouped = smm.catalog()
            .groupBy { it.language.code }
            .toSortedMap()
            .toList()
            .sortedBy { (code, _) -> priority.indexOf(code).let { if (it < 0) Int.MAX_VALUE else it } }
        grouped.forEach { (_, packs) ->
            binding.container.addView(languageSection(
                packs.first().language.displayName,
                packs.first().language.nativeName,
                packs.sortedBy { it.role.name }
            ))
        }
        renderStorageSummary()
    }

    /** One grouped section: language header + one row per STT/TTS pack. */
    private fun languageSection(name: String, nativeName: String, packs: List<LanguageModelPack>): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6 }
        }

        val heading = TextView(this).apply {
            text = "$name  ·  $nativeName"
            setTextColor(getColor(R.color.text_white))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        section.addView(heading)

        for (pack in packs) {
            section.addView(packRow(pack))
        }
        return section
    }

    /** One row per STT or TTS pack: role + model name + size, genuine status, action. */
    private fun packRow(pack: LanguageModelPack): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
            background = getDrawable(R.drawable.bg_surface_chip)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6 }
        }

        // Role model line: "STT — Whisper base int8" / "TTS — VITS Piper hi"
        val title = TextView(this).apply {
            val role = if (pack.role == ModelRole.STT) "STT" else "TTS"
            text = "$role — ${pack.modelName}"
            setTextColor(getColor(R.color.text_white))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 12, 16, 0)
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
        val statusLabel = TextView(this).apply {
            text = statusText(status)
            setTextColor(getColor(statusColor(status)))
            textSize = 13f
            setPadding(0, 0, 10, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(statusLabel)

        when {
            status == PackStatus.INSTALLED || status == PackStatus.LOADED -> bar.addView(
                smallButton("Delete", R.color.comm_red) {
                    smm.distributionManager().deletePack(pack)
                    render()
                }
            )
            // No converted loadable artifact -> no download offered (honest).
            status == PackStatus.NOT_INSTALLED && pack.downloadUrl == null -> bar.addView(
                smallButton("Unavailable", R.color.text_faint) { /* no-op */ }
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
            setBackgroundResource(R.drawable.bg_button_rounded)
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
                        pbar.text = "Installed ✓"
                        // Load the just-installed voice off the UI thread — the sherpa
                        // OfflineTts init is heavy and is lazy-restored by TtsEngine on
                        // next synthesize anyway. TtsEngine guards the load internally.
                        Thread {
                            try { smm.loadDownloadedVoice(pack.language.code) } catch (_: Throwable) {}
                        }.start()
                    }
                    render()
                }
            }
        )
    }

    private fun statusText(s: PackStatus): String = when (s) {
        PackStatus.NOT_INSTALLED -> "Not installed"
        PackStatus.DOWNLOADING -> "Downloading"
        PackStatus.VERIFYING -> "Verifying SHA-256"
        PackStatus.INSTALLED -> "Installed · SHA-256 ✓"
        PackStatus.LOADING -> "Loading"
        PackStatus.LOADED -> "Loaded · SHA-256 ✓"
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