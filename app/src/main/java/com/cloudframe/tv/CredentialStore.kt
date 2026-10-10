package com.cloudframe.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Degoo email and password, saved so the app can sign in again by itself when
 * Degoo ends the session. The password is encrypted with an AES key that lives
 * in the Android Keystore and can't be read out of the TV.
 */
class CredentialStore(context: Context) {
    private val sp = context.getSharedPreferences("cloudframe_credentials", Context.MODE_PRIVATE)

    val hasCredentials: Boolean get() = sp.contains("password")

    fun save(email: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        sp.edit()
            .putString("email", email)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("password", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): Pair<String, String>? = runCatching {
        val email = sp.getString("email", null) ?: return null
        val iv = Base64.decode(sp.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val data = Base64.decode(sp.getString("password", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        email to String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()

    fun clear() = sp.edit().clear().apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ALIAS = "cloudframe-degoo"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
