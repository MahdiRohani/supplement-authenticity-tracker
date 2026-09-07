package ir.aut.supplementtracker.core.domain

sealed class DomainError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Validation(message: String) : DomainError(message)
    class NotFound(message: String) : DomainError(message)
    class Conflict(message: String) : DomainError(message)
    class RateLimited(message: String = "Too many requests") : DomainError(message)
    class Network(message: String, cause: Throwable? = null) : DomainError(message, cause)
    class Api(val statusCode: Int, message: String) : DomainError(message)
    class Unknown(message: String, cause: Throwable? = null) : DomainError(message, cause)
}

object ErrorMapper {
    fun toUserMessage(error: Throwable): String =
        when (error) {
            is DomainError.Validation -> error.message ?: "Invalid input"
            is DomainError.NotFound -> error.message ?: "Not found"
            is DomainError.Conflict -> error.message ?: "Conflict"
            is DomainError.RateLimited -> error.message ?: "Too many requests"
            is DomainError.Network -> error.message ?: "Network error"
            is DomainError.Api -> error.message ?: "Request failed"
            is DomainError -> error.message ?: "Unexpected error"
            else -> error.message ?: "Unexpected error"
        }

    fun fromHttp(statusCode: Int, body: String): DomainError {
        val message = body.ifBlank { "HTTP $statusCode" }
        return when (statusCode) {
            400 -> DomainError.Validation(extractMessage(body) ?: message)
            404 -> DomainError.NotFound(extractMessage(body) ?: message)
            409 -> DomainError.Conflict(
                extractMessage(body)
                    ?: "Product already consumed; refill is not allowed",
            )
            429 -> DomainError.RateLimited()
            in 500..599 -> DomainError.Api(statusCode, extractMessage(body) ?: message)
            else -> DomainError.Api(statusCode, extractMessage(body) ?: message)
        }
    }

    private fun extractMessage(body: String): String? {
        val match = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)
        return match?.groupValues?.get(1)
            ?: Regex("\"message\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    }
}
