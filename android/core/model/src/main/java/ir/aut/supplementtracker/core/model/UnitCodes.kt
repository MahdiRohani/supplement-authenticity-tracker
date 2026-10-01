package ir.aut.supplementtracker.core.model

private const val MAX_INDEX = 0xFFFF_FFFFL
private val DECIMAL = Regex("^[0-9]{1,30}$")
private val PRIVATE_KEY_HEX = Regex("^[0-9a-fA-F]{64}$")
private val LEGACY_LINK = Regex("^(?:https?://[^/]+/verify|supplementtracker://verify)/([0-9]+)/?$")

/** One physical unit of a Merkle-registered batch: `(chainId, batchId, index)`. */
data class UnitRef(
    val chainId: Long,
    val batchId: String,
    val index: Long,
) {
    init {
        require(chainId > 0) { "chainId must be positive" }
        require(DECIMAL.matches(batchId) && batchId.trimStart('0').isNotEmpty()) { "batchId must be a positive integer" }
        require(index in 0..MAX_INDEX) { "index must fit in uint32" }
    }

    /** `chainId/batchId/index`, the path suffix of the public verify URL. */
    val path: String get() = "$chainId/$batchId/$index"

    fun publicUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/$path"

    companion object {
        /**
         * Accepts the public label (`https://…/u/{chainId}/{batchId}/{index}`) from any
         * host, since the verify base URL is deployment configuration, or the bare
         * `chainId/batchId/index` path.
         */
        fun parse(raw: String): UnitRef? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("$SECRET_SCHEME:")) return null
            val path =
                if (trimmed.contains("://")) {
                    trimmed.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
                } else {
                    trimmed
                }
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            if (!trimmed.contains("://") && parts.size != 3) return null
            val (chain, batch, index) = parts.takeLast(3)
            return of(chain, batch, index)
        }

        internal fun of(chain: String, batch: String, index: String): UnitRef? {
            if (!DECIMAL.matches(chain) || !DECIMAL.matches(batch) || !DECIMAL.matches(index)) return null
            val chainId = chain.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val idx = index.toLongOrNull()?.takeIf { it in 0..MAX_INDEX } ?: return null
            val batchId = batch.trimStart('0').ifEmpty { return null }
            return UnitRef(chainId, batchId, idx)
        }
    }
}

internal const val SECRET_SCHEME = "satk2"

/**
 * The hidden label under the scratch-off: `satk2:{chainId}:{batchId}:{index}:{privateKeyHex}`.
 * The key signs the one-time consume authorization, so [toString] never prints it.
 */
class SecretLabel private constructor(
    val unit: UnitRef,
    val privateKeyHex: String,
) {
    val chainId: Long get() = unit.chainId
    val batchId: String get() = unit.batchId
    val index: Long get() = unit.index

    override fun equals(other: Any?): Boolean =
        other is SecretLabel && other.unit == unit && other.privateKeyHex == privateKeyHex

    override fun hashCode(): Int = 31 * unit.hashCode() + privateKeyHex.hashCode()

    override fun toString(): String = "SecretLabel(${unit.path}, key=<redacted>)"

    companion object {
        fun parse(raw: String): SecretLabel? {
            val parts = raw.trim().split(':')
            if (parts.size != 5 || parts[0] != SECRET_SCHEME) return null
            val unit = UnitRef.of(parts[1], parts[2], parts[3]) ?: return null
            val key = parts[4].removePrefix("0x")
            if (!PRIVATE_KEY_HEX.matches(key) || key.all { it == '0' }) return null
            return SecretLabel(unit, key.lowercase())
        }
    }
}

/** Anything the scanner or the verify field can receive. */
sealed interface ScannedCode {
    /** The open label of a batch unit. */
    data class Public(val ref: UnitRef) : ScannedCode

    /** The scratch-off label; it also identifies its unit. */
    data class Hidden(val label: SecretLabel) : ScannedCode

    /** v1 single-product label: `{"v":1,"productId":…}` or a bare product id. */
    data class Legacy(val productId: String) : ScannedCode

    companion object {
        fun parse(raw: String): ScannedCode? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            SecretLabel.parse(trimmed)?.let { return Hidden(it) }
            UnitRef.parse(trimmed)?.let { return Public(it) }
            LEGACY_LINK.find(trimmed)?.let { return Legacy(it.groupValues[1]) }
            return QrPayload.parse(trimmed)?.let { Legacy(it.productId) }
        }
    }
}
