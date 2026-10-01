package ir.aut.supplementtracker.core.data

import ir.aut.supplementtracker.core.domain.DomainError
import ir.aut.supplementtracker.core.model.ConsumeRequest
import ir.aut.supplementtracker.core.model.ProductListQuery
import ir.aut.supplementtracker.core.model.RegisterBatchRequest
import ir.aut.supplementtracker.core.model.RegisterProductRequest
import ir.aut.supplementtracker.core.model.TransferRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the app's HTTP client against a live backend, e.g.
 * `SAT_API_BASE_URL=http://127.0.0.1:3000/v1/ ./gradlew :core:data:testLocalDebugUnitTest`.
 * The backend must not require API_WRITE_KEY. The lifecycle test additionally
 * needs SAT_DISTRIBUTOR and SAT_PHARMACY: relayer-managed wallets holding the
 * on-chain distributor and pharmacy roles.
 */
class HttpProductRepositoryContractTest {
    private val baseUrl = System.getenv("SAT_API_BASE_URL").orEmpty()
    private lateinit var repository: HttpProductRepository

    @Before
    fun setUp() {
        assumeTrue("SAT_API_BASE_URL not set", baseUrl.isNotBlank())
        repository = HttpProductRepository(baseUrl = baseUrl.trimEnd('/') + "/")
    }

    @Test
    fun registerListVerifyAndLabels() = runBlocking {
        val batch = "CT-${System.currentTimeMillis()}"
        val registered = repository.register(RegisterProductRequest(name = "Contract D3", batch = batch))
        assertNotNull(registered.secret)
        assertEquals("Created", registered.status)

        val page = repository.list(ProductListQuery(q = batch, limit = 5))
        assertEquals(1, page.total)
        assertEquals(registered.id, page.items.single().id)
        assertEquals(batch, page.items.single().batchCode)

        val verified = repository.verify(registered.chainProductId)
        assertEquals("Authentic", verified.authenticity)
        assertEquals("Contract D3", verified.metadata?.name)
        assertEquals(batch, verified.metadata?.batch)

        val history = repository.history(registered.chainProductId)
        assertEquals(registered.id, history.productId)

        val flags = repository.getFeatureFlags()
        assertTrue(flags.labelsPdfEnabled)
        val pdf = repository.downloadBatchLabelsPdf(batch)
        assertEquals("%PDF", String(pdf.copyOfRange(0, 4)))

        repository.reportCounterfeit(registered.chainProductId, "contract test")
    }

    @Test
    fun registerBatch() = runBlocking {
        val result = repository.registerBatch(RegisterBatchRequest(name = "Contract Omega", batch = "CTB-1", count = 3))
        assertEquals(3, result.count)
        assertEquals(3, result.items.size)
        assertTrue(result.items.all { it.secret != null })
    }

    @Test
    fun errorBodiesMapToDomainErrors() = runBlocking {
        expect<DomainError.NotFound>("Product does-not-exist not found") {
            repository.verify("does-not-exist")
        }
        expect<DomainError.Validation>("name must be longer than or equal to 1 characters") {
            repository.register(RegisterProductRequest(name = "", batch = "x"))
        }
        expect<DomainError.Validation>("secret must be longer than or equal to 66 characters") {
            repository.consume(ConsumeRequest(productId = "1", secret = "0x12"))
        }
    }

    @Test
    fun fullLifecycleWithAntiRefill() = runBlocking {
        val distributor = System.getenv("SAT_DISTRIBUTOR").orEmpty()
        val pharmacy = System.getenv("SAT_PHARMACY").orEmpty()
        assumeTrue("SAT_DISTRIBUTOR / SAT_PHARMACY not set", distributor.isNotBlank() && pharmacy.isNotBlank())

        val registered = repository.register(RegisterProductRequest(name = "Lifecycle", batch = "CTL-1"))
        val id = registered.chainProductId
        assertTrue("product must be minted on-chain, got $id", id.all { it.isDigit() })

        // The backend signs with the owner's key, and ownership in its database
        // follows the chain through the indexer, so each hop waits for it.
        val first = repository.transfer(TransferRequest(productId = id, toAddress = distributor))
        assertEquals(distributor.lowercase(), first.toAddress)
        awaitOwnershipEvents(id, 1)
        repository.transfer(TransferRequest(productId = id, toAddress = pharmacy))
        awaitOwnershipEvents(id, 2)

        val consumed = repository.consume(ConsumeRequest(productId = id, secret = registered.secret!!))
        assertEquals("Consumed", consumed.status)
        assertFalse(consumed.txHash.isBlank())

        expect<DomainError.Conflict>("Product already consumed; refill is not allowed") {
            repository.consume(ConsumeRequest(productId = id, secret = registered.secret!!))
        }

        val verified = repository.verify(id)
        assertEquals("Consumed", verified.authenticity)
        assertNotNull(verified.message)
        assertEquals(pharmacy.lowercase(), repository.history(id).currentOwner)
    }

    private suspend fun awaitOwnershipEvents(id: String, count: Int) {
        repeat(40) {
            if (repository.history(id).events.size >= count) return
            Thread.sleep(250)
        }
        fail("indexer did not record $count ownership events for product $id")
    }

    private inline fun <reified T : DomainError> expect(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (error: DomainError) {
            assertTrue("got ${error::class.simpleName}: ${error.message}", error is T)
            assertEquals(message, error.message)
        }
    }
}
