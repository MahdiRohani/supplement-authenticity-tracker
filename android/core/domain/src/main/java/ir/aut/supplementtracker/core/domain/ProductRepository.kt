package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.RegisterProductRequest
import ir.aut.supplementtracker.core.model.RegisteredProduct

interface ProductRepository {
    suspend fun register(request: RegisterProductRequest): RegisteredProduct
}
