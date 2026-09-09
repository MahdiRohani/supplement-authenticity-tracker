package ir.aut.supplementtracker.core.domain

class DownloadBatchLabelsPdfUseCase(
    private val repository: ProductRepository,
) {
    suspend operator fun invoke(batchCode: String): ByteArray {
        require(batchCode.isNotBlank()) { "batchCode required" }
        return repository.downloadBatchLabelsPdf(batchCode.trim())
    }
}
