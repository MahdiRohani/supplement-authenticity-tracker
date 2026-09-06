package ir.aut.supplementtracker.core.model

data class RegisterProductRequest(
    val name: String,
    val batch: String,
)

data class RegisteredProduct(
    val id: String,
    val chainProductId: String,
    val metadataCid: String?,
    val secret: String?,
    val status: String,
)
