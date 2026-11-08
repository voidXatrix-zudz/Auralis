package com.auralis.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.auralis.app.BuildConfig
import com.auralis.app.core.formatSummary
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track
import com.auralis.app.logging.AppLog
import com.auralis.app.logging.LogEntry
import com.auralis.app.playback.AudioEffectsEngine
import com.auralis.app.ui.components.ScreenPadding
import com.auralis.app.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class DiagnosticLevel { OK, WARN, ERROR }

private data class DiagnosticCheck(
    val title: String,
    val level: DiagnosticLevel,
    val reason: String,
)

private enum class LogFilter(val label: String) {
    ALL("All"),
    ERRORS("Errors"),
    WARNINGS("Warnings"),
}

private val Green = Color(0xFF43A047)
private val Amber = Color(0xFFFFA000)

@Composable
fun LogsDiagnosticsScreen(
    settings: AppSettings,
    sources: List<MusicSource>,
    currentTrack: Track?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var checks by remember { mutableStateOf<List<DiagnosticCheck>>(emptyList()) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }

    val header = remember(settings, currentTrack) { exportHeader(settings, currentTrack) }

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            val loadedEntries = AppLog.entries()
            loadedEntries to collectChecks(context, sources, loadedEntries)
        }
        entries = snapshot.first
        checks = snapshot.second
    }

    LaunchedEffect(refreshKey, settings, sources) { refresh() }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5_000)
            refresh()
        }
    }

    fun buildReport(): String = buildDiagnosticReport(header, checks, entries)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(buildReport().toByteArray())
                    }
                }
            }
        }
    }

    val filtered = when (filter) {
        LogFilter.ALL -> entries
        LogFilter.ERRORS -> entries.filter { it.isError }
        LogFilter.WARNINGS -> entries.filter { it.isWarning }
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = ScreenPadding) {
        item {
            Text("Logs & diagnostics", style = MaterialTheme.typography.headlineSmall)
            Text(
                "A local record of errors, warnings and device checks. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { SummaryCard(checks) }

        item { SectionTitle("What's not working") }
        items(checks) { check -> DiagnosticRow(check) }

        item { SectionTitle("Export header") }
        item { HeaderCard(header) }

        item { SectionTitle("Logs") }
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        shape = CircleShape,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Refresh", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(buildReport()))
                        copied = true
                    },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) { Text(if (copied) "Copied" else "Copy") }
                OutlinedButton(
                    onClick = { save.launch("AURALIS-diagnostics-${System.currentTimeMillis()}.txt") },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp)); Text("Save", Modifier.padding(start = 4.dp)) }
                OutlinedButton(
                    onClick = { shareReport(context, buildReport()) },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp)); Text("Share", Modifier.padding(start = 4.dp)) }
            }
        }
        item {
            TextButton(
                onClick = { confirmClear = true },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text("Clear logs", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
            }
        }

        if (filtered.isEmpty()) {
            item {
                Text(
                    "No log entries for this filter yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else {
            items(filtered) { entry -> LogRow(entry) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all logs?") },
        text = { Text("This permanently deletes the on-device log files. Diagnostics and the rest of the app keep working.") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch {
                    withContext(Dispatchers.IO) { AppLog.clear() }
                    refresh()
                }
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SummaryCard(checks: List<DiagnosticCheck>) {
    val errors = checks.count { it.level == DiagnosticLevel.ERROR }
    val warnings = checks.count { it.level == DiagnosticLevel.WARN }
    val color = when {
        errors > 0 -> MaterialTheme.colorScheme.error
        warnings > 0 -> Amber
        else -> Green
    }
    val title = when {
        checks.isEmpty() -> "Checking the device…"
        errors > 0 -> "$errors issue${if (errors == 1) "" else "s"} need attention"
        warnings > 0 -> "$warnings warning${if (warnings == 1) "" else "s"}"
        else -> "Everything looks good"
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = color)
                Text(
                    if (errors == 0 && warnings == 0) "All checked capabilities are available."
                    else "Review the checks below for the exact reason.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(check: DiagnosticCheck) {
    val color = when (check.level) {
        DiagnosticLevel.OK -> Green
        DiagnosticLevel.WARN -> Amber
        DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    val label = when (check.level) {
        DiagnosticLevel.OK -> "OK"
        DiagnosticLevel.WARN -> "Warn"
        DiagnosticLevel.ERROR -> "Error"
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp).size(10.dp).background(color, CircleShape))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(check.title, style = MaterialTheme.typography.titleSmall)
                Text(check.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun HeaderCard(header: List<Pair<String, String>>) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            header.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when {
        entry.isError -> MaterialTheme.colorScheme.error
        entry.isWarning -> Amber
        entry.level == 'I' -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.level.toString(),
                color = color,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(entry.tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(entry.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        entry.stackTrace?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun exportHeader(settings: AppSettings, track: Track?): List<Pair<String, String>> = listOf(
    "App version" to BuildConfig.VERSION_NAME,
    "Android version" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    "Device model" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    "Theme" to "${settings.themeStyle.name.lowercase(Locale.US)} · ${settings.theme.name.lowercase(Locale.US)}",
    "Current track format" to (track?.formatSummary()?.takeIf { it.isNotBlank() } ?: "No track playing"),
)

private fun buildDiagnosticReport(
    header: List<Pair<String, String>>,
    checks: List<DiagnosticCheck>,
    entries: List<LogEntry>,
): String = buildString {
    appendLine("AURALIS diagnostics")
    header.forEach { (label, value) -> appendLine("$label: $value") }
    appendLine()
    appendLine("What's not working")
    checks.forEach { check ->
        val label = when (check.level) {
            DiagnosticLevel.OK -> "OK"
            DiagnosticLevel.WARN -> "WARN"
            DiagnosticLevel.ERROR -> "ERROR"
        }
        appendLine("[$label] ${check.title}: ${check.reason}")
    }
    appendLine()
    appendLine("Logs (${entries.size} most recent, newest first)")
    entries.take(500).forEach { entry ->
        appendLine("${entry.timestamp} ${entry.level}/${entry.tag}: ${entry.message}")
        entry.stackTrace?.lineSequence()?.forEach { line -> appendLine("  $line") }
    }
}

private fun collectChecks(
    context: Context,
    sources: List<MusicSource>,
    entries: List<LogEntry>,
): List<DiagnosticCheck> {
    val checks = mutableListOf<DiagnosticCheck>()

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val audioGranted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    checks += DiagnosticCheck(
        "Storage / audio permission",
        if (audioGranted) DiagnosticLevel.OK else DiagnosticLevel.ERROR,
        if (audioGranted) "Library scanning is allowed." else "Denied — the library cannot be scanned. Grant it in system settings.",
    )

    if (Build.VERSION.SDK_INT >= 33) {
        val notificationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        checks += DiagnosticCheck(
            "Notification permission",
            if (notificationGranted) DiagnosticLevel.OK else DiagnosticLevel.WARN,
            if (notificationGranted) "The playback notification can be shown."
            else "Not granted — the playback notification may be hidden.",
        )
    } else {
        checks += DiagnosticCheck("Notification permission", DiagnosticLevel.OK, "Not required before Android 13.")
    }

    if (Build.VERSION.SDK_INT >= 31) {
        val bluetoothGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        checks += DiagnosticCheck(
            "BLUETOOTH_CONNECT",
            if (bluetoothGranted) DiagnosticLevel.OK else DiagnosticLevel.WARN,
            if (bluetoothGranted) "Bluetooth device details are available."
            else "Not granted — Bluetooth output names may be limited; playback still works.",
        )
    } else {
        checks += DiagnosticCheck("BLUETOOTH_CONNECT", DiagnosticLevel.OK, "Not required before Android 12.")
    }

    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val ignoringBattery = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()
    checks += DiagnosticCheck(
        "Battery optimization",
        when (ignoringBattery) {
            true -> DiagnosticLevel.OK
            false -> DiagnosticLevel.WARN
            null -> DiagnosticLevel.WARN
        },
        when (ignoringBattery) {
            true -> "AURALIS is exempt from battery optimization."
            false -> "Optimized — background playback or scanning may be delayed."
            null -> "Battery optimization state could not be read."
        },
    )

    val linked = sources.filter { it.treeUri != null }
    if (linked.isEmpty()) {
        checks += DiagnosticCheck(
            "Music folder accessibility",
            DiagnosticLevel.WARN,
            "No music folders added. Add one in Settings to scan a folder.",
        )
    } else {
        val unreadable = linked.count { source ->
            runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(source.treeUri))?.canRead() == true
            }.getOrDefault(false).not()
        }
        checks += DiagnosticCheck(
            "Music folder accessibility",
            if (unreadable == 0) DiagnosticLevel.OK else DiagnosticLevel.ERROR,
            if (unreadable == 0) "${linked.size} linked folder${if (linked.size == 1) "" else "s"} readable."
            else "$unreadable linked folder${if (unreadable == 1) "" else "s"} can no longer be read.",
        )
    }

    val playerErrors = entries.count { it.isError && (it.tag == "Playback" || it.tag == "Decoder") }
    checks += DiagnosticCheck(
        "Player / decoder errors",
        if (playerErrors == 0) DiagnosticLevel.OK else DiagnosticLevel.ERROR,
        if (playerErrors == 0) "No playback or decoder errors in the current log."
        else "$playerErrors recent playback or decoder error${if (playerErrors == 1) "" else "s"} — see the log below.",
    )

    val effects = AudioEffectsEngine.current()
    checks += when {
        !effects.attached -> DiagnosticCheck(
            "Device EQ support",
            DiagnosticLevel.WARN,
            "Start playback to attach and verify the device equalizer.",
        )
        effects.equalizerAvailable -> DiagnosticCheck(
            "Device EQ support",
            DiagnosticLevel.OK,
            "Hardware equalizer attached to the active audio session.",
        )
        else -> DiagnosticCheck(
            "Device EQ support",
            DiagnosticLevel.ERROR,
            "The device equalizer is not available for this audio session.",
        )
    }

    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val online = runCatching {
        val network = connectivity?.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)
    checks += DiagnosticCheck(
        "Network for lyrics",
        if (online) DiagnosticLevel.OK else DiagnosticLevel.WARN,
        if (online) "Internet is available for online lyrics."
        else "Offline — only local lyrics are available.",
    )

    return checks
}

private fun shareReport(context: Context, report: String) {
    runCatching {
        val directory = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(directory, "AURALIS-diagnostics.txt")
        file.writeText(report)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AURALIS diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("AURALIS diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
    }
}
