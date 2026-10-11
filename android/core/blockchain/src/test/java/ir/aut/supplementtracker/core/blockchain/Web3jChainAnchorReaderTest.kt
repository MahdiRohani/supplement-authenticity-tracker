package ir.aut.supplementtracker.core.blockchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `getBatch` returns a struct that includes a dynamic `string`, so the ABI
 * head starts with an offset word. Reading word 0 as merkleRoot was the bug
 * behind "Evidence does not match the blockchain" on every local verify.
 */
class Web3jChainAnchorReaderTest {
    @Test
    fun decodeBatchHeadSkipsAbiOffsetWord() {
        // Real eth_call shape from SupplementRegistryV2.getBatch(1) on Hardhat:
        // offset(0x20), merkleRoot, metadataHash, physicalBatchId, manufacturer,
        // size, consumedCount, invalid=false, then the string tail.
        val hex =
            "0x" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "5123d4b4fd75f34f4c8a786967d06a121808dd9f1396dacd63fbcd18d455c8ce" +
                "c67b8f116a868af404ae38399903139caf32bc481937e0adacf4af387029913b" +
                "05fce51234567890abcdef01234567890abcdef01234567890abcdef01234567" +
                "000000000000000000000000f39fd6e51aad88f6f4ce6ab8827279cfffb92266" +
                "0000000000000000000000000000000000000000000000000000000000000005" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000100"

        val head = Web3jChainAnchorReader.decodeBatchHead(Web3jChainAnchorReader.words32(hex))
        assertEquals(
            "0x5123d4b4fd75f34f4c8a786967d06a121808dd9f1396dacd63fbcd18d455c8ce",
            head.merkleRoot,
        )
        assertFalse(head.recalled)
    }

    @Test
    fun decodeBatchHeadReadsRecalledFromInvalidFlag() {
        val hex =
            "0x" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000001"

        val head = Web3jChainAnchorReader.decodeBatchHead(Web3jChainAnchorReader.words32(hex))
        assertEquals(
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            head.merkleRoot,
        )
        assertTrue(head.recalled)
    }
}
