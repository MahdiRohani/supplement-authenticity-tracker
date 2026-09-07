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
