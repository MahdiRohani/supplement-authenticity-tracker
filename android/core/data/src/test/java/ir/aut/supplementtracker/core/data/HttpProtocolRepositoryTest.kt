package ir.aut.supplementtracker.core.data

import ir.aut.supplementtracker.core.domain.DomainError
import ir.aut.supplementtracker.core.model.ConsumeAuthorization
import ir.aut.supplementtracker.core.model.NewBatchRequest
import ir.aut.supplementtracker.core.model.ScanContext
import ir.aut.supplementtracker.core.model.SegmentTransferRequest
import ir.aut.supplementtracker.core.model.UnitRef
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Wire-format tests of the `/v2` client against canned backend responses. */
class HttpProtocolRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: HttpProtocolRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repository = HttpProtocolRepository(baseUrl = server.url("/v2/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun derivesTheV2BaseUrlFromTheV1One() {
        assertEquals("http://10.0.2.2:3000/v2/", HttpProtocolRepository.v2BaseUrl("http://10.0.2.2:3000/v1/"))
        assertEquals("https://api.x/v2/", HttpProtocolRepository.v2BaseUrl("https://api.x/v1"))
        assertEquals("https://api.x/v2/", HttpProtocolRepository.v2BaseUrl("https://api.x/"))
    }

    @Test
    fun verifySendsScanContextAndParsesRiskAndEvidence() = runBlocking {
        server.enqueue(json(VERIFY_SUSPICIOUS))
        val result = repository.verify(UnitRef(31337, "3", 7), ScanContext("device-9", "tehran"))

        val request = server.takeRequest()
        assertEquals("/v2/verify/31337/3/7", request.path)
        assertEquals("device-9", request.getHeader("X-Device-Id"))
        assertEquals("tehran", request.getHeader("X-Scan-Region"))

        assertEquals("Suspicious", result.authenticity)
        assertEquals("Vitamin D3", result.productName)
        assertEquals("Darou Pharmacy", result.custodian?.displayName)
        assertEquals("tehran", result.custodian?.region)
        assertNull(result.manufacturer?.displayName)
        assertEquals("high", result.risk.level)
        assertEquals(listOf("many_devices", "foreign_region"), result.risk.reasons.map { it.code })
        assertEquals(2, result.evidence!!.proof.size)
        assertFalse(result.consumed)
        assertNull(result.consumption)
    }

    @Test
    fun verifyOmitsTheRegionHeaderWhenUnknown() = runBlocking {
        server.enqueue(json(VERIFY_SUSPICIOUS))
        repository.verify(UnitRef(31337, "3", 7), ScanContext("device-9", null))
        assertNull(server.takeRequest().getHeader("X-Scan-Region"))
    }

    @Test
    fun registerBatchReturnsOneTimeCredentials() = runBlocking {
        server.enqueue(json(REGISTERED, code = 201))
        val result = repository.registerBatch(NewBatchRequest(name = "D3", lotCode = "L-1", size = 2))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(setOf("name", "lotCode", "size"), body.keys().asSequence().toSet())
        assertEquals(2, body.getInt("size"))
        assertEquals("5", result.batch.batchId)
        assertTrue(result.keysRevealOnce)
        assertEquals(listOf(0L, 1L), result.units.map { it.index })
        assertTrue(result.units.all { it.secretQr.startsWith("satk2:31337:5:") })
    }

    @Test
    fun transferPostsTheCountAndReportsTheSplit() = runBlocking {
        server.enqueue(json(TRANSFERRED))
        val result = repository.transfer(SegmentTransferRequest("5", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8", 3))

        val request = server.takeRequest()
        assertEquals("/v2/segments/5/transfer", request.path)
        assertEquals(3, JSONObject(request.body.readUtf8()).getInt("count"))
        assertTrue(result.split)
        assertEquals(3L, result.units)
        assertEquals("6", result.toSegmentId)
    }

    @Test
    fun consumePostsTheSignedAuthorization() = runBlocking {
        server.enqueue(json(CONSUMED))
        val result = repository.consume(ConsumeAuthorization(31337, "5", 1, "0xbuyer", 1_900_000_000, "0xsig"))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("5", body.getString("batchId"))
        assertEquals(1, body.getInt("index"))
        assertEquals(1_900_000_000L, body.getLong("deadline"))
        assertEquals("0xsig", body.getString("signature"))
        assertEquals("Consumed", result.status)
        assertEquals("0xrelayer", result.submitter)
    }

    @Test
    fun infoIsFetchedOnce() = runBlocking {
        server.enqueue(json(CHAINS))
        val first = repository.info()
        val second = repository.info()
        assertEquals(1, server.requestCount)
        assertEquals(first, second)
        assertEquals("2", first.domain.version)
        assertEquals("0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512", first.domain.verifyingContract)
    }

    @Test
    fun segmentsAndBatchesParsePagesAndDistribution() = runBlocking {
        server.enqueue(json("""{"page":1,"limit":100,"total":1,"totalPages":1,"items":[{"segmentId":"6","batchId":"5","owner":"0xd","start":0,"end":3,"units":3,"status":"Transferred","updatedAt":"t"}]}"""))
        server.enqueue(json("""{"batchId":"5","manufacturer":"0xm","size":4,"consumedCount":1,"recalled":false,"name":null,"lotCode":"L-1","merkleRoot":"0xr","segments":[],"distribution":{"Created":1,"Transferred":3}}"""))

        val page = repository.listSegments(owner = "0xd")
        assertEquals("/v2/segments?owner=0xd&page=1&limit=100", server.takeRequest().path)
        assertEquals(3, page.items.single().units)

        val detail = repository.getBatch("5")
        assertNull(detail.batch.name)
        assertEquals(mapOf("Created" to 1, "Transferred" to 3), detail.distribution)
    }

    @Test
    fun errorBodiesMapToDomainErrors() = runBlocking {
        server.enqueue(json("""{"statusCode":409,"message":"Unit already consumed; refill is not allowed","error":"Conflict"}""", code = 409))
        try {
            repository.consume(ConsumeAuthorization(31337, "5", 1, "0xbuyer", 1, "0xsig"))
            fail("expected conflict")
        } catch (error: DomainError.Conflict) {
            assertEquals("Unit already consumed; refill is not allowed", error.message)
        }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val VERIFY_SUSPICIOUS = """
            {"authenticity":"Suspicious","chainId":31337,"batchId":"3","index":7,
             "product":{"name":"Vitamin D3","lotCode":"L-3","manufacturer":{"address":"0xm","role":"Manufacturer","displayName":null,"region":null},
                        "metadataCid":"bafy","metadataGatewayUrl":"https://ipfs.io/ipfs/bafy"},
             "custodian":{"address":"0xp","role":"Pharmacy","displayName":"Darou Pharmacy","region":"tehran"},
             "segmentId":"9","segmentStatus":"AtPointOfSale","consumed":false,"consumption":null,"recalled":false,
             "risk":{"score":0.66,"level":"high","reasons":[
                {"code":"many_devices","weight":0.437,"message":"Scanned by 5 different devices before being sold (expected at most 3)"},
                {"code":"foreign_region","weight":0.51,"message":"Scanned in 2 region(s) other than the selling pharmacy's"}]},
             "scanCount":9,
             "evidence":{"unitKey":"0x61c6208C4132e8E92846744881EdBA4c3C1C9110","merkleRoot":"0xr","leaf":"0xl","proof":["0xa","0xb"],"registerTxHash":"0xt"},
             "checkedAt":"2026-10-01T10:00:00.000Z"}"""

        const val REGISTERED = """
            {"batchId":"5","manufacturer":"0xm","size":2,"consumedCount":0,"recalled":false,"name":"D3","lotCode":"L-1",
             "merkleRoot":"0xr","physicalBatchId":"0xp","metadataCid":"bafy","metadataHash":"0xh","metadataGatewayUrl":"g",
             "txHash":"0xt","blockNumber":12,"createdAt":"c","segmentId":"5","ipfsPinned":false,"keysRevealOnce":true,
             "units":[{"index":0,"unitKey":"0xk0","publicQr":"https://x/u/31337/5/0","secretQr":"satk2:31337:5:0:aa"},
                      {"index":1,"unitKey":"0xk1","publicQr":"https://x/u/31337/5/1","secretQr":"satk2:31337:5:1:bb"}]}"""

        const val TRANSFERRED = """
            {"batchId":"5","fromSegmentId":"5","toSegmentId":"6","split":true,"from":"0xm","to":"0xd","start":0,"end":3,
             "status":"Transferred","txHash":"0xt","blockNumber":13}"""

        const val CONSUMED = """
            {"status":"Consumed","batchId":"5","index":1,"segmentId":"7","unitKey":"0xk1","consumer":"0xbuyer",
             "submitter":"0xrelayer","txHash":"0xt","blockNumber":20}"""

        const val CHAINS = """
            {"activeChainId":31337,"registryAddress":"0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512","abiVersion":"2.0.0",
             "deployBlock":1,"publicVerifyBaseUrl":"https://supplementtracker.aut.ir/u",
             "eip712Domain":{"name":"SupplementRegistry","version":"2","chainId":31337,"verifyingContract":"0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512"},
             "consumeTypes":{},"primaryType":"ConsumeAuthorization","deployments":{}}"""
    }
}
