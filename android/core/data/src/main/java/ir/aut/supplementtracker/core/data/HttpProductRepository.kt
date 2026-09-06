package ir.aut.supplementtracker.core.data

import ir.aut.supplementtracker.core.domain.ProductRepository
import ir.aut.supplementtracker.core.model.OwnershipEvent
import ir.aut.supplementtracker.core.model.OwnershipHistory
import ir.aut.supplementtracker.core.model.RegisterProductRequest
import ir.aut.supplementtracker.core.model.RegisteredProduct
import ir.aut.supplementtracker.core.model.TransferRequest
import ir.aut.supplementtracker.core.model.TransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("API failed: HTTP ${response.code} $body")
            }
            return JSONObject(body)
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

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
