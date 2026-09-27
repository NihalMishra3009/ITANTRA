package com.itantra.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.itantra.R
import com.itantra.databinding.ActivityLanguageModelsBinding
import com.itantra.speech.LanguageModelPack
import com.itantra.speech.Mlruntime
import com.itantra.speech.ModelRole
import com.itantra.speech.PackStatus
import com.itantra.speech.SpeechModelManager
import java.util.Locale
import kotlin.math.min

/**
 * Language Models screen — marketplace-style library of offline speech packs.
 * Real backend only (SpeechModelManager/catalog/PackStatus). Languages are the unit;
 * STT and TTS stay independently downloadable. Nothing is faked.
 */
class LanguageModelsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLanguageModelsBinding
    private val smm: SpeechModelManager by lazy {
        (application as com.itantra.iTantraApp).orchestrator?.speechModelManager
            ?: SpeechModelManager(applicationContext)
    }

    private enum class Tab { INSTALLED, AVAILABLE, ALL }
    private var activeTab = Tab.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanguageModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        setupTabs()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun setupTabs() {
        binding.btnTabInstalled.setOnClickListener { activeTab = Tab.INSTALLED; render() }
        binding.btnTabAvailable.setOnClickListener { activeTab = Tab.AVAILABLE; render() }
        binding.btnTabAll.setOnClickListener { activeTab = Tab.ALL; render() }
    }

    private fun renderTab() {
        fun set(tab: TextView, selected: Boolean, label: String) {
            tab.text = label
            tab.isSelected = selected
            tab.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.comm_green else R.color.text_white)
            )
        }
        set(binding.btnTabInstalled, activeTab == Tab.INSTALLED, getString(R.string.tab_installed))
        set(binding.btnTabAvailable, activeTab == Tab.AVAILABLE, getString(R.string.tab_available))
        set(binding.btnTabAll, activeTab == Tab.ALL, getString(R.string.tab_all))
    }

    private fun render() {
        renderTab()
        binding.container.removeAllViews()

        if (engineVisible()) {
            smm.enginePacks().forEach { binding.container.addView(engineCard(it)) }
        }

        binding.container.addView(translationPairsSection())

        orderedLanguagePacks()
            .filter { matchesTab(it) }
            .forEach { binding.container.addView(languageCard(it)) }

        renderStorage()
    }

    /**
     * Offline translation: ONE action for the user's own language, not a per-pair list.
     * The user only ever picks their own language (on the home screen "MY LANGUAGE"
     * selector) — translation itself is entirely automatic and receiver-side (see
     * PipelineOrchestrator.currentLanguage). So here we download every pack THAT language
     * needs (as pivot source or final target) in one click, instead of listing individual
     * directed pairs for the user to reason about.
     */
    private fun translationPairsSection(): LinearLayout {
        val myLang = (application as com.itantra.iTantraApp).orchestrator?.currentLanguage
            ?: com.itantra.stt.SupportedLanguage.HINDI
        val relevant = com.itantra.speech.ModelCatalog.translationPacksFor(myLang)
        val hosted = relevant.filter { it.downloadUrl != null }
        val notInstalled = hosted.filter {
            val s = smm.distributionManager().status(it)
            s != PackStatus.INSTALLED && s != PackStatus.LOADED
        }
        val installed = hosted - notInstalled.toSet()

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawableCompat(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        section.addView(TextView(this).apply {
            text = "OFFLINE TRANSLATION — ${myLang.nativeName.uppercase(Locale.US)}"
            setTextColor(getColor(R.color.text_white))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        section.addView(TextView(this).apply {
            text = "Helsinki-NLP Opus-MT (ONNX, Apache-2.0) — fully offline after install. " +
                "Downloads everything needed to translate for your language; change language on the home screen."
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            setPadding(0, 2, 0, 0)
        })

        val progress = TextView(this).apply {
            visibility = View.GONE
            setTextColor(getColor(R.color.comm_amber))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }

        val summary = TextView(this).apply {
            setTextColor(getColor(R.color.text_muted))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
            text = when {
                hosted.isEmpty() -> "No offline translation hosted for ${myLang.displayName} yet."
                notInstalled.isEmpty() -> "✓ All ${installed.size} translation pack(s) for ${myLang.displayName} installed."
                else -> {
                    val mb = notInstalled.sumOf { it.sizeBytes } / (1024.0 * 1024.0)
                    "${notInstalled.size} pack(s) needed, ${installed.size} already installed — " +
                        String.format(Locale.US, "%.0f MB total", mb)
                }
            }
        }
        section.addView(summary)
        section.addView(progress)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val action = when {
            notInstalled.isNotEmpty() -> smallButton("Download for ${myLang.displayName}", R.color.comm_green) {
                downloadAllSequential(notInstalled, progress, summary, myLang)
            }
            installed.isNotEmpty() -> smallButton("Remove all", R.color.comm_red) {
                installed.forEach { smm.distributionManager().deletePack(it) }
                render()
            }
            else -> null
        }
        action?.let { bottom.addView(it) }
        if (bottom.childCount > 0) section.addView(bottom)

        return section
    }

    /** Downloads several packs one after another, reporting ONE combined progress bar. */
    private fun downloadAllSequential(
        packs: List<LanguageModelPack>,
        progress: TextView,
        summary: TextView,
        myLang: com.itantra.stt.SupportedLanguage
    ) {
        progress.visibility = View.VISIBLE
        var index = 0
        fun next() {
            if (index >= packs.size) {
                progress.visibility = View.GONE
                render()
                return
            }
            val pack = packs[index]
            summary.text = "Downloading ${index + 1}/${packs.size} for ${myLang.displayName}…"
            smm.installLanguagePack(
                pack,
                onProgress = { f -> runOnUiThread {
                    progress.text = "${pack.language.nativeName}→${pack.targetLanguage?.displayName ?: "?"}: ${(f * 100).toInt()}%"
                } },
                onDone = { _ -> runOnUiThread {
                    index++
                    next()
                } }
            )
        }
        next()
    }

    private fun engineVisible(): Boolean = when (activeTab) {
        Tab.INSTALLED -> smm.enginePacks().any { p ->
            val s = smm.distributionManager().status(p)
            s == PackStatus.INSTALLED || s == PackStatus.LOADED
        }
        Tab.AVAILABLE -> smm.enginePacks().any { p -> p.downloadUrl != null &&
            smm.distributionManager().status(p) == PackStatus.NOT_INSTALLED }
        Tab.ALL -> true
    }

    /** Languages grouped by code — hi/en first, then alphabetical. */
    private fun orderedLanguagePacks(): List<List<LanguageModelPack>> {
        val priority = listOf("hi", "en")
        return smm.catalog()
            .groupBy { it.language.code }
            .toSortedMap()
            .toList()
            .sortedBy { (code, _) -> priority.indexOf(code).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { (_, packs) -> packs.sortedBy { it.role.name } }
            .filterNot { it.isEmpty() }
    }

    private fun ttsPack(packs: List<LanguageModelPack>): LanguageModelPack =
        packs.first { it.role == ModelRole.TTS }

    /** Tab visibility from REAL catalog/PackStatus. */
    private fun matchesTab(packs: List<LanguageModelPack>): Boolean {
        val ts = smm.distributionManager().status(ttsPack(packs))
        val installed = ts == PackStatus.INSTALLED || ts == PackStatus.LOADED
        val available = ttsPack(packs).downloadUrl != null && !installed
        return when (activeTab) {
            Tab.INSTALLED -> installed
            Tab.AVAILABLE -> available
            Tab.ALL -> true
        }
    }

    // ---------------- Language card ----------------

    private fun languageCard(packs: List<LanguageModelPack>): LinearLayout {
        val tts = ttsPack(packs)
        val ttsStatus = smm.distributionManager().status(tts)
        val sttWorking = smm.sttAvailable(tts.language.code) // bundled Whisper covers all
        val lang = tts.language

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawableCompat(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }

        // Header: 2-letter icon · name/native/description
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(iconCircle(lang.code.uppercase(Locale.US)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(TextView(this).apply {
            text = lang.displayName
            setTextColor(getColor(R.color.text_white))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (lang.nativeName != lang.displayName) {
            titles.addView(TextView(this).apply {
                text = lang.nativeName
                setTextColor(getColor(R.color.text_muted))
                textSize = 13f
            })
        }
        titles.addView(TextView(this).apply {
            text = descriptionText(sttWorking, ttsStatus, tts)
            setTextColor(getColor(R.color.text_faint))
            textSize = 11f
            setPadding(0, 2, 0, 0)
        })
        head.addView(titles)
        card.addView(head)

        // Role badges + hidden download progress
        val badges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        badges.addView(roleBadge("STT", sttWorking, "✓", R.color.comm_green))
        badges.addView(spacer(dp(18)))
        badges.addView(roleBadge("TTS", ttsStatus, tts))
        // License honesty: a non-commercial license is shown explicitly — it is NOT
        // presented as an approved open-source model.
        if (tts.notes.contains("NON-COMMERCIAL", ignoreCase = true)) {
            badges.addView(spacer(dp(12)))
            badges.addView(TextView(this).apply {
                text = "⚠ ${tts.license}"
                setTextColor(getColor(R.color.comm_amber))
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        card.addView(badges)

        val progress = TextView(this).apply {
            visibility = View.GONE
            setTextColor(getColor(R.color.comm_amber))
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(progress)

        // Verification line for installed packs
        if (ttsStatus == PackStatus.INSTALLED || ttsStatus == PackStatus.LOADED) {
            card.addView(TextView(this).apply {
                text = "✓ SHA-256 VERIFIED"
                setTextColor(getColor(R.color.comm_green))
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(8), 0, 0)
            })
        }

        // Bottom: size · primary action
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        bottom.addView(TextView(this).apply {
            text = sizeText(tts, ttsStatus)
            setTextColor(getColor(R.color.text_muted))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bottom.addView(primaryAction(tts, ttsStatus, progress))
        card.addView(bottom)

        return card
    }

    /** 2-letter region icon on a colored circle (native UI, no images). */
    private fun iconCircle(code: String): TextView = TextView(this).apply {
        text = code
        gravity = Gravity.CENTER
        background = getDrawableCompat(R.drawable.bg_button_rounded)
        backgroundTintList = ContextCompat.getColorStateList(this@LanguageModelsActivity, R.color.bg_elevated)
        setTextColor(getColor(R.color.comm_green))
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun spacer(w: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(w, 1)
    }

    /** eSpeak NG ships inside the APK: nothing to download, nothing to delete. */
    private fun isBundled(pack: LanguageModelPack): Boolean = pack.runtime == Mlruntime.ESPEAK_NG

    private fun descriptionText(sttWorking: Boolean, ttsStatus: PackStatus, tts: LanguageModelPack): String = when {
        ttsStatus == PackStatus.INSTALLED || ttsStatus == PackStatus.LOADED -> "Language pack installed — offline STT + TTS"
        isBundled(tts) && !sttWorking -> "Offline TTS works now (bundled open-source voice). Speech recognition is not available for this language yet."
        isBundled(tts) -> "Offline STT + TTS work now (bundled open-source voice)."
        ttsStatus == PackStatus.NOT_INSTALLED && tts.downloadUrl != null && !sttWorking ->
            "Offline TTS works now (bundled open-source voice). Optional: download a higher-quality neural voice."
        ttsStatus == PackStatus.NOT_INSTALLED && tts.downloadUrl != null ->
            "Offline STT + TTS work now (bundled open-source voice). Optional: download a higher-quality neural voice."
        else -> "STT works (bundled). No offline TTS voice available for this language."
    }

    private fun roleBadge(label: String, ok: Boolean, mark: String, okColor: Int): TextView =
        TextView(this).apply {
            text = "$label ${if (ok) mark else "—"}"
            setTextColor(getColor(if (ok) okColor else R.color.text_faint))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun roleBadge(label: String, ts: PackStatus, pack: LanguageModelPack): TextView =
        TextView(this).apply {
            val (mark, color) = when {
                ts == PackStatus.INSTALLED || ts == PackStatus.LOADED -> "✓" to R.color.comm_green
                isBundled(pack) -> "✓" to R.color.comm_green
                ts == PackStatus.NOT_INSTALLED && pack.downloadUrl != null -> "↓" to R.color.comm_amber
                else -> "—" to R.color.text_faint
            }
            text = "$label $mark"
            setTextColor(getColor(color))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun sizeText(pack: LanguageModelPack, s: PackStatus): String = when (s) {
        PackStatus.INSTALLED, PackStatus.LOADED -> {
            val b = smm.distributionManager().installedSize(pack)
            if (b > 0) {
                String.format(Locale.US, "%.0f MB installed", b / (1024.0 * 1024.0))
            } else "installed"
        }
        PackStatus.NOT_INSTALLED ->
            if (isBundled(pack)) "bundled with app"
            else if (pack.sizeBytes > 0) String.format(Locale.US, "%.0f MB download", pack.sizeMb)
            else "—"
        else -> statusVerb(s)
    }

    private fun statusVerb(s: PackStatus): String = when (s) {
        PackStatus.DOWNLOADING -> "Downloading"
        PackStatus.VERIFYING -> "Installing…"
        PackStatus.LOADING -> "Loading…"
        PackStatus.FAILED -> "Failed"
        PackStatus.CORRUPTED -> "Corrupted"
        else -> "—"
    }

    /** Per-language primary action — drives the independently-managed TTS role. */
    private fun primaryAction(pack: LanguageModelPack, s: PackStatus, progress: TextView): TextView = when {
        isBundled(pack) -> smallButton("Bundled", R.color.comm_green) { /* ships in the APK */ }
        s == PackStatus.INSTALLED || s == PackStatus.LOADED -> smallButton("Delete", R.color.comm_red) {
            smm.distributionManager().deletePack(pack)
            render()
        }
        s == PackStatus.NOT_INSTALLED && pack.downloadUrl != null -> smallButton("Download TTS", R.color.comm_green) {
            startDownload(pack, progress)
        }
        s == PackStatus.NOT_INSTALLED -> smallButton("Unavailable", R.color.text_faint) { /* no-op */ }
        else -> smallButton(statusVerb(s), R.color.comm_amber) { startDownload(pack, progress) }
    }

    private fun smallButton(label: String, colorRes: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(getColor(R.color.bg_black))
            setBackgroundResource(R.drawable.bg_button_rounded)
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(colorRes))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(7), dp(16), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun startDownload(pack: LanguageModelPack, progress: TextView) {
        if (!pack.supportsLanguage || pack.downloadUrl == null) return
        progress.visibility = View.VISIBLE
        progress.text = "Starting…"
        smm.installLanguagePack(
            pack,
            onProgress = { f -> runOnUiThread {
                progress.text = "Installing ${(f * 100).toInt()}%"
            } },
            onDone = { result -> runOnUiThread {
                // No eager sherpa load here: loading a downloaded voice on a raw
                // background thread can native-crash the shared TtsEngine while the
                // main pipeline uses it (process restart, install appears lost).
                // The voice is loaded lazily when its language is selected on the
                // home screen dropdown.
                render()
            } }
        )
    }

    // ---------------- STT engine card ----------------

    private fun engineCard(pack: LanguageModelPack): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawableCompat(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(iconCircle("AI"))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(TextView(this).apply {
            text = pack.modelName
            setTextColor(getColor(R.color.text_white))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = "Shared STT engine · covers all 10 languages · offline after install"
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            setPadding(0, 2, 0, 0)
        })
        head.addView(titles)
        card.addView(head)

        val status = smm.distributionManager().status(pack)
        val progress = TextView(this).apply {
            visibility = View.GONE
            setTextColor(getColor(R.color.comm_amber))
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        bottom.addView(TextView(this).apply {
            text = engineStatus(status)
            setTextColor(getColor(R.color.comm_amber))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bottom.addView(engineAction(pack, status, progress))
        card.addView(progress)
        card.addView(bottom)
        return card
    }

    private fun engineStatus(s: PackStatus): String = when (s) {
        PackStatus.INSTALLED -> "Running · replaces bundled base int8"
        PackStatus.NOT_INSTALLED -> "Higher accuracy (610 MB download)"
        PackStatus.DOWNLOADING -> "Downloading"
        PackStatus.VERIFYING -> "Verifying SHA-256…"
        PackStatus.FAILED -> "Failed"
        PackStatus.CORRUPTED -> "SHA-256 mismatch"
        else -> "…"
    }

    private fun engineAction(pack: LanguageModelPack, s: PackStatus, progress: TextView): TextView = when {
        s == PackStatus.INSTALLED || s == PackStatus.LOADED -> smallButton("Delete", R.color.comm_red) {
            smm.distributionManager().deletePack(pack)
            smm.reloadStt()
            render()
        }
        s == PackStatus.NOT_INSTALLED -> smallButton("Download", R.color.comm_green) {
            startDownload(pack, progress)
        }
        else -> smallButton(statusVerb(s), R.color.comm_amber) { startDownload(pack, progress) }
    }

    // ---------------- Storage ----------------

    private fun renderStorage() {
        val st = smm.storageManager()
        val sttBytes = st.installedStt().values.sum()
        val ttsBytes = st.installedTts().values.sum()
        val totalMb = (sttBytes + ttsBytes) / (1024.0 * 1024.0)
        binding.tvStorageUsed.text = String.format(Locale.US, "%.0f MB used", totalMb)
        binding.tvStorageBreakdown.text = "STT  %s MB    ·    TTS  %s MB".format(
            String.format(Locale.US, "%.0f", sttBytes / (1024.0 * 1024.0)),
            String.format(Locale.US, "%.0f", ttsBytes / (1024.0 * 1024.0))
        )
        binding.storageBar.progress = min(512, totalMb.toInt())
    }

    // ---------------- Util ----------------

    private fun getDrawableCompat(id: Int) = ContextCompat.getDrawable(this, id)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}