package com.auralis.app.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One published GitHub release, as far as the in-app updater cares. */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
)

sealed interface UpdateCheck {
    data class Available(val release: ReleaseInfo, val currentVersion: String) : UpdateCheck
    data class UpToDate(val currentVersion: String) : UpdateCheck

    /** The repository has no published release yet (HTTP 404) — not an error. */
    data object NoRelease : UpdateCheck

    data class Failed(val message: String, val error: Throwable? = null) : UpdateCheck
}

/** A bare semantic version: only the three numeric parts are compared. */
internal data class SemVer(val major: Int, val minor: Int, val patch: Int) {
    override fun toString(): String = "$major.$minor.$patch"
}

/** `"v1.2.0-debug"` -> `SemVer(1, 2, 0)`; returns null when no version can be found. */
internal fun parseVersion(raw: String): SemVer? {
    val match = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(raw) ?: return null
    return SemVer(
        major = match.groupValues[1].toIntOrNull() ?: return null,
        minor = match.groupValues[2].toIntOrNull() ?: return null,
        patch = match.groupValues[3].toIntOrNull() ?: 0,
    )
}

/** Numeric comparison: 1.10.0 is newer than 1.9.3 (never a string comparison). */
internal fun compareVersions(a: SemVer, b: SemVer): Int = when {
    a.major != b.major -> a.major.compareTo(b.major)
    a.minor != b.minor -> a.minor.compareTo(b.minor)
    else -> a.patch.compareTo(b.patch)
}

/**
 * Reads the latest release from the GitHub REST API. Runs off the main thread with the same
 * HttpURLConnection approach as the rest of the app; HTTP 404 means "no release yet".
 */
class UpdateChecker(private val currentVersionName: String) {

    suspend fun check(): UpdateCheck = runInterruptible(Dispatchers.IO) { request() }

    private fun request(): UpdateCheck {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(UpdateConfig.LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = UpdateConfig.TIMEOUT_MS
                readTimeout = UpdateConfig.TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> return UpdateCheck.NoRelease
                HttpURLConnection.HTTP_OK -> Unit
                else -> return UpdateCheck.Failed("GitHub returned HTTP $code")
            }

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            // The /latest endpoint already skips these, but never offer a draft or a pre-release.
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return UpdateCheck.NoRelease

            val current = parseVersion(currentVersionName)
                ?: return UpdateCheck.Failed("Couldn't read the installed version")
            val tag = json.optString("tag_name").trim()
            val remote = parseVersion(tag)
                ?: return UpdateCheck.Failed(if (tag.isBlank()) "Release has no version tag" else "Unsupported release tag \"$tag\"")
            if (compareVersions(remote, current) <= 0) return UpdateCheck.UpToDate(current.toString())

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L
            for (index in 0 until (assets?.length() ?: 0)) {
                val asset = assets?.optJSONObject(index) ?: continue
                if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
                apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                apkSize = asset.optLong("size")
                break
            }

            val release = ReleaseInfo(
                tag = tag,
                version = remote.toString(),
                notes = json.optString("body").trim(),
                pageUrl = json.optString("html_url").ifBlank { UpdateConfig.RELEASES_PAGE },
                apkUrl = apkUrl,
                apkSize = apkSize,
            )
            return UpdateCheck.Available(release, current.toString())
        } catch (cancelled: InterruptedException) {
            throw cancelled
        } catch (error: IOException) {
            UpdateCheck.Failed("Couldn't reach GitHub. Check your connection.", error)
        } catch (error: Exception) {
            UpdateCheck.Failed("Update check failed", error)
        } finally {
            connection?.disconnect()
        }
    }
}
