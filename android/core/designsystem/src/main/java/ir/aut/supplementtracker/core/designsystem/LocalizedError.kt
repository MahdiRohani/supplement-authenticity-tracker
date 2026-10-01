package ir.aut.supplementtracker.core.designsystem

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@StringRes
private fun errorResource(raw: String): Int? =
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
        "HIDDEN_LABEL_MISMATCH" -> R.string.error_hidden_label_mismatch
        "WRONG_CHAIN" -> R.string.error_wrong_chain
        "NOT_A_HIDDEN_LABEL" -> R.string.error_not_a_hidden_label
        "NOT_A_UNIT_CODE" -> R.string.error_not_a_unit_code
        "BATCH_SIZE_OUT_OF_RANGE" -> R.string.error_batch_size
        "TRANSFER_COUNT_OUT_OF_RANGE" -> R.string.error_transfer_count
        "KEYS_ALREADY_DISCARDED" -> R.string.error_keys_discarded
        else -> null
    }

fun localizeErrorMessage(context: Context, raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return errorResource(raw)?.let(context::getString) ?: raw
}

@Composable
fun localizedErrorMessage(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return errorResource(raw)?.let { stringResource(it) } ?: raw
}
