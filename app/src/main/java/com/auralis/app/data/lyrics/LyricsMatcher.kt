package com.auralis.app.data.lyrics

/**
 * Text normalisation and similarity for lyrics matching (Part G) and query generation (Part J).
 *
 * Titles are cleaned of edit markers ("(Slowed)", "(Official Video)", "- Remastered",
 * "(From \"...\")", "feat. …"), artists are reduced to the first name of "A, B & C", and
 * similarity is token based so word order and punctuation do not matter.
 */
object LyricsMatcher {
    private val BRACKET_NOISE = Regex(
        "\\((?:slowed|sped\\s*up|speed\\s*up|nightcore|lyric\\s*video|official\\s*(?:video|audio|music\\s*video)?|" +
            "audio|hd|hq|4k|explicit|from\\s*[\"“].*?[\"”]|feat\\..*?|ft\\..*?|with\\s+.*?)\\)",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKET_MOVIE = Regex("\\(from\\s+[\"“].*?[\"”]\\)", RegexOption.IGNORE_CASE)
    private val DASH_SUFFIX = Regex(
        "\\s*[-–]\\s*(remaster(?:ed)?|remaster(?:ed)?\\s*\\d{4}|slowed|sped\\s*up|nightcore|official\\s*(?:video|audio).*)$",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("\\s*[(\\[]?\\s*(?:feat|ft|featuring|with)\\.?\\s+[^)\\]]*[)\\]]?", RegexOption.IGNORE_CASE)
    private val PUNCT = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")
    private val SLOWED_SPED = Regex(
        "slowed|sped\\s*up|speed\\s*up|nightcore",
        RegexOption.IGNORE_CASE,
    )

    /** Strips edit/format noise from a title while keeping original scripts (Devanagari, Bengali, …). */
    fun cleanTitle(raw: String): String {
        var value = raw
        value = BRACKET_MOVIE.replace(value, " ")
        value = BRACKET_NOISE.replace(value, " ")
        value = DASH_SUFFIX.replace(value, "")
        value = FEAT.replace(value, " ")
        return value.replace(SPACES, " ").trim()
    }

    /** The first artist of "A, B & C" / "A feat. B" — the one used for search queries. */
    fun primaryArtist(raw: String): String {
        val withoutFeat = FEAT.replace(raw, " ")
        val first = withoutFeat
            .split(',', '&', '/', ';', '·', '|', '×')
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return first.replace(SPACES, " ").trim()
    }

    /** Lowercase, punctuation-free, whitespace-collapsed form used for comparison. */
    fun normalize(value: String): String =
        PUNCT.replace(value.lowercase(), " ").replace(SPACES, " ").trim()

    /** Token-set similarity in 0..1 (order independent), with a small prefix boost. */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val ta = na.split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = nb.split(' ').filter { it.isNotEmpty() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val overlap = ta.intersect(tb).size.toDouble()
        val score = (2 * overlap) / (ta.size + tb.size)
        // A leading-token match nudges near-misses over the line without accepting random songs.
        val prefix = if (ta.first() == tb.first()) 0.1 else 0.0
        return (score + prefix).coerceAtMost(1.0)
    }

    /** True when a candidate clearly refers to the requested song. */
    fun matches(
        requestedTitle: String,
        requestedArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        requestedDurationMs: Long?,
        candidateDurationMs: Long?,
        durationToleranceMs: Long = 3_000L,
    ): Boolean {
        val titleScore = maxOf(
            similarity(requestedTitle, candidateTitle),
            similarity(cleanTitle(requestedTitle), cleanTitle(candidateTitle)),
        )
        if (titleScore < 0.8) return false
        val wantedArtist = primaryArtist(requestedArtist)
        val gotArtist = primaryArtist(candidateArtist)
        val artistOk = wantedArtist.isBlank() || gotArtist.isBlank() ||
            similarity(wantedArtist, gotArtist) >= 0.5 ||
            normalize(candidateArtist).contains(normalize(wantedArtist))
        if (!artistOk) return false
        if (requestedDurationMs != null && requestedDurationMs > 0 &&
            candidateDurationMs != null && candidateDurationMs > 0
        ) {
            if (kotlin.math.abs(requestedDurationMs - candidateDurationMs) > durationToleranceMs) return false
        }
        return true
    }

    /** True when a title advertises a slowed/sped-up/nightcore edit (Part J). */
    fun isSlowedOrSped(raw: String): Boolean = SLOWED_SPED.containsMatchIn(raw)

    /** One query form tried in order: most specific first. */
    data class QueryVariant(val title: String, val artist: String)

    /** Query variants in the Part J order: exact, first artist, cleaned titles, then title only. */
    fun variants(title: String, artist: String): List<QueryVariant> {
        val primary = primaryArtist(artist)
        val cleaned = cleanTitle(title)
        return buildList {
            add(QueryVariant(title, artist))
            if (primary.isNotBlank() && primary != artist) add(QueryVariant(title, primary))
            if (cleaned != title) {
                add(QueryVariant(cleaned, primary.ifBlank { artist }))
                add(QueryVariant(cleaned, ""))
            }
            add(QueryVariant(title, ""))
        }.distinct()
    }
}
