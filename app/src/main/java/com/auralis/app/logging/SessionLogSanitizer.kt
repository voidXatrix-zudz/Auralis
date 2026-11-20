package com.auralis.app.logging

/**
 * Removes secret values from anything written to the persistent YouTube session log.
 *
 * Only NAMES may survive: `SAPISID=<redacted>`, `visitorData=<redacted>`. The pattern requires an
 * `=` or `:`, so a bare list of cookie names (`cookies=[SAPISID,SID]`) is left intact.
 */
object SessionLogSanitizer {
    private val authorization = Regex("(?i)Authorization\\s*[:=].*")
    private val assignment = Regex(
        "(?i)(SAPISIDHASH|SAPISID|APISID|__Secure-[A-Za-z0-9_-]+|__Host-[A-Za-z0-9_-]+|" +
            "SIDCC|HSID|SSID|SID|Authorization|visitorData|DATASYNC_ID|dataSyncId|pageId|" +
            "onBehalfOfUser)\\s*[=:]\\s*[^\\s,;\\]]+",
    )

    fun redact(text: String): String {
        val cleaned = text
            .replace(authorization, "Authorization=<redacted>")
            .replace(assignment) { match -> "${match.groupValues[1]}=<redacted>" }
        return if (cleaned.length > 500) cleaned.take(500) + "…" else cleaned
    }

    /** Cookie names only, in first-seen order. */
    fun cookieNames(header: String?): List<String> {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        return value.split(';')
            .mapNotNull { entry ->
                entry.substringBefore('=').trim().takeIf { it.isNotEmpty() && entry.contains('=') }
            }
            .distinct()
    }
}