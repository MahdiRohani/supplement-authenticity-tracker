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
