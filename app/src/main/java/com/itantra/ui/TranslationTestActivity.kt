package com.itantra.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.itantra.translation.NativeTranslateResult
import com.itantra.translation.OpusMtTranslationEngine

/**
 * Engineering-only translation test harness (SIH Phase 20). Fully offline,
 * drives the REAL Opus-MT engine directly — used for physical-device validation,
 * not for fake benchmark presentation. No cloud, no mock models.
 */
class TranslationTestActivity : ComponentActivity() {

    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var inputText: EditText
    private lateinit var outputView: TextView
    private lateinit var statusView: TextView
    private var engine: OpusMtTranslationEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = OpusMtTranslationEngine(applicationContext)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(18))
        }
        root.addView(tv("TRANSLATION TEST — OFFLINE", bold = true, size = 16f))

        root.addView(tv("Source:"))
        sourceSpinner = Spinner(this).apply {
            adapter = langAdapter()
            setSelection(0) // hi
        }
        root.addView(sourceSpinner)

        root.addView(tv("Target:"))
        targetSpinner = Spinner(this).apply {
            adapter = langAdapter()
            setSelection(1) // en
        }
        root.addView(targetSpinner)

        root.addView(tv("Input text:"))
        inputText = EditText(this).apply {
            hint = "मुझे मदद चाहिए"
            minLines = 2
        }
        root.addView(inputText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(btn("TRANSLATE") { runTranslate() }, lparams(1))
        row.addView(btn("LOAD MODEL") { runLoad() }, lparams(1))
        root.addView(row)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row2.addView(
            btn("RELEASE MODEL") { runRelease() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(
            btn("SELF TEST") { runSelfTest() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)

        outputView = tv("", bold = false)
        root.addView(tv("Output:"))
        root.addView(outputView)
        statusView = tv("Idle", size = 12f)
        root.addView(statusView)
        return root
    }

    private fun langAdapter(): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("hi", "en"))

    private fun currentSource(): String = sourceSpinner.selectedItem as String
    private fun currentTarget(): String = targetSpinner.selectedItem as String

    private fun runTranslate() {
        val text = inputText.text.toString()
        val src = currentSource(); val tgt = currentTarget()
        status("TRANSLATING $src → $tgt (offline)")
        Thread {
            val start = android.os.SystemClock.elapsedRealtime()
            val res = engine?.translate(text, src, tgt)
            val ms = android.os.SystemClock.elapsedRealtime() - start
            runOnUiThread {
                if (res != null && res.success) {
                    outputView.text = res.translatedText
                    status("OK · ${ms} ms wall")
                } else {
                    outputView.text = ""
                    status("FAILED · ${ms} ms · ${res?.error ?: "no engine"}")
                }
            }
        }.start()
    }

    private fun runLoad() {
        val src = currentSource(); val tgt = currentTarget()
        Thread {
            val ok = engine?.ensureLoaded(src, tgt) == true
            runOnUiThread { status(if (ok) "Loaded pair $src → $tgt (isLoaded=${engine?.isLoaded()})" else "LOAD FAILED for $src → $tgt") }
        }.start()
    }

    private fun runRelease() {
        engine?.release()
        status("Release() executed; isLoaded=${engine?.isLoaded()}")
    }

    private fun runSelfTest() {
        // Native deterministic self-test (real engine, offline). Deliberately does
        // NOT report a WER — no measurement is fabricated for this harness.
        try {
            val r = engine?.nativeSelfTest() ?: "no-engine"
            val parse = r.startsWith("NATIVE_TEST_OK")
            status(if (parse) "SELF TEST OK — native engine executed" else "SELF TEST FAILED: $r")
        } catch (e: Exception) {
            status("SELF TEST THREW: ${e.message}")
        }
    }

    private fun status(s: String) = runOnUiThread { statusView.text = s }

    private fun tv(text: String, bold: Boolean = false, size: Float = 13f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(0, dp(8), 0, dp(2))
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun lparams(weight: Int) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight.toFloat())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        engine?.release()
        super.onDestroy()
    }
}