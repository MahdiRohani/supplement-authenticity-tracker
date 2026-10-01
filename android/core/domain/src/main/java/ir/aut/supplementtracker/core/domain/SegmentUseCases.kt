package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.Segment
import ir.aut.supplementtracker.core.model.SegmentStatus
import ir.aut.supplementtracker.core.model.SegmentTransferRequest
import ir.aut.supplementtracker.core.model.SegmentTransferResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The custody segments an address holds, labelled with their batch's product name. */
class ListCustodySegmentsUseCase(private val repository: ProtocolRepository) {
    suspend operator fun invoke(owner: String): List<Segment> {
        if (owner.isBlank()) throw DomainError.Validation("OWNER_ADDRESS_REQUIRED")
        val segments =
            repository.listSegments(owner = owner).items
                .filter { it.status != SegmentStatus.INVALID && it.units > 0 }
        val batches =
            coroutineScope {
                segments.map { it.batchId }.distinct()
                    .map { id -> async { id to runCatching { repository.getBatch(id).batch }.getOrNull() } }
                    .map { it.await() }
                    .toMap()
            }
        return segments
            .map { segment ->
                val batch = batches[segment.batchId]
                segment.copy(batchName = batch?.name, lotCode = batch?.lotCode)
            }
            .sortedWith(compareByDescending<Segment> { it.batchId.toBigInteger() }.thenBy { it.start })
    }
}

/** Moves the first [SegmentTransferRequest.count] units of a segment; the rest stay put. */
class TransferSegmentUseCase(private val repository: ProtocolRepository) {
    suspend operator fun invoke(request: SegmentTransferRequest, available: Int? = null): SegmentTransferResult {
        if (!EVM_ADDRESS.matches(request.toAddress.trim())) {
            throw DomainError.Validation("toAddress must be a 0x address")
        }
        if (request.count < 1 || (available != null && request.count > available)) {
            throw DomainError.Validation(TRANSFER_COUNT_OUT_OF_RANGE)
        }
        return repository.transfer(request.copy(toAddress = request.toAddress.trim()))
    }

    companion object {
        const val TRANSFER_COUNT_OUT_OF_RANGE = "TRANSFER_COUNT_OUT_OF_RANGE"
        private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    }
}
