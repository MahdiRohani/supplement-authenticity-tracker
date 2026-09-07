package ir.aut.supplementtracker.core.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "verify_cache")
data class VerifyCacheEntity(
    @PrimaryKey val productId: String,
    val chainProductId: String,
    val status: String,
    val authenticity: String,
    val currentOwner: String,
    val metadataCid: String?,
    val name: String?,
    val batch: String?,
    val source: String,
    val message: String?,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
)
