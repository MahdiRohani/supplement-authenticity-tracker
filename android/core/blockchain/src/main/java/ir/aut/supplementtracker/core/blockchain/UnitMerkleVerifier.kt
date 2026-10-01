package ir.aut.supplementtracker.core.blockchain

import ir.aut.supplementtracker.core.domain.MerkleProofVerifier
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

/**
 * OpenZeppelin-compatible verification of a unit's batch membership:
 * `leaf = keccak256(bytes.concat(keccak256(abi.encode(uint32 index, address unitKey))))`
 * folded with sorted-pair hashing up to the batch's Merkle root.
 */
class UnitMerkleVerifier : MerkleProofVerifier {
    override fun verify(index: Long, unitKey: String, proof: List<String>, root: String): Boolean {
        var node = leaf(index, unitKey)
        for (sibling in proof) {
            node = hashPair(node, bytes32(sibling))
        }
        return node.contentEquals(bytes32(root))
    }

    companion object {
        fun leaf(index: Long, unitKey: String): ByteArray {
            require(index in 0..0xFFFF_FFFFL) { "index must fit in uint32" }
            val encoded = ByteArray(64)
            Numeric.toBytesPadded(index.toBigInteger(), 32).copyInto(encoded, 0)
            val address = Numeric.hexStringToByteArray(unitKey)
            require(address.size == 20) { "unitKey must be a 20-byte address" }
            address.copyInto(encoded, 64 - 20)
            return Hash.sha3(Hash.sha3(encoded))
        }

        private fun hashPair(a: ByteArray, b: ByteArray): ByteArray =
            if (compareUnsigned(a, b) <= 0) Hash.sha3(a + b) else Hash.sha3(b + a)

        private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
            for (i in a.indices) {
                val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (diff != 0) return diff
            }
            return 0
        }

        private fun bytes32(hex: String): ByteArray =
            Numeric.hexStringToByteArray(hex).also { require(it.size == 32) { "expected 32 bytes" } }
    }
}
