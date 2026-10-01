package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.Batch
import ir.aut.supplementtracker.core.model.BatchDetail
import ir.aut.supplementtracker.core.model.NewBatchRequest
import ir.aut.supplementtracker.core.model.Page
import ir.aut.supplementtracker.core.model.RegisteredBatch
import ir.aut.supplementtracker.core.model.UnitHistory
import ir.aut.supplementtracker.core.model.UnitRef

class RegisterUnitBatchUseCase(
    private val repository: ProtocolRepository,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
) {
    suspend operator fun invoke(request: NewBatchRequest): RegisteredBatch {
        if (request.name.isBlank()) throw DomainError.Validation("name must not be empty")
        if (request.size !in 1..maxSize) throw DomainError.Validation(BATCH_SIZE_OUT_OF_RANGE)
        return repository.registerBatch(
            request.copy(name = request.name.trim(), lotCode = request.lotCode.trim()),
        )
    }

    companion object {
        /** Matches the backend's default MAX_BATCH_UNITS. */
        const val DEFAULT_MAX_SIZE = 5_000
        const val BATCH_SIZE_OUT_OF_RANGE = "BATCH_SIZE_OUT_OF_RANGE"
    }
}

class ListBatchesUseCase(private val repository: ProtocolRepository) {
    suspend operator fun invoke(manufacturer: String?): Page<Batch> =
        repository.listBatches(manufacturer?.takeIf { it.isNotBlank() })
}

/** Labels for the units whose secret QRs the app still holds from registration. */
class RenderBatchLabelsUseCase(private val repository: ProtocolRepository) {
    suspend operator fun invoke(batch: RegisteredBatch): ByteArray {
        if (batch.units.isEmpty()) throw DomainError.Validation(KEYS_ALREADY_DISCARDED)
        return repository.renderLabels(batch.batch.batchId, batch.units.map { it.secretQr })
    }

    companion object {
        const val KEYS_ALREADY_DISCARDED = "KEYS_ALREADY_DISCARDED"
    }
}

class GetBatchDetailUseCase(private val repository: ProtocolRepository) {
    suspend operator fun invoke(batchId: String): BatchDetail = repository.getBatch(batchId)
}

class GetUnitHistoryUseCase(private val repository: ProtocolRepository) {
    suspend operator fun invoke(unit: UnitRef): UnitHistory = repository.history(unit)
}
