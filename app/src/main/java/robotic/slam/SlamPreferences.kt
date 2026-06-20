package robotic.slam

import android.content.Context

object SlamPreferences {
    private const val PREFS_NAME = "slam_preferences"
    private const val KEY_DURATION_SECONDS = "mapping_duration_seconds"
    private const val KEY_FOCAL_LENGTH = "camera_focal_length"
    private const val KEY_PAN_COEFF = "pan_coefficient"
    private const val KEY_TILT_COEFF = "tilt_coefficient"
    const val MIN_DURATION_SECONDS = 5
    const val MAX_DURATION_SECONDS = 60
    const val DEFAULT_DURATION_SECONDS = 15
    const val DEFAULT_FOCAL_LENGTH = 500f
    const val DEFAULT_PAN_COEFF = 0.5f
    const val DEFAULT_TILT_COEFF = 0.12f

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

    fun getFocalLength(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_FOCAL_LENGTH, DEFAULT_FOCAL_LENGTH)
    }

    fun setFocalLength(context: Context, focalLength: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FOCAL_LENGTH, focalLength)
            .apply()
    }

    fun getPanCoefficient(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_PAN_COEFF, DEFAULT_PAN_COEFF)
    }

    fun setPanCoefficient(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PAN_COEFF, value)
            .apply()
    }

    fun getTiltCoefficient(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_TILT_COEFF, DEFAULT_TILT_COEFF)
    }

    fun setTiltCoefficient(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TILT_COEFF, value)
            .apply()
    }
}
