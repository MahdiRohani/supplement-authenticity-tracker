package ir.aut.supplementtracker.core.blockchain

import ir.aut.supplementtracker.core.domain.ConsumeSigner
import ir.aut.supplementtracker.core.model.Eip712Domain
import ir.aut.supplementtracker.core.model.SecretLabel
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * EIP-712 `ConsumeAuthorization(uint256 batchId,uint32 index,address consumer,uint256 deadline)`
 * of SupplementRegistryV2, signed with the unit key printed under the scratch-off.
 */
class Eip712ConsumeSigner : ConsumeSigner {
    override fun sign(label: SecretLabel, consumer: String, deadline: Long, domain: Eip712Domain): String {
        val digest = digest(label.batchId, label.index, consumer, deadline, domain)
        val keyPair = ECKeyPair.create(Numeric.hexStringToByteArray(label.privateKeyHex))
        val signature = Sign.signMessage(digest, keyPair, false)
        return Numeric.toHexString(signature.r + signature.s + signature.v)
    }

    companion object {
        const val PRIMARY_TYPE = "ConsumeAuthorization"

        fun typedData(batchId: String, index: Long, consumer: String, deadline: Long, domain: Eip712Domain): String {
            fun field(name: String, type: String) = JSONObject().put("name", name).put("type", type)
            val types =
                JSONObject()
                    .put(
                        "EIP712Domain",
                        JSONArray()
                            .put(field("name", "string"))
                            .put(field("version", "string"))
                            .put(field("chainId", "uint256"))
                            .put(field("verifyingContract", "address")),
                    )
                    .put(
                        PRIMARY_TYPE,
                        JSONArray()
                            .put(field("batchId", "uint256"))
                            .put(field("index", "uint32"))
                            .put(field("consumer", "address"))
                            .put(field("deadline", "uint256")),
                    )
            return JSONObject()
                .put("types", types)
                .put("primaryType", PRIMARY_TYPE)
                .put(
                    "domain",
                    JSONObject()
                        .put("name", domain.name)
                        .put("version", domain.version)
                        .put("chainId", domain.chainId)
                        .put("verifyingContract", domain.verifyingContract),
                )
                .put(
                    "message",
                    JSONObject()
                        .put("batchId", BigInteger(batchId).toString())
                        .put("index", index)
                        .put("consumer", consumer)
                        .put("deadline", deadline.toString()),
                )
                .toString()
        }

        fun digest(batchId: String, index: Long, consumer: String, deadline: Long, domain: Eip712Domain): ByteArray =
            StructuredDataEncoder(typedData(batchId, index, consumer, deadline, domain)).hashStructuredData()
    }
}
