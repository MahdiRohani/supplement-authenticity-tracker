package ir.aut.supplementtracker.core.data

import ir.aut.supplementtracker.core.domain.ProtocolRepository
import ir.aut.supplementtracker.core.model.Batch
import ir.aut.supplementtracker.core.model.BatchDetail
import ir.aut.supplementtracker.core.model.ConsumeAuthorization
import ir.aut.supplementtracker.core.model.NewBatchRequest
import ir.aut.supplementtracker.core.model.Page
import ir.aut.supplementtracker.core.model.ProtocolInfo
import ir.aut.supplementtracker.core.model.RegisteredBatch
import ir.aut.supplementtracker.core.model.ScanContext
import ir.aut.supplementtracker.core.model.Segment
import ir.aut.supplementtracker.core.model.SegmentTransferRequest
import ir.aut.supplementtracker.core.model.SegmentTransferResult
import ir.aut.supplementtracker.core.model.UnitConsumeResult
import ir.aut.supplementtracker.core.model.UnitHistory
import ir.aut.supplementtracker.core.model.UnitRef
import ir.aut.supplementtracker.core.model.UnitVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Client of the backend's `/v2` (SupplementRegistryV2) API. */
class HttpProtocolRepository(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = v2BaseUrl(BuildConfig.API_BASE_URL),
) : ProtocolRepository {
    @Volatile
    private var cachedInfo: ProtocolInfo? = null

    override suspend fun info(): ProtocolInfo =
        cachedInfo ?: withContext(Dispatchers.IO) {
            ProtocolJson.info(client.executeJson(get("chains"))).also { cachedInfo = it }
        }

    override suspend fun registerBatch(request: NewBatchRequest): RegisteredBatch =
        withContext(Dispatchers.IO) {
            val body =
                JSONObject()
                    .put("name", request.name)
                    .put("lotCode", request.lotCode)
                    .put("size", request.size)
                    .apply {
                        request.manufacturerAddress?.takeIf { it.isNotBlank() }?.let { put("manufacturerAddress", it) }
                        request.expiresAt?.takeIf { it.isNotBlank() }?.let { put("expiresAt", it) }
                    }
            ProtocolJson.registeredBatch(client.executeJson(post("batches", body)))
        }

    override suspend fun listBatches(manufacturer: String?, page: Int, limit: Int): Page<Batch> =
        withContext(Dispatchers.IO) {
            val url = url("batches", "manufacturer" to manufacturer, "page" to "$page", "limit" to "$limit")
            ProtocolJson.page(client.executeJson(Request.Builder().url(url).get().build()), ProtocolJson::batch)
        }

    override suspend fun getBatch(batchId: String): BatchDetail =
        withContext(Dispatchers.IO) {
            ProtocolJson.batchDetail(client.executeJson(get("batches/${segment(batchId)}")))
        }

    override suspend fun listSegments(owner: String?, batchId: String?, page: Int, limit: Int): Page<Segment> =
        withContext(Dispatchers.IO) {
            val url =
                url("segments", "owner" to owner, "batchId" to batchId, "page" to "$page", "limit" to "$limit")
            ProtocolJson.page(client.executeJson(Request.Builder().url(url).get().build()), ProtocolJson::segment)
        }

    override suspend fun transfer(request: SegmentTransferRequest): SegmentTransferResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("toAddress", request.toAddress).put("count", request.count)
            ProtocolJson.transfer(
                client.executeJson(post("segments/${segment(request.segmentId)}/transfer", body)),
            )
        }

    override suspend fun verify(unit: UnitRef, context: ScanContext): UnitVerification =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url("${baseUrl}verify/${unit.path}")
                    .header("X-Device-Id", context.deviceId)
                    .apply { context.region?.takeIf { it.isNotBlank() }?.let { header("X-Scan-Region", it) } }
                    .get()
                    .build()
            ProtocolJson.verification(client.executeJson(request), unit)
        }

    override suspend fun history(unit: UnitRef): UnitHistory =
        withContext(Dispatchers.IO) {
            ProtocolJson.history(
                client.executeJson(get("batches/${unit.batchId}/units/${unit.index}/history")),
                unit,
            )
        }

    override suspend fun consume(authorization: ConsumeAuthorization): UnitConsumeResult =
        withContext(Dispatchers.IO) {
            val body =
                JSONObject()
                    .put("chainId", authorization.chainId)
                    .put("batchId", authorization.batchId)
                    .put("index", authorization.index)
                    .put("consumer", authorization.consumer)
                    .put("deadline", authorization.deadline)
                    .put("signature", authorization.signature)
            ProtocolJson.consumeResult(client.executeJson(post("consume", body)))
        }

    override suspend fun renderLabels(batchId: String, secretQrs: List<String>): ByteArray =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("batchId", batchId).put("secretQrs", JSONArray(secretQrs))
            client.executeBytes(post("labels/render", body))
        }

    private fun get(path: String): Request = Request.Builder().url("$baseUrl$path").get().build()

    private fun post(path: String, body: JSONObject): Request =
        Request.Builder().url("$baseUrl$path").post(body.toString().toRequestBody(JSON_MEDIA)).build()

    private fun url(path: String, vararg query: Pair<String, String?>) =
        "$baseUrl$path".toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> value?.takeIf { it.isNotBlank() }?.let { addQueryParameter(key, it) } }
        }.build()

    private fun segment(id: String): String = id.filter { it.isDigit() }.ifEmpty { "0" }

    companion object {
        /** The v1 and v2 APIs share a host: `…/v1/` becomes `…/v2/`. */
        fun v2BaseUrl(v1BaseUrl: String): String {
            val base = v1BaseUrl.trimEnd('/')
            return (if (base.endsWith("/v1")) base.dropLast(3) + "/v2" else "$base/v2") + "/"
        }
    }
}
