package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.Batch
import ir.aut.supplementtracker.core.model.BatchDetail
import ir.aut.supplementtracker.core.model.ChainAnchor
import ir.aut.supplementtracker.core.model.ConsumeAuthorization
import ir.aut.supplementtracker.core.model.Eip712Domain
import ir.aut.supplementtracker.core.model.NewBatchRequest
import ir.aut.supplementtracker.core.model.Page
import ir.aut.supplementtracker.core.model.ProtocolInfo
import ir.aut.supplementtracker.core.model.RegisteredBatch
import ir.aut.supplementtracker.core.model.ScanContext
import ir.aut.supplementtracker.core.model.SecretLabel
import ir.aut.supplementtracker.core.model.Segment
import ir.aut.supplementtracker.core.model.SegmentTransferRequest
import ir.aut.supplementtracker.core.model.SegmentTransferResult
import ir.aut.supplementtracker.core.model.UnitConsumeResult
import ir.aut.supplementtracker.core.model.UnitHistory
import ir.aut.supplementtracker.core.model.UnitRef
import ir.aut.supplementtracker.core.model.UnitVerification

/** Unit-level protocol (SupplementRegistryV2) served under `/v2`. */
interface ProtocolRepository {
    suspend fun info(): ProtocolInfo

    suspend fun registerBatch(request: NewBatchRequest): RegisteredBatch

    suspend fun listBatches(manufacturer: String?, page: Int = 1, limit: Int = 50): Page<Batch>

    suspend fun getBatch(batchId: String): BatchDetail

    suspend fun listSegments(owner: String?, batchId: String? = null, page: Int = 1, limit: Int = 100): Page<Segment>

    suspend fun transfer(request: SegmentTransferRequest): SegmentTransferResult

    suspend fun verify(unit: UnitRef, context: ScanContext): UnitVerification

    suspend fun history(unit: UnitRef): UnitHistory

    suspend fun consume(authorization: ConsumeAuthorization): UnitConsumeResult

    /** Labels PDF; the backend only prints secret QRs whose keys are committed on-chain. */
    suspend fun renderLabels(batchId: String, secretQrs: List<String>): ByteArray
}

/** Signs the EIP-712 `ConsumeAuthorization` with the unit key from the hidden label. */
interface ConsumeSigner {
    fun sign(label: SecretLabel, consumer: String, deadline: Long, domain: Eip712Domain): String
}

/** The buyer's pseudonymous on-chain identity; it holds no funds and needs no gas. */
fun interface ConsumerIdentity {
    fun address(): String
}

fun interface ScanContextProvider {
    fun current(): ScanContext
}

fun interface MerkleProofVerifier {
    fun verify(index: Long, unitKey: String, proof: List<String>, root: String): Boolean
}

/** Reads a batch's root and a unit's consumed bit straight from the registry contract. */
interface ChainAnchorReader {
    suspend fun read(registry: String, batchId: String, index: Long): ChainAnchor
}
