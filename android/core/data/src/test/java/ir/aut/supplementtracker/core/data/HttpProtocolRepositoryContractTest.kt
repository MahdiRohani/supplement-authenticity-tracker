package ir.aut.supplementtracker.core.data

import ir.aut.supplementtracker.core.blockchain.Eip712ConsumeSigner
import ir.aut.supplementtracker.core.blockchain.UnitMerkleVerifier
import ir.aut.supplementtracker.core.domain.ConsumeUnitUseCase
import ir.aut.supplementtracker.core.domain.DomainError
import ir.aut.supplementtracker.core.domain.TransferSegmentUseCase
import ir.aut.supplementtracker.core.domain.VerifyUnitUseCase
import ir.aut.supplementtracker.core.model.NewBatchRequest
import ir.aut.supplementtracker.core.model.ScanContext
import ir.aut.supplementtracker.core.model.SecretLabel
import ir.aut.supplementtracker.core.model.SegmentTransferRequest
import ir.aut.supplementtracker.core.model.UnitRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the app's v2 client, signer and use cases against a live backend wired
 * to SupplementRegistryV2, e.g.
 * `SAT_API_BASE_URL=http://127.0.0.1:3000/v1/ SAT_DISTRIBUTOR=0x7099… SAT_PHARMACY=0x3C44… ./gradlew :core:data:testLocalDebugUnitTest`.
 * The distributor and pharmacy must be relayer-managed wallets holding their
 * on-chain roles, and the backend must not require API_WRITE_KEY.
 */
class HttpProtocolRepositoryContractTest {
    private val baseUrl = System.getenv("SAT_API_BASE_URL").orEmpty()
    private val distributor = System.getenv("SAT_DISTRIBUTOR").orEmpty()
    private val pharmacy = System.getenv("SAT_PHARMACY").orEmpty()
    private lateinit var repository: HttpProtocolRepository

    @Before
    fun setUp() {
        assumeTrue("SAT_API_BASE_URL not set", baseUrl.isNotBlank())
        assumeTrue("SAT_DISTRIBUTOR / SAT_PHARMACY not set", distributor.isNotBlank() && pharmacy.isNotBlank())
        repository = HttpProtocolRepository(baseUrl = HttpProtocolRepository.v2BaseUrl(baseUrl))
    }

    @Test
    fun batchSplitCustodyGaslessConsumeAndAntiRefill() = runBlocking {
        val info = repository.info()
        val registered = repository.registerBatch(
            NewBatchRequest(name = "Contract D3", lotCode = "CT2-${System.currentTimeMillis()}", size = 4),
        )
        assertEquals(4, registered.units.size)
        assertTrue(registered.keysRevealOnce)
        val pdf = repository.renderLabels(registered.batch.batchId, registered.units.map { it.secretQr })
        assertEquals("%PDF", String(pdf.copyOfRange(0, 4)))

        val transfer = TransferSegmentUseCase(repository)
        val toDistributor = transfer(SegmentTransferRequest(registered.segmentId, distributor, 3), available = 4)
        assertTrue(toDistributor.split)
        assertEquals(3L, toDistributor.units)
        val toPharmacy = transfer(SegmentTransferRequest(toDistributor.toSegmentId, pharmacy, 2), available = 3)
        assertEquals("AtPointOfSale", toPharmacy.status)

        val unit = UnitRef(info.chainId, registered.batch.batchId, 0)
        val verify = VerifyUnitUseCase(repository, { ScanContext("contract-buyer", "tehran") }, UnitMerkleVerifier())
        val before = verify(unit)
        assertEquals("Authentic", before.verification.authenticity)
        assertEquals(true, before.check.proofValid)

        val label = SecretLabel.parse(registered.units.first { it.index == 0L }.secretQr)!!
        val buyer = "0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc"
        val consume = ConsumeUnitUseCase(repository, Eip712ConsumeSigner(), { buyer })
        val consumed = consume(label, expected = unit)
        assertEquals("Consumed", consumed.status)
        assertEquals(buyer.lowercase(), consumed.consumer.lowercase())
        assertFalse(consumed.txHash.isBlank())

        try {
            consume(label, expected = unit)
            fail("second consume must be rejected")
        } catch (error: DomainError.Conflict) {
            assertEquals("Unit already consumed; refill is not allowed", error.message)
        }
        assertEquals("Consumed", verify(unit).verification.authenticity)

        val stillInTransit = verify(unit.copy(index = 2))
        assertEquals("InTransit", stillInTransit.verification.authenticity)
        val history = repository.history(unit)
        assertEquals(listOf(distributor.lowercase(), pharmacy.lowercase()), history.custody.map { it.to.lowercase() })
        assertEquals(consumed.txHash, history.consumption?.txHash)
    }
}
