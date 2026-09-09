package ir.aut.supplementtracker.core.data

import android.content.Context

class AnalyticsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun incrementVerify() {
        prefs.edit().putInt(KEY_VERIFY_COUNT, verifyCount() + 1).apply()
    }

    fun incrementScan() {
        prefs.edit().putInt(KEY_SCAN_COUNT, scanCount() + 1).apply()
    }

    fun verifyCount(): Int = prefs.getInt(KEY_VERIFY_COUNT, 0)

    fun scanCount(): Int = prefs.getInt(KEY_SCAN_COUNT, 0)

    companion object {
        private const val PREFS = "supplement_analytics"
        private const val KEY_VERIFY_COUNT = "verify_count"
        private const val KEY_SCAN_COUNT = "scan_count"
    }
}
