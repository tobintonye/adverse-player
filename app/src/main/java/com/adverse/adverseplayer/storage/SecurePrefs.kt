package com.adverse.adverseplayer.storage

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the one real secret on this device: its auth token. Uses
 * EncryptedSharedPreferences (not plain SharedPreferences) because this
 * token authenticates the box to the server — worth encrypting at rest in
 * case the box is ever physically compromised.
 */
class SecurePrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "adverse_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var deviceUid: String?
        get() = prefs.getString(KEY_DEVICE_UID, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_UID, value) }

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_AUTH_TOKEN, value) }

    var pairingCode: String?
        get() = prefs.getString(KEY_PAIRING_CODE, null)
        set(value) = prefs.edit { putString(KEY_PAIRING_CODE, value) }

    /** Last "Last-Modified" value returned by /players/schedule/ — send this
     *  back as If-Modified-Since so unchanged polls come back as cheap 304s. */
    var scheduleLastModified: String?
        get() = prefs.getString(KEY_SCHEDULE_LAST_MODIFIED, null)
        set(value) = prefs.edit { putString(KEY_SCHEDULE_LAST_MODIFIED, value) }

    val isPaired: Boolean
        get() = !authToken.isNullOrEmpty()

    /** Called when the server returns 401 — token was rotated or disabled. */
    fun clearPairing() {
        prefs.edit {
            remove(KEY_AUTH_TOKEN)
            remove(KEY_PAIRING_CODE)
            remove(KEY_SCHEDULE_LAST_MODIFIED)
        }
    }

    companion object {
        private const val KEY_DEVICE_UID = "device_uid"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_SCHEDULE_LAST_MODIFIED = "schedule_last_modified"
    }
}