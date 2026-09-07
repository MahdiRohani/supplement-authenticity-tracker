package ir.aut.supplementtracker.core.blockchain

import ir.aut.supplementtracker.core.domain.ChainVerifier
import ir.aut.supplementtracker.core.model.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import java.math.BigInteger

class Web3jChainVerifier(
    private val rpcUrl: String = BuildConfig.RPC_URL,
    private val registryAddress: String = BuildConfig.REGISTRY_ADDRESS,
) : ChainVerifier {
    override suspend fun verify(productId: String): VerifyResult =
        withContext(Dispatchers.IO) {
            val web3j = Web3j.build(HttpService(rpcUrl))
            try {
                val function =
                    Function(
                        "getProductStatus",
                        listOf(Uint256(BigInteger(productId))),
                        listOf(
                            object : TypeReference<Uint8>() {},
                            object : TypeReference<Address>() {},
                            object : TypeReference<Utf8String>() {},
                        ),
                    )
                val encoded = FunctionEncoder.encode(function)
                val response =
                    web3j
                        .ethCall(
                            org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                                null,
                                registryAddress,
                                encoded,
                            ),
                            DefaultBlockParameterName.LATEST,
                        ).send()
                if (response.hasError()) {
                    error(response.error.message)
                }
                @Suppress("UNCHECKED_CAST")
                val decoded =
                    FunctionReturnDecoder.decode(
                        response.value,
                        function.outputParameters as List<TypeReference<Type<*>>>,
                    )
                val statusCode = (decoded[0] as Uint8).value.toInt()
                val owner = (decoded[1] as Address).value
                val metadataCid = (decoded[2] as Utf8String).value
                val status = statusName(statusCode)
                val authenticity = authenticityFor(status)
                VerifyResult(
                    productId = productId,
                    chainProductId = productId,
                    status = status,
                    authenticity = authenticity,
                    currentOwner = owner,
                    metadataCid = metadataCid.ifBlank { null },
                    metadata = null,
                    source = "chain",
                    message = if (authenticity == "Consumed") {
                        "Product already consumed; a second use or refill is not authentic"
                    } else {
                        null
                    },
                )
            } finally {
                web3j.shutdown()
            }
        }

    private fun statusName(code: Int): String =
        when (code) {
            0 -> "Created"
            1 -> "Transferred"
            2 -> "AtPointOfSale"
            3 -> "Consumed"
            4 -> "Invalid"
            else -> "Invalid"
        }

    private fun authenticityFor(status: String): String =
        when (status) {
            "Consumed" -> "Consumed"
            "Invalid" -> "Invalid"
            else -> "Authentic"
        }
}
