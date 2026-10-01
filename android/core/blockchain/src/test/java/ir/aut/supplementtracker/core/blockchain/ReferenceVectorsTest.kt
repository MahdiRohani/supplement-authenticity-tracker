package ir.aut.supplementtracker.core.blockchain

import ir.aut.supplementtracker.core.model.Eip712Domain
import ir.aut.supplementtracker.core.model.SecretLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.io.File

/**
 * Checks the app's Merkle and EIP-712 code against the vectors generated from
 * SupplementRegistryV2 (packages/abis/test-vectors), which the contract and
 * backend tests also use.
 */
class ReferenceVectorsTest {
    private val vectors = JSONObject(File(System.getProperty("sat.vectors")!!).readText())
    private val domain =
        vectors.getJSONObject("domain").let {
            Eip712Domain(
                name = it.getString("name"),
                version = it.getString("version"),
                chainId = it.getString("chainId").toLong(),
                verifyingContract = it.getString("verifyingContract"),
            )
        }

    @Test
    fun merkleLeavesAndProofsMatchTheContract() {
        val verifier = UnitMerkleVerifier()
        val batches = vectors.getJSONArray("batches")
        var checked = 0
        for (b in 0 until batches.length()) {
            val batch = batches.getJSONObject(b)
            val root = batch.getString("root")
            val units = batch.getJSONArray("units")
            for (u in 0 until units.length()) {
                val unit = units.getJSONObject(u)
                val index = unit.getLong("index")
                val address = unit.getString("address")
                val proof = unit.getJSONArray("proof").let { p -> List(p.length()) { p.getString(it) } }
                assertEquals(unit.getString("leaf"), Numeric.toHexString(UnitMerkleVerifier.leaf(index, address)))
                assertTrue("batch ${batch.getString("seed")} unit $index", verifier.verify(index, address, proof, root))
                assertFalse(verifier.verify(index + 1, address, proof, root))
                if (proof.isNotEmpty()) {
                    assertFalse(verifier.verify(index, address, proof.drop(1), root))
                }
                checked++
            }
        }
        assertEquals(19, checked)
    }

    @Test
    fun consumeAuthorizationDigestAndSignatureMatchTheContract() {
        val signer = Eip712ConsumeSigner()
        val authorizations = vectors.getJSONArray("authorizations")
        for (i in 0 until authorizations.length()) {
            val auth = authorizations.getJSONObject(i)
            val batchId = auth.getString("batchId")
            val index = auth.getLong("index")
            val consumer = auth.getString("consumer")
            val deadline = auth.getString("deadline").toLong()
            val digest = Eip712ConsumeSigner.digest(batchId, index, consumer, deadline, domain)
            assertEquals(auth.getString("digest"), Numeric.toHexString(digest))

            val key = privateKeyOf(auth.getString("batchSeed"), index)
            val label = SecretLabel.parse("satk2:${domain.chainId}:$batchId:$index:$key")!!
            val signature = signer.sign(label, consumer, deadline, domain)
            assertEquals(auth.getString("signature"), signature)

            val raw = Numeric.hexStringToByteArray(signature)
            val recovered =
                Sign.signedMessageHashToKey(
                    digest,
                    Sign.SignatureData(raw[64], raw.copyOfRange(0, 32), raw.copyOfRange(32, 64)),
                )
            assertEquals(auth.getString("unitKey"), Keys.toChecksumAddress(Keys.getAddress(recovered)))
        }
    }

    @Test
    fun signatureIsBoundToTheConsumerAndDeadline() {
        val auth = vectors.getJSONArray("authorizations").getJSONObject(0)
        val batchId = auth.getString("batchId")
        val consumer = auth.getString("consumer")
        val deadline = auth.getString("deadline").toLong()
        val reference = Eip712ConsumeSigner.digest(batchId, 0, consumer, deadline, domain)
        assertFalse(reference.contentEquals(Eip712ConsumeSigner.digest(batchId, 0, consumer, deadline + 1, domain)))
        assertFalse(
            reference.contentEquals(
                Eip712ConsumeSigner.digest(batchId, 0, "0x70997970C51812dc3A010C7d01b50e0d17dc79C8", deadline, domain),
            ),
        )
        assertFalse(reference.contentEquals(Eip712ConsumeSigner.digest(batchId, 0, consumer, deadline, domain.copy(chainId = 1))))
    }

    private fun privateKeyOf(seed: String, index: Long): String {
        val batches = vectors.getJSONArray("batches")
        for (b in 0 until batches.length()) {
            val batch = batches.getJSONObject(b)
            if (batch.getString("seed") != seed) continue
            val units = batch.getJSONArray("units")
            for (u in 0 until units.length()) {
                val unit = units.getJSONObject(u)
                if (unit.getLong("index") == index) return unit.getString("privateKey")
            }
        }
        error("no key for $seed:$index")
    }
}
