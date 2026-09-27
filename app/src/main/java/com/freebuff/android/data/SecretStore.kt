package com.freebuff.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.freebuff.android.security.EnvRedaction

// Provider/BYOK secrets live in Keystore-backed encrypted prefs (Master Spec S14).
// They are never written to plain SharedPreferences, logs, or terminal subprocess env.
class SecretStore(context: Context) {
    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "freebuff_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveApiKey(key: String) { prefs.edit().putString(KEY_API, key.trim()).apply() }
    fun readApiKey(): String? = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
    fun clearApiKey() { prefs.edit().remove(KEY_API).apply() }

    fun saveBaseUrl(url: String) { prefs.edit().putString(KEY_BASE, url.trim()).apply() }
    fun readBaseUrl(): String = prefs.getString(KEY_BASE, null) ?: DEFAULT_BASE

    fun saveModel(model: String) { prefs.edit().putString(KEY_MODEL, model.trim()).apply() }
    fun readModel(): String = prefs.getString(KEY_MODEL, null) ?: DEFAULT_MODEL

    /** Display-only, safe summary. Delegates to the shared redaction rule. */
    fun redactedKeySummary(): String {
        val k = readApiKey() ?: return "no key configured"
        return "key ending ${k.takeLast(4)} (${EnvRedaction.redact(k)})"
    }

    companion object {
        private const val KEY_API = "provider_api_key"
        private const val KEY_BASE = "base_url"
        private const val KEY_MODEL = "model"
        const val DEFAULT_BASE = "https://codebuff.com"
        const val DEFAULT_MODEL = "freebuff"
    }
}
