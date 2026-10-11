package ir.aut.supplementtracker.core.data

import android.content.Context
import ir.aut.supplementtracker.core.domain.ScanContextProvider
import ir.aut.supplementtracker.core.model.ScanContext
import java.util.UUID

/**
 * Per-install device id, sent with each verification for clone detection.
 * The backend only stores a salted hash of it.
 */
class ScanContextStore(context: Context) : ScanContextProvider {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun current(): ScanContext = ScanContext(deviceId = deviceId())

    @Synchronized
    private fun deviceId(): String =
        prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }

    companion object {
        const val PREFS = "scan_context"
        private const val KEY_DEVICE = "device_id"
    }
}
