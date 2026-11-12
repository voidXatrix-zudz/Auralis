package com.auralis.app.data.youtube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Signed-in YouTube Music session. Never logged. */
data class YouTubeAuth(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val name: String = "",
    val avatarUrl: String = "",
) {
    /** SAPISID used to derive the SAPISIDHASH Authorization header. */
    val sapisid: String?
        get() = SAPISID_PATTERN.find(cookie)?.groupValues?.getOrNull(1)

    val signedIn: Boolean get() = cookie.isNotBlank() && sapisid != null

    private companion object {
        val SAPISID_PATTERN = Regex("(?:^|;\\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)")
    }
}

/**
 * Stores the cookies/visitor id/account in an AES-GCM blob whose key lives in the Android
 * Keystore. Plaintext never touches disk and no value is ever written to the app log.
 */
class YouTubeAuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _auth = MutableStateFlow(load())
    val auth: StateFlow<YouTubeAuth?> = _auth.asStateFlow()

    fun snapshot(): YouTubeAuth? = _auth.value

    fun save(auth: YouTubeAuth) {
        prefs.edit().putString(KEY_BLOB, encrypt(serialize(auth))).apply()
        _auth.value = auth
    }

    fun updateProfile(name: String, avatarUrl: String) {
        val current = _auth.value ?: return
        save(current.copy(name = name, avatarUrl = avatarUrl))
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
        _auth.value = null
    }

    private fun load(): YouTubeAuth? = runCatching {
        prefs.getString(KEY_BLOB, null)?.let { base64 ->
            val plain = decrypt(base64) ?: return null
            deserialize(plain)
        }
    }.getOrNull()

    private fun serialize(a: YouTubeAuth): String = listOf(a.cookie, a.visitorData, a.dataSyncId, a.name, a.avatarUrl)
        .joinToString(SEPARATOR) { it.replace(SEPARATOR, " ") }

    private fun deserialize(raw: String): YouTubeAuth? {
        val parts = raw.split(SEPARATOR)
        if (parts.size < 5) return null
        val auth = YouTubeAuth(parts[0], parts[1], parts[2], parts[3], parts[4])
        return auth.takeIf { it.cookie.isNotBlank() }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(base64: String): String? = runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        if (bytes.size < IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

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
        const val KEY_ALIAS = "auralis.yt.auth"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val SEPARATOR = "\u001F"
    }
}
