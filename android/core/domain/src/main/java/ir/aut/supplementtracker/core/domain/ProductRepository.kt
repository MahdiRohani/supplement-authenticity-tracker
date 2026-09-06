package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.OwnershipHistory
import ir.aut.supplementtracker.core.model.RegisterProductRequest
import ir.aut.supplementtracker.core.model.RegisteredProduct
import ir.aut.supplementtracker.core.model.TransferRequest
import ir.aut.supplementtracker.core.model.TransferResult

interface ProductRepository {
    suspend fun register(request: RegisterProductRequest): RegisteredProduct

    suspend fun transfer(request: TransferRequest): TransferResult

    suspend fun history(productId: String): OwnershipHistory
}
