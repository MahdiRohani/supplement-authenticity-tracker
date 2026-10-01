package ir.aut.supplementtracker.core.blockchain

import android.content.Context
import ir.aut.supplementtracker.core.domain.ConsumerIdentity
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/**
 * A per-install consumer key, created on first use. It only names the buyer in
 * `UnitConsumed` events: it holds no funds and grants no role, and the consume
 * itself is authorized by the unit key from the hidden label. The preferences
 * file is excluded from backups (see the app's backup rules).
 */
class ConsumerKeyStore(context: Context) : ConsumerIdentity {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cached: String? = null

    override fun address(): String =
        cached ?: synchronized(this) {
            cached ?: credentials().address.let(Keys::toChecksumAddress).also { cached = it }
        }

    private fun credentials(): Credentials {
        prefs.getString(KEY_PRIVATE, null)?.let { return Credentials.create(it) }
        val keyPair = Keys.createEcKeyPair()
        val hex = Numeric.toHexStringWithPrefixZeroPadded(keyPair.privateKey, 64)
        prefs.edit().putString(KEY_PRIVATE, hex).apply()
        return Credentials.create(keyPair)
    }

    companion object {
        const val PREFS = "consumer_identity"
        private const val KEY_PRIVATE = "private_key"
    }
}
