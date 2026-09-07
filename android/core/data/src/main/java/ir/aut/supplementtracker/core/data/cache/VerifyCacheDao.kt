package ir.aut.supplementtracker.core.data.cache

interface VerifyCacheDao {
    suspend fun upsert(entity: VerifyCacheEntity)

    suspend fun latest(limit: Int = 20): List<VerifyCacheEntity>

    suspend fun findByProductId(productId: String): VerifyCacheEntity?
}
