package com.auralis.app.data.youtube

import com.auralis.app.logging.SessionLogSanitizer
import com.auralis.app.logging.YouTubeSessionLog

/** What one self-heal pass did, so the caller can surface "reconnected" only on a real recovery. */
enum class SessionHealOutcome { NOT_NEEDED, RECOVERED, STILL_EXPIRED, THROTTLED }

/**
 * Makes a session flagged `expired` heal itself.
 *
 * 1. `account/account_menu` with the stored cookie (even while flagged). 2xx clears the flag.
 * 2. Otherwise one invisible-WebView refresh; a fresh signing cookie clears it.
 * 3. Only when both fail does it stay expired, and the next automatic attempt waits
 *    [BACKOFF_MS]. Nothing here ever clears the cookie or blocks the UI.
 *
 * All Android dependencies are behind seams so the rules are unit-tested on the JVM.
 */
class SessionHealer(
    private val snapshot: () -> YouTubeAuth?,
    private val persist: (YouTubeAuth) -> Unit,
    private val probe: suspend () -> Int,
    private val refresh: suspend () -> YouTubeAuth?,
    private val lastFailureAt: () -> Long,
    private val recordFailureAt: (Long) -> Unit,
    private val clearFailure: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val record: (String) -> Unit = { YouTubeSessionLog.record("heal", it) },
) {
    /** Re-checks a flagged session, or refreshes it once. Safe to call on every launch/page open. */
    suspend fun heal(force: Boolean = false): SessionHealOutcome {
        val stored = snapshot() ?: run {
            record("skipped: no stored session")
            return SessionHealOutcome.NOT_NEEDED
        }
        if (!stored.expired) return SessionHealOutcome.NOT_NEEDED

        val now = clock()
        if (!force) {
            val previous = lastFailureAt()
            if (previous > 0L && now - previous in 0 until BACKOFF_MS) {
                val minutes = (BACKOFF_MS - (now - previous)) / 60_000L
                record("skipped: backoff, ~${minutes}m left")
                return SessionHealOutcome.THROTTLED
            }
        }

        record(
            "started: probe account_menu (signingCookie=" +
                "${if (hasSigningCookie(stored.cookie)) "yes" else "no"}, " +
                "age=${ageMinutes(now, stored.lastRefreshedAt)})",
        )
        val status = runCatching { probe() }.getOrDefault(0)
        if (status in 200..299) {
            persist(stored.copy(expired = false, lastRefreshedAt = now))
            clearFailure()
            record("OK: account_menu HTTP $status, expired cleared")
            return SessionHealOutcome.RECOVERED
        }

        record("probe HTTP $status -> silent refresh")
        val fresh = runCatching { refresh() }.getOrNull()
        if (fresh != null && hasSigningCookie(fresh.cookie)) {
            persist(mergeProfile(stored, fresh).copy(expired = false, lastRefreshedAt = now))
            clearFailure()
            record(
                "OK: silent refresh captured cookies=[" +
                    SessionLogSanitizer.cookieNames(fresh.cookie).joinToString(",") + "]",
            )
            return SessionHealOutcome.RECOVERED
        }

        recordFailureAt(now)
        record("failed: probe HTTP $status and silent refresh failed; retry in 30m")
        return SessionHealOutcome.STILL_EXPIRED
    }

    /**
     * Refreshes a still-valid session whose [YouTubeAuth.lastRefreshedAt] is older than
     * [STALE_AFTER_MS]. This is the "app start / foreground" trigger: Google rotates the
     * `*PSIDTS` family while the app is closed, so replaying a six-hour-old snapshot can be refused
     * even though the browser jar is still logged in.
     *
     * Never blocks the UI, never runs twice at once (the caller guards that), never clears
     * anything, and honours the same 30-minute backoff after a failed attempt.
     *
     * @return true only when a fresh signing cookie was captured and saved.
     */
    suspend fun refreshIfStale(reason: String = "stale"): Boolean {
        val stored = snapshot() ?: return false
        if (stored.expired) return false
        val now = clock()
        val age = if (stored.lastRefreshedAt <= 0L) Long.MAX_VALUE else now - stored.lastRefreshedAt
        if (age < STALE_AFTER_MS) return false

        val previous = lastFailureAt()
        if (previous > 0L && now - previous in 0 until BACKOFF_MS) {
            val minutes = (BACKOFF_MS - (now - previous)) / 60_000L
            record("refresh skipped ($reason): backoff, ~${minutes}m left")
            return false
        }

        val ok = refreshNow(reason)
        if (ok) {
            clearFailure()
        } else {
            recordFailureAt(clock())
        }
        return ok
    }

    /**
     * Refreshes unconditionally (used by the verified-401 path). Returns true only when a fresh
     * signing cookie was captured and saved. Never clears anything.
     */
    suspend fun refreshNow(reason: String): Boolean {
        val current = snapshot() ?: return false
        record("refresh started ($reason)")
        val startedAt = clock()
        val fresh = runCatching { refresh() }.getOrNull()
        val elapsed = clock() - startedAt
        if (fresh == null) {
            record("refresh failed: no session captured ($reason, ${elapsed} ms)")
            return false
        }
        if (!hasSigningCookie(fresh.cookie)) {
            record("refresh failed: no signing cookie ($reason, ${elapsed} ms)")
            return false
        }
        persist(mergeProfile(current, fresh).copy(expired = false, lastRefreshedAt = clock()))
        clearFailure()
        record(
            "refresh OK ($reason, ${elapsed} ms) cookies=[" +
                SessionLogSanitizer.cookieNames(fresh.cookie).joinToString(",") + "]",
        )
        return true
    }

    /** Keeps the display identity captured at sign-in; the refresh only replaces session fields. */
    private fun mergeProfile(current: YouTubeAuth, fresh: YouTubeAuth): YouTubeAuth = fresh.copy(
        name = current.name.ifBlank { fresh.name },
        email = current.email.ifBlank { fresh.email },
        avatarUrl = current.avatarUrl.ifBlank { fresh.avatarUrl },
    )

    private fun ageMinutes(now: Long, at: Long): String =
        if (at <= 0L) "never" else "${(now - at).coerceAtLeast(0L) / 60_000L}m"

    companion object {
        /** After a failed self-heal, no automatic retry for this long. */
        const val BACKOFF_MS = 30 * 60 * 1000L

        /** A stored session older than this is refreshed on app start/foreground. */
        const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L
    }
}