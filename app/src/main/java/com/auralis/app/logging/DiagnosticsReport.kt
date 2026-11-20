package com.auralis.app.logging

/** One device/feature check shown on the Logs screen; shared by the screen and the exported report. */
enum class DiagnosticLevel { OK, WARN, ERROR }

data class DiagnosticCheck(
    val title: String,
    val level: DiagnosticLevel,
    val reason: String,
)

/**
 * Everything the export's "YouTube session" block needs. Cookie/secrets are represented by NAMES and
 * presence flags only — never by values.
 */
data class SessionSummary(
    val signedIn: Boolean,
    val expired: Boolean,
    val lastRefreshAge: String,
    val cookieNames: List<String>,
    val clientVersionPresent: Boolean,
    val visitorDataPresent: Boolean,
    val dataSyncIdPresent: Boolean,
    val authUser: String,
    val snapshotExists: Boolean,
    val sessionReadError: String?,
    val lastLines: List<String>,
)

/** Human-readable age for the last successful refresh, e.g. `2h 10m ago` or `never`. */
fun refreshAgeText(lastRefreshedAt: Long, now: Long = System.currentTimeMillis()): String {
    if (lastRefreshedAt <= 0L) return "never"
    val delta = (now - lastRefreshedAt).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ${minutes % 60}m ago"
        else -> "${minutes / (24 * 60)}d ${minutes % (24 * 60) / 60}h ago"
    }
}

/** The "YouTube session" section, newest-last session lines included and sanitized again on export. */
fun sessionSection(summary: SessionSummary): List<String> = buildList {
    add("YouTube session")
    add("signedIn: ${yesNo(summary.signedIn)}")
    add("expired: ${yesNo(summary.expired)}")
    add("last refresh: ${summary.lastRefreshAge}")
    add("cookie names: ${summary.cookieNames.joinToString(", ").ifBlank { "none" }}")
    add("clientVersion: ${present(summary.clientVersionPresent)}")
    add("visitorData: ${present(summary.visitorDataPresent)}")
    add("dataSyncId: ${present(summary.dataSyncIdPresent)}")
    add("authUser: ${summary.authUser.ifBlank { "0" }}")
    add("session snapshot: ${present(summary.snapshotExists)}")
    add("session readable: ${summary.sessionReadError ?: "yes"}")
    add("last ${summary.lastLines.size} session line(s):")
    if (summary.lastLines.isEmpty()) {
        add("  (none)")
    } else {
        summary.lastLines.forEach { add("  ${SessionLogSanitizer.redact(it)}") }
    }
}

/**
 * The whole exported diagnostics file. The session section is deliberately first, before the device
 * header and the noisy app log, so a "why did I get logged out" report is readable.
 *
 * Every line is sanitized: no cookie value, SAPISID value, Authorization, visitorData or dataSyncId
 * value can reach the file even if a caller passes one by mistake.
 */
fun buildDiagnosticsReport(
    header: List<Pair<String, String>>,
    checks: List<DiagnosticCheck>,
    entries: List<LogEntry>,
    session: SessionSummary,
    crash: String? = null,
    startupWarnings: String? = null,
): String = buildString {
    appendLine("AURALIS diagnostics")
    sessionSection(session).forEach { appendLine(SessionLogSanitizer.redact(it)) }
    appendLine()
    if (!crash.isNullOrBlank()) {
        appendLine("Last crash")
        crash.trimEnd().lineSequence().forEach { appendLine(SessionLogSanitizer.redact("  $it")) }
        appendLine()
    }
    if (!startupWarnings.isNullOrBlank()) {
        appendLine("Startup warnings")
        startupWarnings.trimEnd().lineSequence().forEach { appendLine(SessionLogSanitizer.redact("  $it")) }
        appendLine()
    }
    header.forEach { (label, value) ->
        appendLine(SessionLogSanitizer.redact("$label: $value"))
    }
    appendLine()
    appendLine("What's not working")
    checks.forEach { check ->
        val label = when (check.level) {
            DiagnosticLevel.OK -> "OK"
            DiagnosticLevel.WARN -> "WARN"
            DiagnosticLevel.ERROR -> "ERROR"
        }
        appendLine(SessionLogSanitizer.redact("[$label] ${check.title}: ${check.reason}"))
    }
    appendLine()
    appendLine("Logs (${entries.size} most recent, newest first)")
    entries.take(500).forEach { entry ->
        appendLine(
            SessionLogSanitizer.redact(
                "${entry.timestamp} ${entry.level}/${entry.tag}: ${entry.message}",
            ),
        )
        entry.stackTrace?.lineSequence()?.forEach { line -> appendLine("  ${SessionLogSanitizer.redact(line)}") }
    }
}

private fun yesNo(value: Boolean) = if (value) "yes" else "no"
private fun present(value: Boolean) = if (value) "present" else "absent"