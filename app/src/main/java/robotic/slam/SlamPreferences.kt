package robotic.slam

import android.content.Context

object SlamPreferences {
    private const val PREFS_NAME = "slam_preferences"
    private const val KEY_DURATION_SECONDS = "mapping_duration_seconds"
    const val MIN_DURATION_SECONDS = 5
    const val MAX_DURATION_SECONDS = 60
    const val DEFAULT_DURATION_SECONDS = 15

    fun getMappingDurationSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DURATION_SECONDS, DEFAULT_DURATION_SECONDS)
            .coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
    }

    fun setMappingDurationSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DURATION_SECONDS, seconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS))
            .apply()
    }
}
