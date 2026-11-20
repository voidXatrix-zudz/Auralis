package com.auralis.app.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.auralis.app.AppContainer
import com.auralis.app.data.db.AuralisDatabase
import com.auralis.app.ui.theme.AuralisAppTheme
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shown instead of the app shell when the local Room database cannot be opened (a failed migration
 * or a corrupt file). It offers the only two safe outs: export the raw database for support, or
 * reset the local library. Nothing here reaches into playback, YouTube or the network — only the
 * app's own database files are touched, and only after an explicit confirmation.
 */
@Composable
fun DatabaseRecoveryScreen(container: AppContainer, failure: Throwable) {
    AuralisAppTheme(darkTheme = true, accentName = "Teal") {
        val context = LocalContext.current
        var confirmingReset by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }

        val exporter = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri != null) {
                val saved = runCatching { zipDatabaseFiles(context, uri) }.getOrDefault(false)
                status = if (saved) "Database backup saved." else "There was no database file to copy."
            }
        }

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Local database problem", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "AURALIS could not open the on-device library database, so your music, playlists " +
                        "and history can't be loaded right now. Nothing has been deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${failure.javaClass.name}: ${failure.message.orEmpty()}".trim(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { runCatching { exporter.launch("AURALIS-database-backup.zip") } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export backup") }
                Text(
                    "Saves a copy of the database file so you can keep it before resetting.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { confirmingReset = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset local database") }
                Text(
                    "Deletes the local database and starts an empty library. Files already on the " +
                        "device are not touched.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (confirmingReset) {
            AlertDialog(
                onDismissRequest = { confirmingReset = false },
                title = { Text("Reset local database?") },
                text = {
                    Text("This deletes the local library, playlists and history on this device and cannot be undone.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingReset = false
                        container.resetDatabase()
                        // Rebuild the whole screen tree so a fresh, working database is created.
                        (context as? Activity)?.recreate()
                    }) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingReset = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/** Zips the main database file (and any -wal/-shm beside it) to the chosen location. */
private fun zipDatabaseFiles(context: Context, target: Uri): Boolean {
    val base = context.getDatabasePath(AuralisDatabase.DB_NAME)
    val files = listOf(base, File("${base.path}-wal"), File("${base.path}-shm"))
        .filter { it.exists() && it.length() > 0 }
    if (files.isEmpty()) return false
    val out = context.contentResolver.openOutputStream(target) ?: return false
    out.use {
        ZipOutputStream(BufferedOutputStream(it)).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
    return true
}
