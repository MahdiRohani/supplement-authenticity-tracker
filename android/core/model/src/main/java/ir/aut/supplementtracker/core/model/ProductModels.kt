package ir.aut.supplementtracker.core.model

enum class SupplyRole {
    Manufacturer,
    Distributor,
    Pharmacy,
    Admin,
}

data class UserSession(
    val role: SupplyRole,
    val address: String,
)

data class RegisterProductRequest(
    val name: String,
    val batch: String,
    val manufacturerAddress: String? = null,
)

data class RegisteredProduct(
    val id: String,
    val chainProductId: String,
    val metadataCid: String?,
    val secret: String?,
    val status: String,
)

data class TransferRequest(
    val productId: String,
    val toAddress: String,
)

data class TransferResult(
    val chainProductId: String,
    val fromAddress: String,
    val toAddress: String,
    val txHash: String,
)

data class OwnershipEvent(
    val id: String,
    val fromAddress: String,
    val toAddress: String,
    val txHash: String,
    val blockNumber: String,
    val createdAt: String,
)

data class OwnershipHistory(
    val productId: String,
    val chainProductId: String,
    val currentOwner: String,
    val status: String,
    val elapsedMs: Long,
    val events: List<OwnershipEvent>,
)

data class QrPayload(
    val schemaVersion: Int,
    val productId: String,
    val chainId: Long,
) {
    fun encode(): String =
        """{"v":$schemaVersion,"productId":"$productId","chainId":$chainId}"""

    companion object {
        const val CURRENT_VERSION = 1

        fun parse(raw: String): QrPayload? {
            val trimmed = raw.trim()
            if (trimmed.toLongOrNull() != null) {
                return QrPayload(CURRENT_VERSION, trimmed, 31337L)
            }
            return runCatching {
                val v = Regex("\"v\"\\s*:\\s*(\\d+)").find(trimmed)?.groupValues?.get(1)?.toInt()
                    ?: return null
                val productId =
                    Regex("\"productId\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1)
                        ?: return null
                val chainId =
                    Regex("\"chainId\"\\s*:\\s*(\\d+)").find(trimmed)?.groupValues?.get(1)?.toLong()
                        ?: return null
                QrPayload(v, productId, chainId)
            }.getOrNull()
        }
    }
}

data class ProductMetadata(
    val name: String? = null,
    val batch: String? = null,
    val expiresAt: String? = null,
    val image: String? = null,
)

data class VerifyResult(
    val productId: String,
    val chainProductId: String,
    val status: String,
    val authenticity: String,
    val currentOwner: String,
    val metadataCid: String?,
    val metadataGatewayUrl: String? = null,
    val metadata: ProductMetadata?,
    val source: String,
    val message: String? = null,
)

data class ConsumeRequest(
    val productId: String,
    val secret: String,
)

data class ConsumeResult(
    val productId: String,
    val chainProductId: String,
    val status: String,
    val txHash: String,
    val actor: String,
)

data class ProductSummary(
    val id: String,
    val chainProductId: String,
    val ownerAddress: String,
    val status: String,
    val name: String? = null,
    val batchCode: String? = null,
    val metadataCid: String? = null,
    val createdAt: String,
)

data class ProductListPage(
    val page: Int,
    val limit: Int,
    val total: Int,
    val totalPages: Int,
    val items: List<ProductSummary>,
)

data class ProductListQuery(
    val owner: String? = null,
    val status: String? = null,
    val q: String? = null,
    val page: Int = 1,
    val limit: Int = 20,
)

data class RegisterBatchRequest(
    val name: String,
    val batch: String,
    val count: Int,
    val manufacturerAddress: String? = null,
)

data class RegisterBatchItem(
    val id: String,
    val chainProductId: String,
    val secret: String?,
)

data class RegisterBatchResult(
    val count: Int,
    val metadataCid: String?,
    val mintedOnChain: Boolean,
    val txHash: String?,
    val items: List<RegisterBatchItem>,
)

data class FeatureFlags(
    val reportsEnabled: Boolean = true,
    val scanEnabled: Boolean = true,
    val labelsPdfEnabled: Boolean = true,
    val analyticsEnabled: Boolean = true,
)
