package ir.aut.supplementtracker.core.blockchain

import ir.aut.supplementtracker.core.domain.ChainWriter
import ir.aut.supplementtracker.core.domain.DomainError
import ir.aut.supplementtracker.core.model.ConsumeRequest
import ir.aut.supplementtracker.core.model.ConsumeResult
import ir.aut.supplementtracker.core.model.TransferRequest
import ir.aut.supplementtracker.core.model.TransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Numeric
import java.math.BigInteger

class ManagedKeyChainWriter(
    private val rpcUrl: String = BuildConfig.RPC_URL,
    private val registryAddress: String = BuildConfig.REGISTRY_ADDRESS,
    private val chainId: Long = BuildConfig.CHAIN_ID.toLongOrNull() ?: 31337L,
    privateKey: String = BuildConfig.MANAGED_PRIVATE_KEY,
) : ChainWriter {
    private val credentials: Credentials = Credentials.create(privateKey)

    override suspend fun consume(request: ConsumeRequest): ConsumeResult =
        withContext(Dispatchers.IO) {
            val secretBytes = parseBytes32(request.secret)
            val function =
                Function(
                    "consume",
                    listOf(
                        Uint256(BigInteger(request.productId.trim())),
                        Bytes32(secretBytes),
                    ),
                    emptyList(),
                )
            val txHash = sendFunction(function)
            ConsumeResult(
                productId = request.productId.trim(),
                chainProductId = request.productId.trim(),
                status = "Consumed",
                txHash = txHash,
                actor = credentials.address,
            )
        }

    override suspend fun transfer(request: TransferRequest): TransferResult =
        withContext(Dispatchers.IO) {
            val function =
                Function(
                    "transferOwnership",
                    listOf(
                        Uint256(BigInteger(request.productId.trim())),
                        Address(request.toAddress.trim()),
                    ),
                    emptyList(),
                )
            val txHash = sendFunction(function)
            TransferResult(
                chainProductId = request.productId.trim(),
                fromAddress = credentials.address,
                toAddress = request.toAddress.trim(),
                txHash = txHash,
            )
        }

    private fun sendFunction(function: Function): String {
        val web3j = Web3j.build(HttpService(rpcUrl))
        try {
            val encoded = FunctionEncoder.encode(function)
            val manager = RawTransactionManager(web3j, credentials, chainId)
            val response =
                manager.sendTransaction(
                    DefaultGasProvider.GAS_PRICE,
                    DefaultGasProvider.GAS_LIMIT,
                    registryAddress,
                    encoded,
                    BigInteger.ZERO,
                )
            if (response.hasError()) {
                throw DomainError.Api(
                    statusCode = 500,
                    message = response.error.message ?: "Chain transaction failed",
                )
            }
            val hash = response.transactionHash
            if (hash.isNullOrBlank()) {
                throw DomainError.Unknown("Missing transaction hash")
            }
            return hash
        } catch (error: DomainError) {
            throw error
        } catch (error: Throwable) {
            throw DomainError.Network(error.message ?: "Chain write failed", error)
        } finally {
            web3j.shutdown()
        }
    }

    private fun parseBytes32(secret: String): ByteArray {
        val hex = secret.trim().removePrefix("0x")
        val raw = Numeric.hexStringToByteArray(if (hex.startsWith("0x")) hex else "0x$hex")
        if (raw.size == 32) return raw
        if (raw.size > 32) {
            throw DomainError.Validation("secret must be 32 bytes")
        }
        val padded = ByteArray(32)
        System.arraycopy(raw, 0, padded, 32 - raw.size, raw.size)
        return padded
    }
}
