package ir.aut.supplementtracker.core.data

import ir.aut.supplementtracker.core.domain.ProductRepository
import ir.aut.supplementtracker.core.model.RegisterProductRequest
import ir.aut.supplementtracker.core.model.RegisteredProduct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
                    .toString()
            val httpRequest =
                Request.Builder()
                    .url("${baseUrl}products")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Register failed: HTTP ${response.code} $body")
                }
                val json = JSONObject(body)
                RegisteredProduct(
                    id = json.getString("id"),
                    chainProductId = json.getString("chainProductId"),
                    metadataCid = json.optString("metadataCid").ifBlank { null },
                    secret = json.optString("secret").ifBlank { null },
                    status = json.optString("status", "Created"),
                )
            }
        }
}
