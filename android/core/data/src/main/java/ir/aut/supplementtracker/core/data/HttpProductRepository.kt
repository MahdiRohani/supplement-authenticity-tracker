package ir.aut.supplementtracker.core.data

import ir.aut.supplementtracker.core.domain.DomainError
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.ProductRepository
import ir.aut.supplementtracker.core.model.ConsumeRequest
import ir.aut.supplementtracker.core.model.ConsumeResult
import ir.aut.supplementtracker.core.model.OwnershipEvent
import ir.aut.supplementtracker.core.model.OwnershipHistory
import ir.aut.supplementtracker.core.model.ProductListPage
import ir.aut.supplementtracker.core.model.ProductListQuery
import ir.aut.supplementtracker.core.model.ProductMetadata
import ir.aut.supplementtracker.core.model.ProductSummary
import ir.aut.supplementtracker.core.model.RegisterBatchItem
import ir.aut.supplementtracker.core.model.RegisterBatchRequest
import ir.aut.supplementtracker.core.model.RegisterBatchResult
import ir.aut.supplementtracker.core.model.RegisterProductRequest
import ir.aut.supplementtracker.core.model.RegisteredProduct
import ir.aut.supplementtracker.core.model.TransferRequest
import ir.aut.supplementtracker.core.model.TransferResult
import ir.aut.supplementtracker.core.model.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class HttpProductRepository(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) : ProductRepository {
    override suspend fun register(request: RegisterProductRequest): RegisteredProduct =
        withContext(Dispatchers.IO) {
            val payload =
                JSONObject()
                    .put("name", request.name)
                    .put("batch", request.batch)
                    .apply {
                        request.manufacturerAddress?.let { put("manufacturerAddress", it) }
                    }
                    .toString()
            val json = executeJson(
                Request.Builder()
                    .url("${baseUrl}products")
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build(),
            )
            RegisteredProduct(
                id = json.getString("id"),
                chainProductId = json.getString("chainProductId"),
                metadataCid = json.optString("metadataCid").ifBlank { null },
                secret = json.optString("secret").ifBlank { null },
                status = json.optString("status", "Created"),
            )
        }

    override suspend fun registerBatch(request: RegisterBatchRequest): RegisterBatchResult =
        withContext(Dispatchers.IO) {
            val payload =
                JSONObject()
                    .put("name", request.name)
                    .put("batch", request.batch)
                    .put("count", request.count)
                    .apply {
                        request.manufacturerAddress?.let { put("manufacturerAddress", it) }
                    }
                    .toString()
            val json = executeJson(
                Request.Builder()
                    .url("${baseUrl}products/batch")
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build(),
            )
            RegisterBatchResult(
                count = json.optInt("count"),
                metadataCid = json.optString("metadataCid").ifBlank { null },
                mintedOnChain = json.optBoolean("mintedOnChain"),
                txHash = json.optString("txHash").ifBlank { null },
                items = json.optJSONArray("items").toBatchItems(),
            )
        }

    override suspend fun list(query: ProductListQuery): ProductListPage =
        withContext(Dispatchers.IO) {
            val urlBuilder = "${baseUrl}products".toHttpUrl().newBuilder()
                .addQueryParameter("page", query.page.toString())
                .addQueryParameter("limit", query.limit.toString())
            query.owner?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("owner", it) }
            query.status?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("status", it) }
            query.q?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("q", it) }
            val json = executeJson(
                Request.Builder()
                    .url(urlBuilder.build())
                    .get()
                    .build(),
            )
            ProductListPage(
                page = json.optInt("page", query.page),
                limit = json.optInt("limit", query.limit),
                total = json.optInt("total"),
                totalPages = json.optInt("totalPages", 1),
                items = json.optJSONArray("items").toProductSummaries(),
            )
        }

    override suspend fun transfer(request: TransferRequest): TransferResult =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("toAddress", request.toAddress).toString()
            val json = executeJson(
                Request.Builder()
                    .url("${baseUrl}products/${request.productId}/transfer")
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build(),
            )
            TransferResult(
                chainProductId = json.getString("chainProductId"),
                fromAddress = json.getString("fromAddress"),
                toAddress = json.getString("toAddress"),
                txHash = json.getString("txHash"),
            )
        }

    override suspend fun consume(request: ConsumeRequest): ConsumeResult =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("secret", request.secret).toString()
            val json = executeJson(
                Request.Builder()
                    .url("${baseUrl}products/${request.productId}/consume")
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build(),
            )
            ConsumeResult(
                productId = json.optString("productId", request.productId),
                chainProductId = json.getString("chainProductId"),
                status = json.optString("status", "Consumed"),
                txHash = json.getString("txHash"),
                actor = json.optString("actor"),
            )
        }

    override suspend fun history(productId: String): OwnershipHistory =
        withContext(Dispatchers.IO) {
            val json = executeJson(
                Request.Builder()
                    .url("${baseUrl}products/$productId/history")
                    .get()
                    .build(),
            )
            OwnershipHistory(
                productId = json.getString("productId"),
                chainProductId = json.getString("chainProductId"),
                currentOwner = json.getString("currentOwner"),
                status = json.getString("status"),
                elapsedMs = json.optLong("elapsedMs"),
                events = json.optJSONArray("events").toOwnershipEvents(),
            )
        }

    override suspend fun verify(productId: String): VerifyResult =
        withContext(Dispatchers.IO) {
            val json = executeJson(
                Request.Builder()
                    .url("${baseUrl}verify/$productId")
                    .get()
                    .build(),
            )
            val metadataJson = json.optJSONObject("metadata")
            VerifyResult(
                productId = json.optString("productId", productId),
                chainProductId = json.optString("chainProductId", productId),
                status = json.getString("status"),
                authenticity = json.optString("authenticity", json.getString("status")),
                currentOwner = json.optString("currentOwner"),
                metadataCid = json.optString("metadataCid").ifBlank { null },
                metadata = metadataJson?.let {
                    ProductMetadata(
                        name = it.optString("name").ifBlank { null },
                        batch = it.optString("batch").ifBlank { null },
                        expiresAt = it.optString("expiresAt").ifBlank { null },
                        image = it.optString("image").ifBlank { null },
                    )
                },
                source = json.optString("source", "db"),
                message = json.optString("message").ifBlank { null },
            )
        }

    private fun executeJson(request: Request): JSONObject {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ErrorMapper.fromHttp(response.code, body)
                }
                return JSONObject(body)
            }
        } catch (error: DomainError) {
            throw error
        } catch (error: IOException) {
            throw DomainError.Network("Unable to resolve host or connect", error)
        } catch (error: Throwable) {
            throw DomainError.Unknown(error.message ?: "Request failed", error)
        }
    }

    private fun JSONArray?.toOwnershipEvents(): List<OwnershipEvent> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    OwnershipEvent(
                        id = item.getString("id"),
                        fromAddress = item.getString("fromAddress"),
                        toAddress = item.getString("toAddress"),
                        txHash = item.getString("txHash"),
                        blockNumber = item.getString("blockNumber"),
                        createdAt = item.getString("createdAt"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toProductSummaries(): List<ProductSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    ProductSummary(
                        id = item.getString("id"),
                        chainProductId = item.getString("chainProductId"),
                        ownerAddress = item.getString("ownerAddress"),
                        status = item.getString("status"),
                        name = item.optString("name").ifBlank { null },
                        batchCode = item.optString("batchCode").ifBlank { null },
                        metadataCid = item.optString("metadataCid").ifBlank { null },
                        createdAt = item.optString("createdAt"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toBatchItems(): List<RegisterBatchItem> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    RegisterBatchItem(
                        id = item.getString("id"),
                        chainProductId = item.getString("chainProductId"),
                        secret = item.optString("secret").ifBlank { null },
                    ),
                )
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
