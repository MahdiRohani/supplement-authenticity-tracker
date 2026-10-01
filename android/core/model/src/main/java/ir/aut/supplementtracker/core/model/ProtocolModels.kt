package ir.aut.supplementtracker.core.model

/** Buyer-facing verdicts of `GET /v2/verify`. */
object Authenticity {
    const val AUTHENTIC = "Authentic"
    const val IN_TRANSIT = "InTransit"
    const val CONSUMED = "Consumed"
    const val RECALLED = "Recalled"
    const val SUSPICIOUS = "Suspicious"
}

/** Custody state of a segment (a contiguous index range of one batch). */
object SegmentStatus {
    const val CREATED = "Created"
    const val TRANSFERRED = "Transferred"
    const val AT_POINT_OF_SALE = "AtPointOfSale"
    const val INVALID = "Invalid"
}

object RiskLevel {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
}

/** Rule codes emitted by the backend risk engine. */
object RiskReasonCode {
    const val MANY_DEVICES = "many_devices"
    const val SCANNED_AFTER_CONSUMPTION = "scanned_after_consumption"
    const val FOREIGN_REGION = "foreign_region"
    const val REGION_SPREAD = "region_spread"
}

data class Party(
    val address: String,
    val role: String? = null,
    val displayName: String? = null,
    val region: String? = null,
)

data class Page<T>(
    val page: Int,
    val limit: Int,
    val total: Int,
    val totalPages: Int,
    val items: List<T>,
)

data class Eip712Domain(
    val name: String,
    val version: String,
    val chainId: Long,
    val verifyingContract: String,
)

/** `GET /v2/chains`: what a client needs to sign and to cross-check the chain. */
data class ProtocolInfo(
    val chainId: Long,
    val registryAddress: String,
    val publicVerifyBaseUrl: String,
    val domain: Eip712Domain,
)

data class NewBatchRequest(
    val name: String,
    val lotCode: String,
    val size: Int,
    val manufacturerAddress: String? = null,
    val expiresAt: String? = null,
)

data class Batch(
    val batchId: String,
    val manufacturer: String,
    val size: Int,
    val consumedCount: Int,
    val recalled: Boolean,
    val name: String? = null,
    val lotCode: String? = null,
    val merkleRoot: String = "",
    val metadataCid: String = "",
    val metadataGatewayUrl: String = "",
    val txHash: String = "",
    val createdAt: String = "",
)

/** Credentials of one unit; the secret QR exists only in the registration response. */
data class UnitCredential(
    val index: Long,
    val unitKey: String,
    val publicQr: String,
    val secretQr: String,
) {
    override fun toString(): String = "UnitCredential(index=$index, unitKey=$unitKey, secretQr=<redacted>)"
}

data class RegisteredBatch(
    val batch: Batch,
    val segmentId: String,
    val ipfsPinned: Boolean,
    val keysRevealOnce: Boolean,
    val units: List<UnitCredential>,
)

data class Segment(
    val segmentId: String,
    val batchId: String,
    val owner: String,
    val start: Long,
    val end: Long,
    val units: Int,
    val status: String,
    val updatedAt: String = "",
    /** Filled in by the client from the batch, for display. */
    val batchName: String? = null,
    val lotCode: String? = null,
) {
    /** Inclusive index range, e.g. `0–49`. */
    val lastIndex: Long get() = end - 1
}

data class BatchDetail(
    val batch: Batch,
    val segments: List<Segment>,
    val distribution: Map<String, Int>,
)

data class SegmentTransferRequest(
    val segmentId: String,
    val toAddress: String,
    val count: Int,
)

data class SegmentTransferResult(
    val batchId: String,
    val fromSegmentId: String,
    val toSegmentId: String,
    val split: Boolean,
    val from: String,
    val to: String,
    val start: Long,
    val end: Long,
    val status: String,
    val txHash: String,
) {
    val units: Long get() = end - start
}

data class RiskReason(
    val code: String,
    val weight: Double,
    val message: String,
)

data class RiskAssessment(
    val score: Double,
    val level: String,
    val reasons: List<RiskReason>,
)

data class ChainEvidence(
    val unitKey: String,
    val merkleRoot: String,
    val leaf: String,
    val proof: List<String>,
    val registerTxHash: String,
)

data class Consumption(
    val consumer: String?,
    val txHash: String,
    val blockNumber: Long?,
    val at: String?,
)

data class UnitVerification(
    val authenticity: String,
    val unit: UnitRef,
    val productName: String?,
    val lotCode: String?,
    val manufacturer: Party?,
    val metadataCid: String?,
    val metadataGatewayUrl: String?,
    val custodian: Party?,
    val segmentId: String?,
    val segmentStatus: String?,
    val consumed: Boolean,
    val consumption: Consumption?,
    val recalled: Boolean,
    val risk: RiskAssessment,
    val scanCount: Int,
    val evidence: ChainEvidence?,
    val checkedAt: String,
)

/**
 * What the device checked itself instead of trusting the API: the Merkle proof
 * against the root, and the root/consumed bit against the contract. `null` means
 * the check could not run (no evidence, or the RPC endpoint was unreachable).
 */
data class IndependentCheck(
    val proofValid: Boolean? = null,
    val rootMatchesChain: Boolean? = null,
    val consumedMatchesChain: Boolean? = null,
) {
    /** The API's evidence contradicts the math or the chain. */
    val contradicted: Boolean get() = proofValid == false || rootMatchesChain == false
}

data class VerifiedUnit(
    val verification: UnitVerification,
    val check: IndependentCheck,
)

/** On-chain state of a unit's batch as read directly from the registry. */
data class ChainAnchor(
    val merkleRoot: String,
    val consumed: Boolean,
    val recalled: Boolean,
)

/** Coarse context sent with each verification to feed clone detection. */
data class ScanContext(
    val deviceId: String,
    val region: String?,
)

data class ConsumeAuthorization(
    val chainId: Long,
    val batchId: String,
    val index: Long,
    val consumer: String,
    val deadline: Long,
    val signature: String,
)

data class UnitConsumeResult(
    val status: String,
    val batchId: String,
    val index: Long,
    val segmentId: String,
    val unitKey: String,
    val consumer: String,
    val submitter: String,
    val txHash: String,
    val blockNumber: Long,
)

data class CustodyStep(
    val from: String,
    val to: String,
    val segmentId: String,
    val status: String,
    val units: Int,
    val txHash: String,
    val blockNumber: Long,
    val at: String,
)

data class UnitHistory(
    val unit: UnitRef,
    val manufacturer: String,
    val registeredAt: String,
    val registerTxHash: String,
    val custody: List<CustodyStep>,
    val consumption: Consumption?,
    val recalled: Boolean,
)
