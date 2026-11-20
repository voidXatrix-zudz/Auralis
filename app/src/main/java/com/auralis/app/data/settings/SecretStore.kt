package com.auralis.app.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.auralis.app.logging.AppLog
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal Android Keystore backed encryption for small secrets (currently the Last.fm API key).
 *
 * The AES key never leaves the keystore; only the ciphertext (IV + GCM tag) is written to DataStore.
 * Keystore is available from API 23, so this works across the whole supported range.
 *
 * Every operation is defensive: a Keystore problem ([GeneralSecurityException] — which covers
 * KeyStoreException, UnrecoverableKeyException, InvalidKeyException and AEADBadTagException — or a
 * ProviderException / malformed input) degrades to "no secret" instead of crashing. An unreadable
 * secret is treated as missing; the stored ciphertext is never deleted or rewritten on read.
 */
object SecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "auralis_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(context: Context, plain: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        } catch (error: GeneralSecurityException) {
            logFailure("encrypt", error)
            ""
        } catch (error: RuntimeException) {
            logFailure("encrypt", error)
            ""
        }
    }

    fun decrypt(context: Context, encoded: String): String {
        if (encoded.isBlank()) return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return ""
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            // Includes AEADBadTagException (wrong/tampered key), KeyStoreException and
            // UnrecoverableKeyException. The blob is left on disk untouched.
            logFailure("decrypt", error)
            ""
        } catch (error: IllegalArgumentException) {
            // Base64 decode failure or a malformed GCMParameterSpec.
            logFailure("decrypt", error)
            ""
        } catch (error: RuntimeException) {
            // Covers ProviderException and any vendor Keystore misbehaviour.
            logFailure("decrypt", error)
            ""
        }
    }

    private fun logFailure(op: String, error: Throwable) {
        AppLog.w("SecretStore", "keystore $op failed type=${error.javaClass.simpleName}")
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
