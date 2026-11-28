package com.auralis.app.data.youtube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.auralis.app.logging.SessionLogSanitizer
import com.auralis.app.logging.YouTubeSessionLog
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The three cookie names that can sign an InnerTube request, in the order they are preferred.
 *
 * `SAPISID` is the documented one, but a partitioned/`__Host` login sets only the `__Secure-`
 * forms. Checking only `SAPISID` makes the UI say "signed in" while every request goes out
 * unsigned, which Google answers as a stranger. All three name the same secret.
 */
internal val SAPISID_COOKIE_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

/** True when [cookieHeader] carries at least one non-empty signing cookie. */
fun hasSigningCookie(cookieHeader: String): Boolean = cookieMap(cookieHeader)
    .any { (name, value) -> name in SAPISID_COOKIE_NAMES && value.isNotEmpty() }

/** The signing secret out of a cookie header, or null when none of the three names is present. */
fun signingCookieValue(cookieHeader: String): String? {
    val jar = cookieMap(cookieHeader)
    return SAPISID_COOKIE_NAMES.firstNotNullOfOrNull { jar[it]?.takeIf(String::isNotBlank) }
}

private fun cookieMap(cookieHeader: String): Map<String, String> =
    cookieHeader.split(';').mapNotNull { entry ->
        val name = entry.substringBefore('=').trim()
        val value = entry.substringAfter('=', "").trim()
        if (name.isEmpty()) null else name to value
    }.toMap()

/** The cookie name at the start of a `Set-Cookie`/`Cookie` fragment, or null when malformed. */
internal fun cookieName(header: String): String? =
    header.substringBefore(';').substringBefore('=').trim()
        .takeIf { it.isNotEmpty() && header.contains('=') }

/**
 * Applies the `Set-Cookie` rotations from one reply to an existing cookie header, returning the
 * updated header, or null when nothing changed.
 *
 * Only names already present in [existing] are updated, so unrelated cookies (consent, marketing)
 * are never adopted into the session. A deletion (`Max-Age=0` or an empty value) is ignored rather
 * than removing a still-needed cookie. Cookie values are never logged by the caller.
 */
internal fun mergeCookieHeader(existing: String, setCookies: List<String>): String? {
    val jar = LinkedHashMap<String, String>()
    for (entry in existing.split(';')) {
        val name = entry.substringBefore('=').trim()
        val value = entry.substringAfter('=', "").trim()
        if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
    }
    var changed = false
    for (raw in setCookies) {
        val name = raw.substringBefore(';').substringBefore('=').trim()
        val value = raw.substringBefore(';').substringAfter('=', "").trim()
        if (name.isEmpty() || value.isEmpty()) continue
        if (raw.contains("Max-Age=0", ignoreCase = true)) continue
        if (!jar.containsKey(name)) continue
        if (jar[name] != value) {
            jar[name] = value
            changed = true
        }
    }
    return if (changed) jar.entries.joinToString("; ") { (name, value) -> "$name=$value" } else null
}

/**
 * Value accepted by Innertube as `context.user.onBehalfOfUser` for a `DATASYNC_ID`.
 *
 * YouTube exposes `DATASYNC_ID` as `account||delegated`. The **second** half is the active
 * identity Google can tie to the cookie; the first half names the account rather than the page and
 * is answered with HTTP 401 when sent as `onBehalfOfUser`. A value without the separator is kept
 * as-is, and an empty value becomes null so the field is omitted rather than sent blank.
 */
fun normalizeDataSyncId(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (!value.contains("||")) return value
    return value.substringAfter("||").trim().takeIf(String::isNotBlank)
        ?: value.substringBefore("||").trim().takeIf(String::isNotBlank)
}

/** Signed-in YouTube Music session. Cookie/secret values are never logged. */
@Serializable
data class YouTubeAuth(
    val cookie: String,
    val visitorData: String = "",
    /** Normalised `DATASYNC_ID` used as `onBehalfOfUser`. */
    val dataSyncId: String = "",
    /** `DELEGATED_SESSION_ID`, set only for a brand channel; sent as `X-Goog-PageId`. */
    val pageId: String? = null,
    /** `SESSION_INDEX`, sent as `X-Goog-AuthUser`. */
    val authUser: String = "0",
    /** Live `INNERTUBE_CLIENT_VERSION` read at sign-in; empty falls back to the constant. */
    val clientVersion: String = "",
    /**
     * The WebView user agent that created the session, replayed verbatim on signed requests.
     * Google can tie a session to the browser that signed in; empty for older saved sessions.
     */
    val userAgent: String = "",
    val email: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    /** Set after HTTP 401/403; the session is kept but requests go anonymous until sign-in again. */
    val expired: Boolean = false,
    /** When the cookie was last confirmed by a sign-in, a rotation or a silent refresh. */
    val lastRefreshedAt: Long = 0L,
) {
    /** The secret used to derive the SAPISIDHASH header. */
    val sapisid: String? get() = signingCookieValue(cookie)

    /** A usable signed-in session. An expired session drops the app to anonymous mode. */
    val signedIn: Boolean get() = !expired && cookie.isNotBlank() && sapisid != null

    /** A stored session, even when expired — Settings uses this to offer "log in again". */
    val hasSession: Boolean get() = cookie.isNotBlank()
}

