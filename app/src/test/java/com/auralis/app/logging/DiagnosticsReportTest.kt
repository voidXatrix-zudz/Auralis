package com.auralis.app.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3/T7: the export starts with the "YouTube session" section, shows cookie NAMES only, and no
 * cookie value, SAPISID value or other secret can reach the file.
 */
class DiagnosticsReportTest {
    private val secret = "SAPISID=THE-ACTUAL-SECRET-VALUE; SID=ANOTHER-SECRET"
    private val summary = SessionSummary(
        signedIn = true,
        expired = false,
        lastRefreshAge = "2h 5m ago",
        cookieNames = SessionLogSanitizer.cookieNames(secret),
        clientVersionPresent = true,
        visitorDataPresent = true,
        dataSyncIdPresent = false,
        authUser = "0",
        snapshotExists = true,
        sessionReadError = null,
        lastLines = listOf(
            "2026-01-01 10:00:00 auth: 401 browse signed=yes rule=session-defining",
            // A hostile line that should still be sanitized on export.
            "2026-01-01 10:00:01 session: cleared cookie SAPISID=THE-ACTUAL-SECRET-VALUE",
        ),
    )

    @Test
    fun `the session section is first and shows names not values`() {
        val report = buildDiagnosticsReport(
            header = listOf("App version" to "1.2.0"),
            checks = emptyList(),
            entries = emptyList(),
            session = summary,
        )

        val sessionIndex = report.indexOf("YouTube session")
        val headerIndex = report.indexOf("App version")
        assertTrue(sessionIndex in 0 until headerIndex)
        assertTrue(report.contains("signedIn: yes"))
        assertTrue(report.contains("cookie names: ${summary.cookieNames.joinToString(", ")}"))
        assertTrue(report.contains("SAPISID"))
    }

    @Test
    fun `no cookie value can reach the report`() {
        val report = buildDiagnosticsReport(
            header = listOf("App version" to "1.2.0"),
            checks = emptyList(),
            entries = listOf(
                LogEntry(
                    timestamp = "2026-01-01 10:00:00",
                    level = 'I',
                    tag = "YouTube",
                    message = "Authorization: SAPISIDHASH 123_THEHASH visitorData=VISITOR-SECRET dataSyncId=SYNC-SECRET",
                    stackTrace = null,
                ),
            ),
            session = summary,
        )

        listOf("THE-ACTUAL-SECRET-VALUE", "ANOTHER-SECRET", "THEHASH", "VISITOR-SECRET", "SYNC-SECRET").forEach { value ->
            assertFalse("report leaked $value", report.contains(value))
        }
        assertTrue(report.contains("SAPISID=THE-ACTUAL-SECRET-VALUE") == false)
    }

    @Test
    fun `an unreadable session is reported by type only`() {
        val report = buildDiagnosticsReport(
            header = emptyList(),
            checks = listOf(DiagnosticCheck("YouTube", DiagnosticLevel.WARN, "session unreadable")),
            entries = emptyList(),
            session = summary.copy(sessionReadError = "AEADBadTagException", snapshotExists = false),
        )

        assertTrue(report.contains("session readable: AEADBadTagException"))
        assertTrue(report.contains("[WARN] YouTube: session unreadable"))
    }

    @Test
    fun `refresh age is human readable`() {
        val now = 10_000_000_000L
        assertTrue(refreshAgeText(0L, now) == "never")
        assertTrue(refreshAgeText(now - 30_000, now) == "just now")
        assertTrue(refreshAgeText(now - 90 * 60_000, now) == "1h 30m ago")
    }
}