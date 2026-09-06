package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.TransferRequest
import ir.aut.supplementtracker.core.model.TransferResult

class TransferProductUseCase(
    private val repository: ProductRepository,
) {
    suspend operator fun invoke(request: TransferRequest): TransferResult {
        require(request.productId.isNotBlank()) { "productId required" }
        require(request.toAddress.startsWith("0x")) { "toAddress required" }
        return repository.transfer(request)
    }
}
