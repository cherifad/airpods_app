package com.airbuds.companion.data.preferences

import android.content.Context

/**
 * Remembers which devices answered as Airoha earbuds and which ones clearly do
 * not, plus the last device that connected successfully.
 */
class DeviceMemory(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val lastAddress: String?
        get() = preferences.getString(KEY_LAST_ADDRESS, null)

    val lastName: String?
        get() = preferences.getString(KEY_LAST_NAME, null)

    fun markSupported(address: String, name: String?) {
        preferences.edit()
            .putString(KEY_LAST_ADDRESS, address)
            .putString(KEY_LAST_NAME, name)
            .putStringSet(KEY_UNSUPPORTED, unsupported() - address)
            .apply()
    }

    fun markUnsupported(address: String) {
        preferences.edit()
            .putStringSet(KEY_UNSUPPORTED, unsupported() + address)
            .apply()
    }

    fun isUnsupported(address: String): Boolean = address in unsupported()

    fun unsupported(): Set<String> =
        preferences.getStringSet(KEY_UNSUPPORTED, emptySet()).orEmpty().toSet()

    companion object {
        private const val PREFS = "sonabud"
        private const val KEY_LAST_ADDRESS = "last_device_address"
        private const val KEY_LAST_NAME = "last_device_name"
        private const val KEY_UNSUPPORTED = "unsupported_devices"
    }
}
