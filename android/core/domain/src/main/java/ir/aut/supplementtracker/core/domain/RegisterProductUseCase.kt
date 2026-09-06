package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.RegisterProductRequest
import ir.aut.supplementtracker.core.model.RegisteredProduct

class RegisterProductUseCase(
    private val repository: ProductRepository,
) {
    suspend operator fun invoke(request: RegisterProductRequest): RegisteredProduct {
        require(request.name.isNotBlank()) { "name required" }
        return repository.register(request)
    }
}
