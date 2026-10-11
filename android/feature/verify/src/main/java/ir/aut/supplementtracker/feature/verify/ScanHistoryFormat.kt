package ir.aut.supplementtracker.feature.verify

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Parses the backend's `2026-10-01T08:00:00.000Z` timestamps; null when malformed. */
internal fun parseUtcMillis(iso: String): Long? {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    return try {
        format.parse(iso)?.time
    } catch (_: ParseException) {
        null
    }
}
