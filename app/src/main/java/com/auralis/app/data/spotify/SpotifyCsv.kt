package com.auralis.app.data.spotify

/** One Spotify track row parsed from an Exportify CSV. */
data class SpotifyRow(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/**
 * Minimal RFC-4180 CSV reader tuned for Exportify exports: quoted fields may contain commas and
 * doubled quotes, rows may be CRLF, and the file may start with a UTF-8 BOM.
 */
object SpotifyCsv {

    fun parse(text: String): List<SpotifyRow> {
        val rows = splitRows(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        val titleIdx = header.indexOfFirst { it == "track name" }
        val artistIdx = header.indexOfFirst { it == "artist name(s)" || it == "artist name" || it == "artists" }
        val albumIdx = header.indexOfFirst { it == "album name" || it == "album" }
        val durationIdx = header.indexOfFirst { it == "duration (ms)" || it == "duration ms" || it == "duration" }
        if (titleIdx < 0) return emptyList()

        return rows.drop(1).mapNotNull { cells ->
            val title = cells.getOrNull(titleIdx)?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val artist = cells.getOrNull(artistIdx)?.trim().orEmpty()
            val album = cells.getOrNull(albumIdx)?.trim().orEmpty()
            val duration = cells.getOrNull(durationIdx)?.trim()?.toLongOrNull() ?: 0L
            SpotifyRow(title, artist, album, duration.coerceAtLeast(0L))
        }
    }

    /** Splits the whole document into rows of raw (still-quoted) cells. */
    private fun splitRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
                c == '"' -> quoted = true
                c == ',' -> { row.add(field.toString()); field.setLength(0) }
                c == '\r' -> { /* handled with the following \n */ }
                c == '\n' -> {
                    row.add(field.toString()); field.setLength(0)
                    rows.add(row); row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows.filter { it.isNotEmpty() && it.any { cell -> cell.isNotBlank() } }
    }
}
