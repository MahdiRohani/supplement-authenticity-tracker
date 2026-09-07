package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.RegisterBatchRequest
import ir.aut.supplementtracker.core.model.RegisterBatchResult

class RegisterBatchUseCase(
    private val repository: ProductRepository,
) {
    suspend operator fun invoke(request: RegisterBatchRequest): RegisterBatchResult {
        require(request.name.isNotBlank()) { "name required" }
        require(request.count in 1..100) { "count must be between 1 and 100" }
        return repository.registerBatch(request)
    }
}
