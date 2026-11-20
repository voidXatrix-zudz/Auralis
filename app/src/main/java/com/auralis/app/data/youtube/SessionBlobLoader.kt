package com.auralis.app.data.youtube

/**
 * Reads the encrypted session blob without ever mutating it.
 *
 * A read failure (missing Keystore key, AEAD tag mismatch, malformed Base64, bad JSON) is reported
 * by exception TYPE only, so Settings can say "Session unreadable, log in again" instead of
 * silently showing "Not connected", and the stored blob is kept for a later, working key.
 *
 * Pure Java/Kotlin so the rules are unit-tested on the JVM: Android's Keystore is behind the
 * [decrypt] seam.
 */
sealed interface SessionLoadResult {
    data class Loaded(val auth: YouTubeAuth) : SessionLoadResult
    data class Unreadable(val type: String) : SessionLoadResult
}

class SessionBlobLoader(
    private val readBlob: () -> String?,
    private val decrypt: (String) -> String?,
    private val parse: (String) -> YouTubeAuth?,
    private val record: (String) -> Unit = {},
) {
    /** Null when no blob exists (or it decodes to an empty session); never deletes anything. */
    fun load(): SessionLoadResult? {
        val blob = readBlob() ?: return null
        if (blob.isBlank()) return null
        val plain = try {
            decrypt(blob)
        } catch (error: Throwable) {
            val type = errorType(error)
            record("decrypt failed type=$type; blob kept")
            return SessionLoadResult.Unreadable(type)
        } ?: return null
        if (plain.isBlank()) return null
        return try {
            parse(plain)?.let(SessionLoadResult::Loaded)
        } catch (error: Throwable) {
            val type = errorType(error)
            record("parse failed type=$type; blob kept")
            SessionLoadResult.Unreadable(type)
        }
    }

    companion object {
        fun errorType(error: Throwable): String =
            error.javaClass.simpleName.takeIf { it.isNotBlank() } ?: "unknown"
    }
}

/**
 * Which callers may ever erase the stored YouTube session. Only the explicit Disconnect button may
 * do so. Everything else — Clerk sign-out, "Use a different account" (which only expires browser
 * cookies), the integration switch, app start, a failed request — must keep the blob so the next
 * launch can self-heal or the current account can stay usable if the chooser is cancelled.
 */
object SessionClearRules {
    const val DISCONNECT = "Disconnect button"

    fun mayClear(caller: String): Boolean = caller == DISCONNECT
}