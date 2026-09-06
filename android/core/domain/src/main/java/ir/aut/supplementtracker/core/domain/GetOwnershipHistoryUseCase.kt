package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.OwnershipHistory

class GetOwnershipHistoryUseCase(
    private val repository: ProductRepository,
) {
    suspend operator fun invoke(productId: String): OwnershipHistory {
        require(productId.isNotBlank()) { "productId required" }
        return repository.history(productId)
    }
}
