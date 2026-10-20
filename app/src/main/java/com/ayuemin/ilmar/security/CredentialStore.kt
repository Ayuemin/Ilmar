package com.ayuemin.ilmar.security

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
 * Encrypts provider credentials with a non-exportable Android Keystore AES key.
 * No plaintext API key is written to SharedPreferences.
 */
interface SecretStore {
    fun saveApiKey(value: String, providerId: String = "legacy")
    fun loadApiKey(providerId: String = "legacy"): String?
    fun hasApiKey(providerId: String = "legacy"): Boolean
    fun clearApiKey(providerId: String = "legacy")
}

class CredentialStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        "ilmar_credentials", Context.MODE_PRIVATE
    )
    private val alias = "ilmar_provider_credentials_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    override fun saveApiKey(value: String, providerId: String) {
        if (value.isBlank()) {
            clearApiKey(providerId)
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(providerId + "_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(providerId + "_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit().also { check(it) { "Не удалось сохранить ключ" } }
    }

    override fun loadApiKey(providerId: String): String? {
        val iv = (prefs.getString(providerId + "_iv", null) ?: if (providerId == "legacy") prefs.getString("iv", null) else null) ?: return null
        val encrypted = (prefs.getString(providerId + "_ciphertext", null) ?: if (providerId == "legacy") prefs.getString("ciphertext", null) else null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: java.security.GeneralSecurityException) {
            null // Key lost or ciphertext invalid; never return corrupted credentials.
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun hasApiKey(providerId: String): Boolean = loadApiKey(providerId) != null

    override fun clearApiKey(providerId: String) {
        prefs.edit().remove(providerId + "_iv").remove(providerId + "_ciphertext").apply()
        if (providerId == "legacy") prefs.edit().remove("iv").remove("ciphertext").apply()
    }
}

