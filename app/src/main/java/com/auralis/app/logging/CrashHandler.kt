package com.auralis.app.logging

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash capture.
 *
 * Installed as the very first thing in [com.auralis.app.AuralisApp.attachBaseContext], before any
 * library or Keystore initialisation, so a startup crash is still written to disk. It never
 * rewrites or deletes user data and never records secrets — only the stack trace and coarse device
 * facts.
 *
 * Behaviour:
 * - Writes the full stack trace (every "Caused by") plus device info to `filesDir/crash/`.
 * - Keeps only the newest [MAX_REPORTS] reports.
 * - Guards against a crash loop: a second crash within [LOOP_WINDOW_MS] writes the file but does
 *   not launch the report screen (which would flicker on and off forever).
 * - Hands the process off to the previously installed handler so the platform still restarts/kills
 *   the app normally.
 */
object CrashHandler {
    const val CRASH_DIR = "crash"
    const val STARTUP_WARNINGS_FILE = "startup-warnings.txt"

    private const val MAX_REPORTS = 5
    private const val LOOP_WINDOW_MS = 10_000L
    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".txt"
    private const val PREFS = "auralis_crash"
    private const val KEY_ACKNOWLEDGED = "acknowledged_latest"
    private const val STARTUP_WARNINGS_MAX_BYTES = 200_000L
    private const val DIAGNOSTICS_CRASH_CHARS = 6_000

    private val lineStamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val fileStamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }

    @Volatile private var installed = false

    /** Chains the previous handler, so the platform's default crash behaviour is preserved. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handle(app, thread, throwable, previous)
        }
    }

    private fun handle(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        previous: Thread.UncaughtExceptionHandler?,
    ) {
        val looping = hasRecentCrash(context)
        val file = try {
            writeCrashReport(context, thread, throwable, looping)
        } catch (_: Throwable) {
            null
        }
        if (!looping && file != null) {
            runCatching { launchReport(context, file) }
        }
        val handler = previous
        if (handler != null) {
            handler.uncaughtException(thread, throwable)
        } else {
            Process.killProcess(Process.myPid())
        }
    }

    /** True when a crash was already recorded inside [LOOP_WINDOW_MS] (this is the 2nd or later). */
    private fun hasRecentCrash(context: Context): Boolean {
        val cutoff = System.currentTimeMillis() - LOOP_WINDOW_MS
        return crashFiles(context).any { it.lastModified() >= cutoff }
    }

    /** Writes one report. Never throws: a failure here must not mask the original crash. */
    fun writeCrashReport(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        looping: Boolean = false,
    ): File {
        val dir = crashDir(context)
        val file = File(dir, "$FILE_PREFIX${fileStamp.get()!!.format(Date())}$FILE_SUFFIX")
        val text = buildString {
            appendLine("AURALIS crash report")
            appendLine("Generated: ${lineStamp.get()!!.format(Date())}")
            appendLine("Thread: ${thread.name}")
            appendLine("Process: ${currentProcessName()}")
            if (looping) {
                appendLine("Note: repeated crash detected within ${LOOP_WINDOW_MS / 1000} s (crash loop); " +
                    "the report screen was suppressed.")
            }
            appendLine()
            appendLine("[Device]")
            appendLine(deviceInfoBlock(context))
            appendLine()
            appendLine("[Exception]")
            append(throwable.toDetailedString())
        }
        runCatching { file.writeText(text) }
        prune(dir)
        return file
    }

    /**
     * Records a non-fatal startup failure (a step that was degraded instead of crashing) into the
     * same folder, so the exported diagnostics explain why a feature is missing on this device.
     */
    fun writeStartupWarning(context: Context, step: String, error: Throwable) {
        runCatching {
            val dir = crashDir(context)
            val file = File(dir, STARTUP_WARNINGS_FILE)
            if (file.length() > STARTUP_WARNINGS_MAX_BYTES) file.writeText("")
            val entry = buildString {
                appendLine("${lineStamp.get()!!.format(Date())} startup step failed: $step")
                appendLine(error.toDetailedString())
                appendLine()
            }
            file.appendText(entry)
        }
    }

    /** The newest crash report on disk, or null when there has never been one. */
    fun latestCrashFile(context: Context): File? = crashFiles(context).lastOrNull()

    /**
     * The crash report the next launch should announce once, or null when there is nothing new (no
     * report, or the newest one was already acknowledged).
     */
    fun pendingCrashReport(context: Context): File? {
        val latest = latestCrashFile(context) ?: return null
        val acked = prefs(context).getString(KEY_ACKNOWLEDGED, null)
        return if (latest.name != acked) latest else null
    }

    /** Marks a report as seen so the next launch does not announce it again. */
    fun acknowledge(context: Context, file: File) {
        runCatching { prefs(context).edit().putString(KEY_ACKNOWLEDGED, file.name).apply() }
    }

    /** A bounded copy of the newest crash report for the diagnostics export; null when none. */
    fun latestCrashSummary(context: Context): String? {
        val file = latestCrashFile(context) ?: return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return if (text.length <= DIAGNOSTICS_CRASH_CHARS) text
        else text.take(DIAGNOSTICS_CRASH_CHARS) + "\n… (truncated)"
    }

    fun readStartupWarnings(context: Context): String? {
        val file = File(crashDir(context), STARTUP_WARNINGS_FILE)
        if (!file.exists()) return null
        return runCatching { file.readText().takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** Coarse device/app facts. Nothing here can identify a user or carry a secret. */
    fun deviceInfoBlock(context: Context): String = buildString {
        appendLine("app: ${com.auralis.app.BuildConfig.VERSION_NAME} (${com.auralis.app.BuildConfig.VERSION_CODE})")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("manufacturer: ${Build.MANUFACTURER}")
        appendLine("model: ${Build.MODEL}")
        appendLine("device: ${Build.DEVICE}")
        appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("pageSize: ${pageSizeBytes()} bytes")
        appendLine("locale: ${Locale.getDefault()}")
        appendLine("process: ${currentProcessName()}")
        memoryLine(context)?.let { appendLine(it) }
    }

    /**
     * The current process name. `Application.getProcessName()` is API 28+, so fall back to
     * `/proc/self/cmdline`, which is available everywhere the app runs.
     */
    fun currentProcessName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
        }
    }.getOrDefault("")

    private fun memoryLine(context: Context): String? = runCatching {
        val rt = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        "memory: heapFree=${rt.freeMemory() / 1024 / 1024}MB heapMax=${rt.maxMemory() / 1024 / 1024}MB " +
            "sysAvail=${info.availMem / 1024 / 1024}MB sysLow=${info.lowMemory}"
    }.getOrNull()

    private fun pageSizeBytes(): Long = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
    }.getOrDefault(-1L)

    private fun launchReport(context: Context, file: File) {
        val intent = Intent(context, com.auralis.app.CrashReportActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            putExtra(com.auralis.app.CrashReportActivity.EXTRA_CRASH_FILE, file.absolutePath)
        }
        context.startActivity(intent)
    }

    private fun prune(dir: File) {
        val files = listReports(dir)
        if (files.size <= MAX_REPORTS) return
        files.dropLast(MAX_REPORTS).forEach { runCatching { it.delete() } }
    }

    private fun crashFiles(context: Context): List<File> = listReports(crashDir(context))

    /** Oldest first. */
    private fun listReports(dir: File): List<File> =
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
                ?.sortedBy { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())

    private fun crashDir(context: Context): File =
        File(context.filesDir, CRASH_DIR).apply { mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Throwable.toDetailedString(): String {
        val writer = StringWriter()
        printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
