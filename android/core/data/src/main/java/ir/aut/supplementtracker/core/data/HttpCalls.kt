package ir.aut.supplementtracker.core.data

import ir.aut.supplementtracker.core.domain.DomainError
import ir.aut.supplementtracker.core.domain.ErrorMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

internal val JSON_MEDIA = "application/json".toMediaType()

internal fun OkHttpClient.executeJson(request: Request): JSONObject {
    try {
        newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ErrorMapper.fromHttp(response.code, body)
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    } catch (error: DomainError) {
        throw error
    } catch (error: IOException) {
        throw DomainError.Network("Unable to resolve host or connect", error)
    } catch (error: Throwable) {
        throw DomainError.Unknown(error.message ?: "Request failed", error)
    }
}

internal fun OkHttpClient.executeBytes(request: Request): ByteArray {
    try {
        newCall(request).execute().use { response ->
            val body = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw ErrorMapper.fromHttp(response.code, body.toString(Charsets.UTF_8))
            }
            return body
        }
    } catch (error: DomainError) {
        throw error
    } catch (error: IOException) {
        throw DomainError.Network("Unable to resolve host or connect", error)
    } catch (error: Throwable) {
        throw DomainError.Unknown(error.message ?: "Request failed", error)
    }
}
