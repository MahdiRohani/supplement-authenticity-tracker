package ir.aut.supplementtracker.core.data

import android.content.Context
import ir.aut.supplementtracker.core.data.cache.AppDatabase
import ir.aut.supplementtracker.core.data.cache.VerifyCacheEntity
import ir.aut.supplementtracker.core.model.ProductMetadata
import ir.aut.supplementtracker.core.model.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VerifyCacheStore(
    context: Context,
) {
    private val dao = AppDatabase.get(context).verifyCacheDao()

    suspend fun save(result: VerifyResult) =
        withContext(Dispatchers.IO) {
            dao.upsert(
                VerifyCacheEntity(
                    productId = result.productId,
                    chainProductId = result.chainProductId,
                    status = result.status,
                    authenticity = result.authenticity,
                    currentOwner = result.currentOwner,
                    metadataCid = result.metadataCid,
                    name = result.metadata?.name,
                    batch = result.metadata?.batch,
                    source = result.source,
                    message = result.message,
                    cachedAt = System.currentTimeMillis(),
                ),
            )
        }

    suspend fun latest(limit: Int = 20): List<VerifyResult> =
        withContext(Dispatchers.IO) {
            dao.latest(limit).map { it.toVerifyResult() }
        }

    suspend fun find(productId: String): VerifyResult? =
        withContext(Dispatchers.IO) {
            dao.findByProductId(productId)?.toVerifyResult()
        }

    private fun VerifyCacheEntity.toVerifyResult(): VerifyResult =
        VerifyResult(
            productId = productId,
            chainProductId = chainProductId,
            status = status,
            authenticity = authenticity,
            currentOwner = currentOwner,
            metadataCid = metadataCid,
            metadata =
                if (name != null || batch != null) {
                    ProductMetadata(name = name, batch = batch)
                } else {
                    null
                },
            source = source,
            message = message,
        )
}
