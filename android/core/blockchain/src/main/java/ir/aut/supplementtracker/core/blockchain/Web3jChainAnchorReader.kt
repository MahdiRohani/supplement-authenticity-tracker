package ir.aut.supplementtracker.core.blockchain

import ir.aut.supplementtracker.core.domain.ChainAnchorReader
import ir.aut.supplementtracker.core.model.ChainAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Reads `getBatch(batchId)` and `isConsumed(batchId, index)` from the registry
 * over plain JSON-RPC, so the verify screen can compare the API's evidence with
 * the contract without trusting the backend.
 */
class Web3jChainAnchorReader(
    private val rpcUrl: String = BuildConfig.RPC_URL,
) : ChainAnchorReader {
    override suspend fun read(registry: String, batchId: String, index: Long): ChainAnchor =
        withContext(Dispatchers.IO) {
            val web3j = Web3j.build(HttpService(rpcUrl))
            try {
                val id = Uint256(BigInteger(batchId))
                val batchWords = call(web3j, registry, Function("getBatch", listOf(id), emptyList()))
                val head = decodeBatchHead(batchWords)
                val consumedWords =
                    call(web3j, registry, Function("isConsumed", listOf(id, Uint32(index)), emptyList()))
                ChainAnchor(
                    merkleRoot = head.merkleRoot,
                    consumed = consumedWords.firstOrNull()?.let { BigInteger(1, it).signum() != 0 } ?: false,
                    recalled = head.recalled,
                )
            } finally {
                web3j.shutdown()
            }
        }

    private fun call(web3j: Web3j, to: String, function: Function): List<ByteArray> {
        val response =
            web3j.ethCall(
                Transaction.createEthCallTransaction(null, to, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST,
            ).send()
        if (response.hasError() || response.isReverted) {
            error(response.error?.message ?: response.revertReason ?: "eth_call reverted")
        }
        return words32(response.value ?: "0x")
    }

    internal companion object {
        // Batch ends with `string metadataCid`, so ABI encoding prefixes the tuple
        // with a 0x20 offset word before merkleRoot … invalid.
        const val TUPLE_OFFSET = 1
        const val BATCH_HEAD_WORDS = 7
        const val BATCH_INVALID_WORD = 6

        data class BatchHead(val merkleRoot: String, val recalled: Boolean)

        fun words32(hex: String): List<ByteArray> {
            val raw = Numeric.hexStringToByteArray(hex)
            return (0 until raw.size / 32).map { raw.copyOfRange(it * 32, it * 32 + 32) }
        }

        fun decodeBatchHead(batchWords: List<ByteArray>): BatchHead {
            require(batchWords.size >= TUPLE_OFFSET + BATCH_HEAD_WORDS) {
                "unexpected getBatch result"
            }
            return BatchHead(
                merkleRoot = Numeric.toHexString(batchWords[TUPLE_OFFSET]),
                recalled = BigInteger(1, batchWords[TUPLE_OFFSET + BATCH_INVALID_WORD]).signum() != 0,
            )
        }
    }
}
