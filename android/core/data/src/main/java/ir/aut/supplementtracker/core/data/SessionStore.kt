package ir.aut.supplementtracker.core.data

import android.content.Context
import ir.aut.supplementtracker.core.model.SupplyRole
import ir.aut.supplementtracker.core.model.UserSession

class SessionStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(session: UserSession) {
        prefs.edit()
            .putString(KEY_ROLE, session.role.name)
            .putString(KEY_ADDRESS, session.address)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun read(): UserSession? {
        val roleName = prefs.getString(KEY_ROLE, null) ?: return null
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val role = runCatching { SupplyRole.valueOf(roleName) }.getOrNull() ?: return null
        return UserSession(role = role, address = address)
    }

    companion object {
        private const val PREFS = "supplement_session"
        private const val KEY_ROLE = "role"
        private const val KEY_ADDRESS = "address"
    }
}
