package ir.aut.supplementtracker.core.domain

class ReportCounterfeitUseCase(
    private val repository: ProductRepository,
) {
    suspend operator fun invoke(productId: String, note: String? = null) {
        require(productId.isNotBlank()) { "productId required" }
        repository.reportCounterfeit(productId, note)
    }
}
