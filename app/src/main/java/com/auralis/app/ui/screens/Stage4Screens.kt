package com.auralis.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.auralis.app.data.db.PlayEventEntity
import com.auralis.app.data.metadata.TagData
import com.auralis.app.domain.audio.EqPresets
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.Track
import com.auralis.app.domain.stats.WrappedStats
import com.auralis.app.playback.AudioEffectsEngine
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.components.ScreenPadding
import com.auralis.app.ui.components.SectionTitle
import com.auralis.app.ui.theme.Auralis
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit, modifier: Modifier = Modifier) {
    val effects by AudioEffectsEngine.state.collectAsStateWithLifecycle()
    val bands = effects.bands
    val eqAvailable = effects.equalizerAvailable
    val bassAvailable = effects.bassAvailable && effects.bassStrengthSupported
    val virtualizerAvailable = effects.virtualizerAvailable && effects.virtualizerStrengthSupported
    // Local drag values keep the sliders smooth; the engine is updated on every move but only
    // emits its coarse state on structural changes. Keying on the band layout (not the live level
    // list) means preset/level updates do not reset the map mid-interaction.
    val bandLayout = bands.map { it.index to it.centerHz }
    val bandLevels = remember(bandLayout, settings.eqBandCount, settings.eqBandLevels) {
        mutableStateMapOf<Int, Float>().apply {
            bands.forEach { band ->
                val saved = if (settings.eqBandCount == bands.size) {
                    settings.eqBandLevels.getOrNull(band.index)
                } else {
                    null
                }
                put(band.index, (saved ?: band.levelMillibels).toFloat())
            }
        }
    }
    var bassDb by remember(settings.bassDb) { mutableFloatStateOf(settings.bassDb) }
    var virtualizerStrength by remember(settings.virtualizerStrength) { mutableFloatStateOf(settings.virtualizerStrength.toFloat()) }
    var preampDb by remember(settings.preampDb) { mutableFloatStateOf(settings.preampDb) }

    val resetAll: () -> Unit = {
        val levels = AudioEffectsEngine.applyPreset("Flat")
        bands.forEach { band -> bandLevels[band.index] = levels.getOrElse(band.index) { 0 }.toFloat() }
        AudioEffectsEngine.setPreampDb(0f)
        preampDb = 0f
        AudioEffectsEngine.setBassEnabled(false)
        bassDb = 0f
        AudioEffectsEngine.setVirtualizerEnabled(false)
        virtualizerStrength = 0f
        onUpdate {
            it.copy(
                eqPreset = "Flat",
                eqGains = EqPresets.gains("Flat"),
                eqBandLevels = levels,
                eqBandCount = bands.size,
                preampDb = 0f,
                bassBoostEnabled = false,
                bassDb = 0f,
                virtualizerEnabled = false,
                virtualizerStrength = 0,
            )
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = ScreenPadding) {
        item { Text("Equalizer", style = MaterialTheme.typography.headlineSmall) }

        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Master EQ", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (eqAvailable) "${bands.size}-band device equalizer · session ${effects.sessionId}"
                        else "Start playback to attach the real audio session",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.eqEnabled,
                    onCheckedChange = { enabled ->
                        AudioEffectsEngine.setEnabled(enabled)
                        onUpdate { it.copy(eqEnabled = enabled) }
                    },
                    enabled = eqAvailable,
                )
            }
            effects.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (eqAvailable) {
            item { SectionTitle("Presets") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(EqPresets.names) { name ->
                        FilterChip(
                            selected = settings.eqPreset == name,
                            onClick = {
                                val levels = AudioEffectsEngine.applyPreset(name)
                                bands.forEach { band ->
                                    bandLevels[band.index] = levels.getOrElse(band.index) { 0 }.toFloat()
                                }
                                onUpdate {
                                    it.copy(
                                        eqPreset = name,
                                        eqGains = EqPresets.gains(name),
                                        eqBandLevels = levels,
                                        eqBandCount = bands.size,
                                    )
                                }
                            },
                            label = { Text(name) },
                            shape = CircleShape,
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Bands (dB)", Modifier.weight(1f))
                    TextButton(onClick = resetAll) { Text("Reset") }
                }
            }
            items(bands, key = { it.index }) { band ->
                val level = bandLevels[band.index] ?: band.levelMillibels.toFloat()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .alpha(if (settings.eqEnabled) 1f else 0.45f),
                ) {
                    Row {
                        Text(formatBandFrequency(band.centerHz), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(formatDb(level / 100f), style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = level,
                        onValueChange = { value ->
                            bandLevels[band.index] = value
                            AudioEffectsEngine.setBandLevel(band.index, value.roundToInt())
                        },
                        onValueChangeFinished = {
                            val levels = bands.map { it.index }.map { index ->
                                bandLevels[index]?.roundToInt() ?: 0
                            }
                            onUpdate {
                                it.copy(
                                    eqPreset = EqPresets.CUSTOM,
                                    eqBandLevels = levels,
                                    eqBandCount = bands.size,
                                )
                            }
                        },
                        valueRange = band.minMillibels.toFloat()..band.maxMillibels.toFloat(),
                        enabled = settings.eqEnabled,
                    )
                }
            }
            item { SectionTitle("Output") }
            item {
                Column(Modifier.alpha(if (settings.eqEnabled) 1f else 0.45f)) {
                    Row {
                        Text("Pre-amp", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(formatDb(preampDb), style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = preampDb,
                        onValueChange = { value ->
                            preampDb = value
                            AudioEffectsEngine.setPreampDb(value)
                        },
                        onValueChangeFinished = { onUpdate { it.copy(preampDb = preampDb) } },
                        valueRange = 0f..12f,
                        enabled = settings.eqEnabled,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Limiter (anti-clip)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = settings.limiterEnabled,
                            onCheckedChange = { enabled ->
                                AudioEffectsEngine.setLimiterEnabled(enabled)
                                onUpdate { it.copy(limiterEnabled = enabled) }
                            },
                            enabled = settings.eqEnabled && effects.limiterAvailable,
                        )
                    }
                }
            }
        }

        item { SectionTitle("Bass Boost") }
        item {
            Column(Modifier.alpha(if (settings.bassBoostEnabled && bassAvailable) 1f else 0.55f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f))
                    Switch(
                        checked = settings.bassBoostEnabled,
                        onCheckedChange = { enabled ->
                            AudioEffectsEngine.setBassEnabled(enabled)
                            onUpdate { it.copy(bassBoostEnabled = enabled) }
                        },
                        enabled = bassAvailable,
                    )
                }
                if (!bassAvailable) {
                    Text(
                        "Not supported by this device's audio hardware",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = bassDb,
                    onValueChange = { value ->
                        bassDb = value
                        AudioEffectsEngine.setBassDb(value)
                    },
                    onValueChangeFinished = { onUpdate { it.copy(bassDb = bassDb) } },
                    valueRange = 0f..12f,
                    enabled = bassAvailable && settings.bassBoostEnabled,
                )
                Text(
                    "Strength ${formatDb(bassDb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionTitle("Virtualizer") }
        item {
            Column(Modifier.alpha(if (settings.virtualizerEnabled && virtualizerAvailable) 1f else 0.55f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f))
                    Switch(
                        checked = settings.virtualizerEnabled,
                        onCheckedChange = { enabled ->
                            AudioEffectsEngine.setVirtualizerEnabled(enabled)
                            onUpdate { it.copy(virtualizerEnabled = enabled) }
                        },
                        enabled = virtualizerAvailable,
                    )
                }
                if (!virtualizerAvailable) {
                    Text(
                        "Not supported by this device's audio hardware",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = virtualizerStrength,
                    onValueChange = { value ->
                        virtualizerStrength = value
                        AudioEffectsEngine.setVirtualizerStrength(value.roundToInt())
                    },
                    onValueChangeFinished = { onUpdate { it.copy(virtualizerStrength = virtualizerStrength.roundToInt()) } },
                    valueRange = 0f..1000f,
                    enabled = virtualizerAvailable && settings.virtualizerEnabled,
                )
                Text(
                    "Strength ${(virtualizerStrength / 10f).roundToInt()}% · works best with headphones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatBandFrequency(hz: Int): String =
    if (hz >= 1_000) "${(hz / 100f).roundToInt() / 10f} kHz" else "$hz Hz"

private fun formatDb(db: Float): String = String.format(java.util.Locale.US, "%+.1f dB", db)

@Composable
fun WrappedScreen(events: List<PlayEventEntity>, statsFor: (Int, Int?) -> WrappedStats, modifier: Modifier = Modifier) {
    val calendar = remember { Calendar.getInstance() }
    val years = remember(events) { events.map { e -> calendar.timeInMillis = e.playedAt; calendar.get(Calendar.YEAR) }.distinct().sortedDescending() }
    var year by rememberSaveable { mutableIntStateOf(years.firstOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)) }
    var month by rememberSaveable { mutableStateOf<Int?>(null) }
    if (years.isNotEmpty() && year !in years) year = years.first()
    val stats = remember(year, month, events) { statsFor(year, month) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = ScreenPadding) {
        item {
            Text("AURALIS Wrapped", style = MaterialTheme.typography.headlineSmall)
            LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(years.ifEmpty { listOf(year) }) { y -> FilterChip(selected = year == y, onClick = { year = y; month = null }, label = { Text(y.toString()) }, shape = CircleShape) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = month == null, onClick = { month = null }, label = { Text("Year") }, shape = CircleShape) }
                items((1..12).toList()) { m -> FilterChip(selected = month == m, onClick = { month = m }, label = { Text(monthName(m)) }, shape = CircleShape) }
            }
        }
        if (events.isEmpty()) {
            item { Text("No listening statistics yet. Play music and your recap will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Plays", stats.totalPlays.toString(), Modifier.weight(1f))
                    StatCard("Time", formatHours(stats.listenedMs), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Tracks", stats.uniqueTracks.toString(), Modifier.weight(1f))
                    StatCard("Artists", stats.uniqueArtists.toString(), Modifier.weight(1f))
                }
            }
            item { SectionTitle("Top tracks") }
            items(stats.topTracks) { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtworkImage(entry.track, Modifier.size(46.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(entry.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(entry.track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    Text("${entry.plays}×", color = MaterialTheme.colorScheme.primary)
                }
            }
            item { SectionTitle("Top artists") }
            items(stats.topArtists) { artist -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text(artist.name, Modifier.weight(1f)); Text("${artist.plays} plays", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun monthName(month: Int) = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").getOrElse(month - 1) { "" }

private fun formatHours(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

@Composable
fun MetadataEditorScreen(
    track: Track,
    readTags: suspend (Track) -> Result<TagData>,
    writeTags: suspend (Track, TagData) -> Result<Unit>,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    remote: Boolean = false,
    metadataCandidates: List<com.auralis.app.data.metadata.MetadataCandidate> = emptyList(),
    metadataLoading: Boolean = false,
    onFetchMetadata: (Track) -> Unit = {},
    onClearCandidates: () -> Unit = {},
    onSaveRemoteMetadata: (Track, com.auralis.app.data.metadata.MetadataCandidate) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tag by remember(track.id) { mutableStateOf<TagData?>(null) }
    var loading by remember(track.id) { mutableStateOf(true) }
    var error by remember(track.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var trackNo by remember { mutableStateOf("") }
    var discNo by remember { mutableStateOf("") }
    var composer by remember { mutableStateOf("") }
    var artwork by remember { mutableStateOf<ByteArray?>(null) }
    var removeArtwork by remember { mutableStateOf(false) }
    var pendingWrite by remember { mutableStateOf<TagData?>(null) }

    fun applyLoaded(loaded: TagData) {
        title = loaded.title; artist = loaded.artist; album = loaded.album; albumArtist = loaded.albumArtist
        genre = loaded.genre; year = loaded.year?.toString().orEmpty(); trackNo = loaded.trackNumber?.toString().orEmpty()
        discNo = loaded.discNumber?.toString().orEmpty(); composer = loaded.composer; artwork = loaded.artwork; removeArtwork = false
    }
    suspend fun doSave(value: TagData) {
        saving = true; error = null
        writeTags(track, value).onSuccess { onSaved() }.onFailure { error = it.localizedMessage ?: "Could not save tags"; saving = false }
    }

    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                .onSuccess { bytes -> if (bytes != null && bytes.size <= 12 * 1024 * 1024) { artwork = bytes; removeArtwork = false } else error = "Artwork must be smaller than 12 MB" }
                .onFailure { error = it.localizedMessage }
        }
    }
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pending = pendingWrite; pendingWrite = null
        if (result.resultCode == Activity.RESULT_OK && pending != null) scope.launch { doSave(pending) }
        else { saving = false; error = "Write permission was not granted" }
    }
    fun save() {
        val base = tag ?: return
        val value = base.copy(title = title, artist = artist, album = album, albumArtist = albumArtist, genre = genre,
            year = year.toIntOrNull(), trackNumber = trackNo.toIntOrNull(), discNumber = discNo.toIntOrNull(),
            composer = composer, artwork = artwork, removeArtwork = removeArtwork)
        if (Build.VERSION.SDK_INT >= 29 && track.uri.startsWith("content://media/")) {
            runCatching { MediaStore.createWriteRequest(context.contentResolver, listOf(Uri.parse(track.uri))) }
                .onSuccess { sender -> pendingWrite = value; saving = true; writeRequest.launch(IntentSenderRequest.Builder(sender.intentSender).build()) }
                .onFailure { error = it.localizedMessage }
        } else scope.launch { doSave(value) }
    }

    LaunchedEffect(track.id) {
        loading = true
        if (remote) {
            tag = TagData()
            loading = false
        } else {
            readTags(track).onSuccess { applyLoaded(it); tag = it; loading = false }
                .onFailure { error = it.localizedMessage ?: "Could not read tags"; loading = false }
        }
    }

    fun applyCandidate(candidate: com.auralis.app.data.metadata.MetadataCandidate) {
        if (candidate.title.isNotBlank()) title = candidate.title
        if (candidate.artists.isNotBlank()) artist = candidate.artists
        if (candidate.album.isNotBlank()) album = candidate.album
        if (candidate.albumArtist.isNotBlank()) albumArtist = candidate.albumArtist
        if (candidate.genre.isNotBlank()) genre = candidate.genre
        candidate.year?.let { year = it.toString() }
        candidate.trackNumber?.let { trackNo = it.toString() }
        // Download the cover into the editor so Save embeds it (local) — off the main thread.
        val cover = candidate.coverUrl
        if (!cover.isNullOrBlank()) scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { java.net.URL(cover).openStream().use { it.readBytes() } }.getOrNull()
            }
            if (bytes != null && bytes.size <= 12 * 1024 * 1024) { artwork = bytes; removeArtwork = false }
        }
    }

    if (loading) Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else if (tag == null) Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(error ?: "Tags are unavailable for this file.", color = MaterialTheme.colorScheme.error) }
    else Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Edit tags", style = MaterialTheme.typography.headlineSmall)
        Text(track.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // "Fetch metadata": candidates from MusicBrainz/Deezer/iTunes (Last.fm adds genre).
        OutlinedButton(onClick = { onFetchMetadata(track) }, enabled = !metadataLoading, modifier = Modifier.fillMaxWidth()) {
            if (metadataLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.CloudDownload, null)
            Text(if (metadataCandidates.isEmpty()) "Fetch metadata" else "Search again", Modifier.padding(start = 8.dp))
        }
        if (metadataCandidates.isNotEmpty()) {
            Text("Tap a match to fill the fields" + if (remote) ", then Save." else ".", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            metadataCandidates.forEach { candidate ->
                MetadataCandidateRow(candidate) {
                    if (remote) { onSaveRemoteMetadata(track, candidate); onSaved() }
                    else applyCandidate(candidate)
                }
            }
            TextButton(onClick = onClearCandidates) { Text("Hide matches") }
        }

        if (remote) {
            Text(
                "YouTube songs keep an in-app metadata override (used in the player and notification); the stream is never modified.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (metadataCandidates.isEmpty()) {
                Text("Fetch metadata to pick a match, or close this screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        val preview = remember(artwork) { artwork?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                if (preview != null) Image(preview.asImageBitmap(), contentDescription = "Embedded artwork", modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.Album, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { artworkPicker.launch(arrayOf("image/*")) }) { Text(if (preview == null) "Add artwork" else "Replace artwork") }
                if (preview != null) TextButton(onClick = { artwork = null; removeArtwork = true }) { Text("Remove artwork") }
            }
        }
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text("Album artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Year") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(trackNo, { trackNo = it.filter(Char::isDigit).take(4) }, label = { Text("Track") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(discNo, { discNo = it.filter(Char::isDigit).take(2) }, label = { Text("Disc") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(composer, { composer = it }, label = { Text("Composer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save tags")
        }
        Text("The original file is backed up in app cache and restored if the write fails. Rescan afterwards to refresh the library.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MetadataCandidateRow(
    candidate: com.auralis.app.data.metadata.MetadataCandidate,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (!candidate.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = candidate.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(Icons.Rounded.Album, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(candidate.title.ifBlank { "Unknown title" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = listOfNotNull(
                    candidate.artists.takeIf { it.isNotBlank() },
                    candidate.album.takeIf { it.isNotBlank() },
                    candidate.year?.toString(),
                ).joinToString(" · ")
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(candidate.source + if (candidate.genre.isNotBlank()) " · ${candidate.genre}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun BackupScreen(onExport: (Uri) -> Unit, onRestore: (Uri) -> Unit, modifier: Modifier = Modifier) {
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(onExport) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { pendingRestore = it } }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backup & restore", style = MaterialTheme.typography.headlineSmall)
        Text("Versioned JSON backup of settings, playlists, favorites, history, lyrics and listening events. Music files are never copied.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { create.launch("AURALIS-backup-${System.currentTimeMillis()}.json") }, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Icon(Icons.Rounded.Save, null); Text("Create backup", Modifier.padding(start = 8.dp)) }
        OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Icon(Icons.Rounded.Restore, null); Text("Restore backup", Modifier.padding(start = 8.dp)) }
        Text("Restore merges data and skips references to tracks that are not on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    pendingRestore?.let { uri -> AlertDialog(
        onDismissRequest = { pendingRestore = null },
        title = { Text("Restore this backup?") },
        text = { Text("Playlists, favorites, history, lyrics and settings will be merged with the current data.") },
        confirmButton = { TextButton(onClick = { onRestore(uri); pendingRestore = null }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
    ) }
}
