package com.airbuds.companion.data.preferences

import android.content.Context

/** Remembers whether the first-launch onboarding has been completed. */
class OnboardingPreferences(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var completed: Boolean
        get() = preferences.getBoolean(KEY_COMPLETED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_COMPLETED, value).apply()
        }

    companion object {
        private const val PREFS = "sonabud"
        private const val KEY_COMPLETED = "onboarding_completed"
    }
}
