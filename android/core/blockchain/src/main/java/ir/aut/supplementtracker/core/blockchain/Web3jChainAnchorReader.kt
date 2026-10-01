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
                require(batchWords.size >= BATCH_WORDS) { "unexpected getBatch result" }
                val consumedWords =
                    call(web3j, registry, Function("isConsumed", listOf(id, Uint32(index)), emptyList()))
                ChainAnchor(
                    merkleRoot = Numeric.toHexString(batchWords[0]),
                    consumed = consumedWords.firstOrNull()?.let { BigInteger(1, it).signum() != 0 } ?: false,
                    recalled = BigInteger(1, batchWords[BATCH_INVALID_WORD]).signum() != 0,
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
        val raw = Numeric.hexStringToByteArray(response.value ?: "0x")
        return (0 until raw.size / 32).map { raw.copyOfRange(it * 32, it * 32 + 32) }
    }

    private companion object {
        // Batch {merkleRoot, metadataHash, physicalBatchId, manufacturer, size, consumedCount, invalid}
        const val BATCH_WORDS = 7
        const val BATCH_INVALID_WORD = 6
    }
}
