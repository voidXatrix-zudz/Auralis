package com.auralis.app.data.youtube

/**
 * One entry returned by YouTube Music's suggestion endpoint (`music/get_search_suggestions`).
 *
 * Called with an EMPTY input the endpoint answers with the account's recent searches: each is a
 * `historySuggestionRenderer`, and it is the only place YouTube exposes that history (there is no
 * "list my search history" browse page). Typed completions come back as `searchSuggestionRenderer`
 * instead and are not history.
 *
 * A history entry carries a [feedbackToken]; sending it to the `feedback` endpoint removes the term
 * from the account's search history, which is how deleting a recent search is mirrored back.
 */
data class YtSearchSuggestion(
    val text: String,
    /** True for a `historySuggestionRenderer`, i.e. a real recent search rather than a completion. */
    val fromHistory: Boolean,
    /** Only set for history entries; the token the `feedback` endpoint needs to delete the term. */
    val feedbackToken: String? = null,
)
