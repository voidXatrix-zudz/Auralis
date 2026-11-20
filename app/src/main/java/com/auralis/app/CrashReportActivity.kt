package com.auralis.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.auralis.app.logging.CrashHandler
import java.io.File

/**
 * Plain-View crash viewer that lives in its own `:crash` process.
 *
 * It deliberately touches nothing from the app graph — no Clerk, Room, Hilt or [AppContainer] — so
 * it can render even when the normal startup path is the thing that crashed. It shows the newest
 * report (or the one named by [EXTRA_CRASH_FILE]) with Copy/Share/Close.
 */
class CrashReportActivity : Activity() {

    private var reportText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = resolveCrashFile()
        reportText = file?.let { runCatching { it.readText() }.getOrNull() }
            ?: "No crash report was found on this device."
        if (file != null) runCatching { CrashHandler.acknowledge(this, file) }
        setContentView(buildContent())
    }

    private fun resolveCrashFile(): File? {
        val path = intent?.getStringExtra(EXTRA_CRASH_FILE)
        if (path != null) {
            val file = File(path)
            if (file.exists()) return file
        }
        return CrashHandler.latestCrashFile(this)
    }

    private fun buildContent(): ViewGroup {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val textColor = if (dark) 0xFFECECEC.toInt() else 0xFF1A1A1A.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(if (dark) 0xFF121212.toInt() else 0xFFF6F6F6.toInt())
        }

        root.addView(TextView(this).apply {
            text = "AURALIS crashed"
            setTextColor(textColor)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "A crash report was saved on this device. Copy or share it before closing, then " +
                "reopen AURALIS. A copy is kept in the app's crash folder."
            setTextColor(textColor)
            textSize = 13f
            setPadding(0, dp(6), 0, dp(12))
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(TextView(this).apply {
            text = reportText
            setTextColor(textColor)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        root.addView(scroll)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(actionButton("Copy") { copyReport() })
        row.addView(actionButton("Share") { shareReport() })
        row.addView(actionButton("Close") { finish() })
        root.addView(row)
        return root
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
    }

    private fun copyReport() {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("AURALIS crash", reportText))
        }
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        runCatching {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "AURALIS-crash.txt")
            file.writeText(reportText)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "AURALIS crash report")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("AURALIS crash", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share crash report"))
        }.onFailure {
            Toast.makeText(this, "Could not share the report", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CRASH_FILE = "crash_file"
    }
}
