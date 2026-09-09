package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.ConsumeRequest
import ir.aut.supplementtracker.core.model.ConsumeResult
import ir.aut.supplementtracker.core.model.FeatureFlags
import ir.aut.supplementtracker.core.model.OwnershipHistory
import ir.aut.supplementtracker.core.model.ProductListPage
import ir.aut.supplementtracker.core.model.ProductListQuery
import ir.aut.supplementtracker.core.model.RegisterBatchRequest
import ir.aut.supplementtracker.core.model.RegisterBatchResult
import ir.aut.supplementtracker.core.model.RegisterProductRequest
import ir.aut.supplementtracker.core.model.RegisteredProduct
import ir.aut.supplementtracker.core.model.TransferRequest
import ir.aut.supplementtracker.core.model.TransferResult
import ir.aut.supplementtracker.core.model.VerifyResult

interface ProductRepository {
    suspend fun register(request: RegisterProductRequest): RegisteredProduct

    suspend fun registerBatch(request: RegisterBatchRequest): RegisterBatchResult

    suspend fun list(query: ProductListQuery): ProductListPage

    suspend fun transfer(request: TransferRequest): TransferResult

    suspend fun consume(request: ConsumeRequest): ConsumeResult

    suspend fun history(productId: String): OwnershipHistory

    suspend fun verify(productId: String): VerifyResult

    suspend fun getFeatureFlags(): FeatureFlags

    suspend fun reportCounterfeit(productId: String, note: String? = null)

    suspend fun downloadBatchLabelsPdf(batchCode: String): ByteArray
}
