package com.auralis.app.data.youtube

/**
 * Success/failure wrapper for account calls whose failure must never look like "nothing there".
 *
 * A library fetch that fails is not the same as an account with zero playlists: the first needs a
 * retry row, the second a normal empty state. [Failure] carries the HTTP status (0 when the request
 * never completed) so the UI can tell a rejected session from a network problem.
 */
sealed interface YouTubeLoadResult<out T> {
    data class Success<T>(val value: T) : YouTubeLoadResult<T>
    data class Failure(val reason: String, val code: Int = 0) : YouTubeLoadResult<Nothing>

    val isFailure: Boolean get() = this is Failure
}

/** True when the failure was a rejected session rather than a network/parse problem. */
val YouTubeLoadResult.Failure?.unauthorized: Boolean
    get() = this?.code == 401 || this?.code == 403

/** One endpoint's outcome for Settings → "Test YouTube connection". Never contains a secret. */
data class YouTubeEndpointCheck(
    /** `account_menu`, `liked_playlists`, `liked_songs`. */
    val label: String,
    val ok: Boolean,
    val code: Int,
    /** Items the response parsed to; 0 for endpoints that only prove the session. */
    val parsed: Int,
) {
    /** The single line the Settings card shows. */
    fun display(): String =
        "$label  ${if (ok) "OK" else "FAIL"}  HTTP $code" +
            if (parsed > 0) "  parsed: $parsed" else ""
}