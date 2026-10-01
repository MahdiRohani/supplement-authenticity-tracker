package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.Batch
import ir.aut.supplementtracker.core.model.BatchDetail
import ir.aut.supplementtracker.core.model.ChainAnchor
import ir.aut.supplementtracker.core.model.ChainEvidence
import ir.aut.supplementtracker.core.model.ConsumeAuthorization
import ir.aut.supplementtracker.core.model.Eip712Domain
import ir.aut.supplementtracker.core.model.NewBatchRequest
import ir.aut.supplementtracker.core.model.Page
import ir.aut.supplementtracker.core.model.ProtocolInfo
import ir.aut.supplementtracker.core.model.RegisteredBatch
import ir.aut.supplementtracker.core.model.RiskAssessment
import ir.aut.supplementtracker.core.model.ScanContext
import ir.aut.supplementtracker.core.model.SecretLabel
import ir.aut.supplementtracker.core.model.Segment
import ir.aut.supplementtracker.core.model.SegmentTransferRequest
import ir.aut.supplementtracker.core.model.SegmentTransferResult
import ir.aut.supplementtracker.core.model.UnitConsumeResult
import ir.aut.supplementtracker.core.model.UnitHistory
import ir.aut.supplementtracker.core.model.UnitRef
import ir.aut.supplementtracker.core.model.UnitVerification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val ROOT = "0xa3bd8bd5d211b00094dec8ef96a19a60fa4cbf3b60dc8fb6f2d9a814f6f62e9c"
private const val KEY = "e6b32d5f7d9b973cae55df813ef956024bc1116f50ff2ca4cf88c943ca4ac4b3"

class ProtocolUseCasesTest {
    private val unit = UnitRef(31337, "4", 2)
    private val repo = FakeProtocol()

    @Test
    fun verifyPassesScanContextAndConfirmsEvidenceIndependently() = runBlocking {
        val anchor = FakeAnchor(ChainAnchor(ROOT.uppercase().replace("0X", "0x"), consumed = false, recalled = false))
        val verify = VerifyUnitUseCase(
            repo,
            scanContext = { ScanContext("device-1", "tehran") },
            proofVerifier = { _, _, _, root -> root == ROOT },
            anchorReader = anchor,
        )
        val result = verify(unit)
        assertEquals(ScanContext("device-1", "tehran"), repo.lastContext)
        assertEquals(true, result.check.proofValid)
        assertEquals(true, result.check.rootMatchesChain)
        assertEquals(true, result.check.consumedMatchesChain)
        assertFalse(result.check.contradicted)
        assertEquals(listOf(FakeProtocol.REGISTRY, "4", "2"), anchor.lastRead)
    }

    @Test
    fun verifyFlagsEvidenceThatContradictsTheChain() = runBlocking {
        val verify = VerifyUnitUseCase(
            repo,
            scanContext = { ScanContext("d", null) },
            proofVerifier = { _, _, _, _ -> true },
            anchorReader = FakeAnchor(ChainAnchor("0x" + "11".repeat(32), consumed = true, recalled = false)),
        )
        val check = verify(unit).check
        assertEquals(false, check.rootMatchesChain)
        assertEquals(false, check.consumedMatchesChain)
        assertTrue(check.contradicted)
    }

    @Test
    fun verifyDegradesToUncheckedWhenTheChainIsUnreachable() = runBlocking {
        val verify = VerifyUnitUseCase(
            repo,
            scanContext = { ScanContext("d", null) },
            anchorReader = object : ChainAnchorReader {
                override suspend fun read(registry: String, batchId: String, index: Long): ChainAnchor =
                    throw IllegalStateException("rpc down")
            },
        )
        val check = verify(unit).check
        assertNull(check.proofValid)
        assertNull(check.rootMatchesChain)
        assertFalse(check.contradicted)
    }

    @Test
    fun consumeSignsAShortLivedAuthorizationForTheBuyer() = runBlocking {
        val signer = RecordingSigner()
        val consume = ConsumeUnitUseCase(
            repo,
            signer,
            identity = { "0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc" },
            nowSeconds = { 1_000L },
        )
        val label = SecretLabel.parse("satk2:31337:4:2:$KEY")!!
        val result = consume(label, expected = unit)

        assertEquals("Consumed", result.status)
        val auth = repo.lastConsume!!
        assertEquals("4", auth.batchId)
        assertEquals(2L, auth.index)
        assertEquals(31337L, auth.chainId)
        assertEquals("0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc", auth.consumer)
        assertEquals(1_000L + ConsumeUnitUseCase.DEFAULT_VALIDITY_SECONDS, auth.deadline)
        assertEquals("0xsig", auth.signature)
        assertEquals(FakeProtocol.DOMAIN, signer.domain)
    }

    @Test
    fun consumeRejectsAHiddenLabelFromAnotherUnitOrChain() = runBlocking {
        val consume = ConsumeUnitUseCase(repo, RecordingSigner(), identity = { "0x01" })
        expectValidation(ConsumeUnitUseCase.HIDDEN_LABEL_MISMATCH) {
            consume(SecretLabel.parse("satk2:31337:4:3:$KEY")!!, expected = unit)
        }
        expectValidation(ConsumeUnitUseCase.WRONG_CHAIN) {
            consume(SecretLabel.parse("satk2:1:4:2:$KEY")!!)
        }
        assertNull(repo.lastConsume)
    }

