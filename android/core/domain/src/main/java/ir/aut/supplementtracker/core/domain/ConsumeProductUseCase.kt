package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.ConsumeRequest
import ir.aut.supplementtracker.core.model.ConsumeResult

class ConsumeProductUseCase(
    private val repository: ProductRepository,
) {
    suspend operator fun invoke(request: ConsumeRequest): ConsumeResult {
        require(request.productId.isNotBlank()) { "productId required" }
        require(request.secret.isNotBlank()) { "secret required" }
        return repository.consume(request)
    }
}
