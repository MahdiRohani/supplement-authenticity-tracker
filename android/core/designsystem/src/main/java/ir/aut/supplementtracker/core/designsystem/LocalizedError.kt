package ir.aut.supplementtracker.core.designsystem

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

fun localizeErrorMessage(context: Context, raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val resId =
        when (raw) {
            "INVALID_INPUT" -> R.string.error_invalid_input
            "NOT_FOUND" -> R.string.error_not_found
            "CONFLICT" -> R.string.error_conflict
            "RATE_LIMITED" -> R.string.error_rate_limited
            "NETWORK_ERROR" -> R.string.error_network
            "REQUEST_FAILED" -> R.string.error_request_failed
            "UNEXPECTED_ERROR" -> R.string.error_unexpected
            "ALREADY_CONSUMED" -> R.string.error_already_consumed
            "OWNER_ADDRESS_REQUIRED" -> R.string.error_owner_required
            "HISTORY_FAILED" -> R.string.error_history_failed
            else -> null
        }
    return if (resId != null) context.getString(resId) else raw
}

@Composable
fun localizedErrorMessage(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when (raw) {
        "INVALID_INPUT" -> stringResource(R.string.error_invalid_input)
        "NOT_FOUND" -> stringResource(R.string.error_not_found)
        "CONFLICT" -> stringResource(R.string.error_conflict)
        "RATE_LIMITED" -> stringResource(R.string.error_rate_limited)
        "NETWORK_ERROR" -> stringResource(R.string.error_network)
        "REQUEST_FAILED" -> stringResource(R.string.error_request_failed)
        "UNEXPECTED_ERROR" -> stringResource(R.string.error_unexpected)
        "ALREADY_CONSUMED" -> stringResource(R.string.error_already_consumed)
        "OWNER_ADDRESS_REQUIRED" -> stringResource(R.string.error_owner_required)
        "HISTORY_FAILED" -> stringResource(R.string.error_history_failed)
        else -> raw
    }
}
