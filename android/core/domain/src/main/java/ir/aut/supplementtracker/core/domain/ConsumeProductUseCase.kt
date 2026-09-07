package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.ConsumeRequest
import ir.aut.supplementtracker.core.model.ConsumeResult

class ConsumeProductUseCase(
    private val repository: ProductRepository,
    private val chainWriter: ChainWriter? = null,
) {
    suspend operator fun invoke(request: ConsumeRequest): ConsumeResult {
        require(request.productId.isNotBlank()) { "productId required" }
        require(request.secret.isNotBlank()) { "secret required" }
        if (chainWriter != null) {
            return chainWriter.consume(request)
        }
        return repository.consume(request)
    }
}