    @Test
    fun registerBatchValidatesSize() = runBlocking {
        val register = RegisterUnitBatchUseCase(repo, maxSize = 10)
        expectValidation(RegisterUnitBatchUseCase.BATCH_SIZE_OUT_OF_RANGE) {
            register(NewBatchRequest(name = "D3", lotCode = "L", size = 11))
        }
        expectValidation(RegisterUnitBatchUseCase.BATCH_SIZE_OUT_OF_RANGE) {
            register(NewBatchRequest(name = "D3", lotCode = "L", size = 0))
        }
        assertEquals(3, register(NewBatchRequest(name = " D3 ", lotCode = "L", size = 3)).units.size)
        assertEquals("D3", repo.lastBatchRequest!!.name)
    }

    @Test
    fun transferValidatesCountAgainstTheSegment() = runBlocking {
        val transfer = TransferSegmentUseCase(repo)
        val to = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
        expectValidation(TransferSegmentUseCase.TRANSFER_COUNT_OUT_OF_RANGE) {
            transfer(SegmentTransferRequest("1", to, 0), available = 5)
        }
        expectValidation(TransferSegmentUseCase.TRANSFER_COUNT_OUT_OF_RANGE) {
            transfer(SegmentTransferRequest("1", to, 6), available = 5)
        }
        expectValidation("toAddress must be a 0x address") {
            transfer(SegmentTransferRequest("1", "bob", 1), available = 5)
        }
        assertTrue(transfer(SegmentTransferRequest("1", " $to ", 2), available = 5).split)
    }

    @Test
    fun custodySegmentsAreNamedAfterTheirBatch() = runBlocking {
        val segments = ListCustodySegmentsUseCase(repo)("0xowner")
        assertEquals(listOf("9", "4"), segments.map { it.batchId })
        assertEquals("Vitamin D3", segments.last().batchName)
        assertEquals("LOT-4", segments.last().lotCode)
    }

    private inline fun expectValidation(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected validation error $message")
        } catch (error: DomainError.Validation) {
            assertEquals(message, error.message)
        }
    }
}

private class FakeAnchor(private val anchor: ChainAnchor) : ChainAnchorReader {
    var lastRead: List<String>? = null

    override suspend fun read(registry: String, batchId: String, index: Long): ChainAnchor {
        lastRead = listOf(registry, batchId, index.toString())
        return anchor
    }
}

private class RecordingSigner : ConsumeSigner {
    var domain: Eip712Domain? = null

    override fun sign(label: SecretLabel, consumer: String, deadline: Long, domain: Eip712Domain): String {
        this.domain = domain
        return "0xsig"
    }
}

private class FakeProtocol : ProtocolRepository {
    var lastContext: ScanContext? = null
    var lastConsume: ConsumeAuthorization? = null
    var lastBatchRequest: NewBatchRequest? = null

    override suspend fun info() = ProtocolInfo(31337, REGISTRY, "https://x/u", DOMAIN)

    override suspend fun registerBatch(request: NewBatchRequest): RegisteredBatch {
        lastBatchRequest = request
        return RegisteredBatch(batch("1"), "1", true, true, List(request.size) {
            ir.aut.supplementtracker.core.model.UnitCredential(it.toLong(), "0x0", "p", "s")
        })
    }

    override suspend fun listBatches(manufacturer: String?, page: Int, limit: Int) = Page(1, 50, 0, 0, emptyList<Batch>())

    override suspend fun getBatch(batchId: String) = BatchDetail(batch(batchId), emptyList(), emptyMap())

    override suspend fun listSegments(owner: String?, batchId: String?, page: Int, limit: Int) =
        Page(
            1, 100, 3, 1,
            listOf(
                Segment("2", "4", "0xowner", 0, 5, 5, "Transferred"),
                Segment("3", "4", "0xowner", 5, 5, 0, "Transferred"),
                Segment("7", "9", "0xowner", 10, 20, 10, "AtPointOfSale"),
            ),
        )

    override suspend fun transfer(request: SegmentTransferRequest) =
        SegmentTransferResult("4", request.segmentId, "8", true, "0xa", request.toAddress, 0, request.count.toLong(), "Transferred", "0xtx")

    override suspend fun verify(unit: UnitRef, context: ScanContext): UnitVerification {
        lastContext = context
        return UnitVerification(
            authenticity = "Authentic",
            unit = unit,
            productName = "Vitamin D3",
            lotCode = "LOT-4",
            manufacturer = null,
            metadataCid = null,
            metadataGatewayUrl = null,
            custodian = null,
            segmentId = "2",
            segmentStatus = "AtPointOfSale",
            consumed = false,
            consumption = null,
            recalled = false,
            risk = RiskAssessment(0.0, "low", emptyList()),
            scanCount = 1,
            evidence = ChainEvidence("0xkey", ROOT, ROOT, emptyList(), "0xtx"),
            checkedAt = "2026-10-01T00:00:00Z",
        )
    }

    override suspend fun history(unit: UnitRef) = UnitHistory(unit, "0xm", "", "", emptyList(), null, false)

    override suspend fun consume(authorization: ConsumeAuthorization): UnitConsumeResult {
        lastConsume = authorization
        return UnitConsumeResult("Consumed", authorization.batchId, authorization.index, "2", "0xkey", authorization.consumer, "0xrelayer", "0xtx", 9)
    }

    override suspend fun renderLabels(batchId: String, secretQrs: List<String>) = ByteArray(0)

    private fun batch(id: String) = Batch(id, "0xm", 10, 0, false, name = "Vitamin D3", lotCode = "LOT-$id")

    companion object {
        const val REGISTRY = "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512"
        val DOMAIN = Eip712Domain("SupplementRegistry", "2", 31337, REGISTRY)
    }
}
