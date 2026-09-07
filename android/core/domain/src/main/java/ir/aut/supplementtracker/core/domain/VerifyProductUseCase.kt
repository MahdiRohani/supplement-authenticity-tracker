package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.VerifyResult

class VerifyProductUseCase(
    private val repository: ProductRepository,
    private val chainVerifier: ChainVerifier? = null,
) {
    suspend operator fun invoke(productId: String): VerifyResult {
        require(productId.isNotBlank()) { "productId required" }
        return runCatching { repository.verify(productId) }
            .getOrElse { backendError ->
                val fallback = chainVerifier
                    ?: throw backendError
                runCatching { fallback.verify(productId) }
                    .getOrElse { throw backendError }
            }
    }
}

interface ChainVerifier {
    suspend fun verify(productId: String): VerifyResult
}