/** Alias the prompt uses; the existing class stays intact so no call site has to be renamed. */
typealias YouTubeSession = YouTubeAuth

/**
 * Stores the cookies/visitor id/account in an AES-GCM blob whose key lives in the Android
 * Keystore. Plaintext never touches disk and no value is ever written to the app log.
 *
 * Writes use `commit()` on a single background thread so a session captured while the user leaves
 * the app is on disk before the process is killed, without blocking the UI. A failed read never
 * deletes or rewrites the blob: [readError] surfaces the failure TYPE so Settings can say
 * "Session unreadable, log in again".
 */
class YouTubeAuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One serial writer: a queued save always persists the CURRENT snapshot, never an older one. */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "yt-auth-writer").apply { isDaemon = true }
    }

    private val loader = SessionBlobLoader(
        readBlob = { prefs.getString(KEY_BLOB, null) },
        decrypt = ::decrypt,
        parse = ::deserialize,
        record = { message -> YouTubeSessionLog.record("auth", message) },
    )
    private val loaded = loader.load()

    private val _auth = MutableStateFlow((loaded as? SessionLoadResult.Loaded)?.auth)
    val auth: StateFlow<YouTubeAuth?> = _auth.asStateFlow()

    /** Non-null when a stored blob exists but cannot be read (Keystore/decrypt/JSON failure). */
    private val _readError = MutableStateFlow((loaded as? SessionLoadResult.Unreadable)?.type)
    val readError: StateFlow<String?> = _readError.asStateFlow()

    /** True whenever the encrypted blob exists on disk, readable or not (diagnostics only). */
    private val _hasBlob = MutableStateFlow(hasStoredBlob())
    val hasBlob: StateFlow<Boolean> = _hasBlob.asStateFlow()

    init {
        val session = _auth.value
        YouTubeSessionLog.record(
            "auth",
            "store loaded present=${if (session != null) "yes" else "no"} " +
                "expired=${if (session?.expired == true) "yes" else "no"} " +
                "signingCookie=${if (session?.let { hasSigningCookie(it.cookie) } == true) "yes" else "no"} " +
                "key=${if (keyPresent()) "yes" else "no"} " +
                "readError=${_readError.value ?: "none"}",
        )
    }

    fun snapshot(): YouTubeAuth? = _auth.value

    fun ownerId(): String? = prefs.getString(KEY_OWNER_ID, null)

    fun bindOwner(ownerId: String) { prefs.edit().putString(KEY_OWNER_ID, ownerId).apply() }

    fun lastUploadedHash(): String? = prefs.getString(KEY_UPLOAD_HASH, null)

    fun lastUploadAttemptAt(): Long = prefs.getLong(KEY_UPLOAD_ATTEMPT, 0L)

    fun recordUploadAttempt(at: Long) { prefs.edit().putLong(KEY_UPLOAD_ATTEMPT, at).apply() }

    fun recordUploaded(hash: String) {
        prefs.edit().putString(KEY_UPLOAD_HASH, hash).putLong(KEY_UPLOAD_ATTEMPT, System.currentTimeMillis()).apply()
    }

    fun pendingDeleteOwnerHash(): String? = prefs.getString(KEY_PENDING_DELETE, null)

    fun setPendingDeleteOwnerHash(hash: String?) {
        prefs.edit().apply {
            if (hash == null) remove(KEY_PENDING_DELETE) else putString(KEY_PENDING_DELETE, hash)
        }.apply()
    }

    /** True when the encrypted blob exists on disk (even if it cannot be decrypted). */
    fun hasStoredBlob(): Boolean = prefs.getString(KEY_BLOB, null)?.isNotBlank() == true

    fun keyPresent(): Boolean = runCatching {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.containsAlias(KEY_ALIAS)
    }.getOrDefault(false)

    fun save(auth: YouTubeAuth) {
        _auth.value = auth
        _readError.value = null
        _hasBlob.value = true
        writer.execute {
            // Read the latest snapshot at execution time so an out-of-order task cannot resurrect
            // an older cookie (or a cleared session).
            val latest = _auth.value ?: return@execute
            persist(latest)
        }
    }

    fun updateProfile(name: String, avatarUrl: String, email: String? = null) {
        val current = _auth.value ?: return
        save(
            current.copy(
                name = name.ifBlank { current.name },
                avatarUrl = avatarUrl.ifBlank { current.avatarUrl },
                email = email?.takeIf(String::isNotBlank) ?: current.email,
            ),
        )
    }

    /** HTTP 401/403 after verification + refresh + retry all refused. Idempotent. */
    fun markExpired(caller: String = "expired:verified") {
        val current = _auth.value ?: return
        if (current.expired) return
        save(current.copy(expired = true))
        // Names only: a bug report can show which cookies existed without exposing values.
        val names = SessionLogSanitizer.cookieNames(current.cookie).joinToString(",")
        YouTubeSessionLog.record("session", "expired caller=$caller cookieNames=[$names]")
    }

    /**
     * Erases the session only for an explicit disconnect or Clerk identity boundary.
     */
    fun clear(caller: String) {
        if (!SessionClearRules.mayClear(caller)) {
            YouTubeSessionLog.record("session", "clear ignored caller=$caller")
            return
        }
        _auth.value = null
        _readError.value = null
        _hasBlob.value = false
        prefs.edit().remove(KEY_OWNER_ID).remove(KEY_UPLOAD_HASH).remove(KEY_UPLOAD_ATTEMPT).apply()
        writer.execute {
            runCatching { prefs.edit().remove(KEY_BLOB).commit() }
        }
        YouTubeSessionLog.record("session", "cleared caller=$caller")
    }

    // ---- self-heal backoff -------------------------------------------------------------------

    fun lastHealFailureAt(): Long = prefs.getLong(KEY_HEAL_FAILED_AT, 0L)

    fun recordHealFailure(at: Long) {
        prefs.edit().putLong(KEY_HEAL_FAILED_AT, at).apply()
    }

    fun clearHealFailure() {
        prefs.edit().remove(KEY_HEAL_FAILED_AT).apply()
    }

    // ---- persistence -------------------------------------------------------------------------

    private fun persist(auth: YouTubeAuth) {
        runCatching {
            val encoded = json.encodeToString(YouTubeAuth.serializer(), auth)
            val blob = encrypt(encoded)
            check(prefs.edit().putString(KEY_BLOB, blob).commit()) { "commit returned false" }
        }.onFailure { error ->
            // The previous blob stays untouched; a failed save can never lose a working session.
            YouTubeSessionLog.record(
                "auth",
                "save failed type=${SessionBlobLoader.errorType(error)}; previous blob kept",
            )
        }
    }

    /**
     * New format is JSON; the pre-existing format joined five fields with a unit separator.
     * A JSON error is allowed to propagate so [SessionBlobLoader] can report its TYPE.
     */
    private fun deserialize(raw: String): YouTubeAuth? {
        if (raw.trimStart().startsWith("{")) {
            return json.decodeFromString(YouTubeAuth.serializer(), raw)
                .takeIf { it.cookie.isNotBlank() }
        }
        val parts = raw.split(LEGACY_SEPARATOR)
        if (parts.size < 5) return null
        return YouTubeAuth(
            cookie = parts[0],
            visitorData = parts[1],
            dataSyncId = parts[2],
            name = parts[3],
            avatarUrl = parts[4],
        ).takeIf { it.cookie.isNotBlank() }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    /** Throws (never returns null) so the exact failure TYPE reaches the session log. */
    private fun decrypt(base64: String): String {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        require(bytes.size >= IV_BYTES) { "blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    /** Decrypt must never mint a new key: a missing key means "unreadable", not "overwrite". */
    private fun existingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw UnrecoverableKeyException("Keystore key missing")
        return entry.secretKey
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "yt_auth_secure"
        const val KEY_BLOB = "session"
        const val KEY_HEAL_FAILED_AT = "heal_failed_at"
        const val KEY_OWNER_ID = "clerk_owner_id"
        const val KEY_UPLOAD_HASH = "uploaded_hash"
        const val KEY_UPLOAD_ATTEMPT = "upload_attempt_at"
        const val KEY_PENDING_DELETE = "pending_delete_owner_hash"
        const val KEY_ALIAS = "auralis.yt.auth"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val LEGACY_SEPARATOR = "\u001F"
    }
}
