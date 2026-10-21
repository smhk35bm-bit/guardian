package com.guardian.parent.data

import com.guardian.parent.core.Config
import com.guardian.parent.core.Logger
import com.guardian.parent.core.optObjectOrNull
import com.guardian.parent.core.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** The server answered with an error object (`{ ok:false, error:{...} }`). */
class ApiException(
    val code: String,
    message: String,
    val httpCode: Int
) : Exception(message) {

    /** `true` when the token was rejected and the device must be paired again. */
    val isAuthError: Boolean
        get() = httpCode == 401 || httpCode == 403 ||
            code == "INVALID_TOKEN" || code == "FORBIDDEN"
}

/** The request never reached the server (offline, DNS, timeout...). */
class NetworkException(cause: Throwable) : IOException("network failure: ${cause.message}", cause)

/**
 * Small HTTP client written directly on top of OkHttp.
 *
 * Retrofit is deliberately not used: the contract only needs a handful of
 * endpoints, and a hand written client keeps the dependency list short and
 * the request/response format obvious.
 */
class ApiClient(private val store: LocalStore) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(Config.WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Accepts the server address with or without the trailing `/api` segment:
     * `https://host`, `https://host/`, `https://host/api` and `https://host/api/`
     * all resolve to `https://host/api`.
     */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        while (url.endsWith("/")) url = url.dropLast(1)
        if (url.isEmpty()) return url
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val hasApiSegment = path.split('/').any { it.equals("api", ignoreCase = true) }
        if (!hasApiSegment) url = "$url/api"
        return url
    }

    /** `POST /api/devices/register` — consumes the one time enrolment code. */
    suspend fun register(
        baseUrl: String,
        code: String,
        identity: DeviceIdentity
    ): EnrollmentResult = withContext(Dispatchers.IO) {
        val url = normalizeBaseUrl(baseUrl) + "/devices/register"
        val body = JSONObject()
            .put("code", code)
            .put("deviceName", identity.deviceName)
            .put("manufacturer", identity.manufacturer)
            .put("model", identity.model)
            .put("androidVersion", identity.androidVersion)
            .put("sdkInt", identity.sdkInt)
            .put("appVersion", identity.appVersion)

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val data = execute(request)
        EnrollmentResult(
            deviceId = data.optLong("deviceId", 0L),
            deviceToken = data.optStringOrNull("deviceToken").orEmpty(),
            serverUrl = data.optStringOrNull("serverUrl")
        )
    }

    /** `POST /api/devices/:id/<path>` with a JSON body. */
    suspend fun postDeviceJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(deviceUrl(path))
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", authHeader())
            .build()
        execute(request)
    }

    /**
     * `POST /api/devices/:id/<path>` as multipart.
     *
     * The contract requires the binary field to be called `file` and the
     * optional JSON metadata field to be called `meta`.
     */
    suspend fun postDeviceMedia(
        path: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        meta: JSONObject?
    ): JSONObject = withContext(Dispatchers.IO) {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mimeType.toMediaType()))
        if (meta != null) {
            multipart.addFormDataPart("meta", meta.toString())
        }

        val request = Request.Builder()
            .url(deviceUrl(path))
            .post(multipart.build())
            .addHeader("Authorization", authHeader())
            .build()
        execute(request)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun deviceUrl(path: String): String {
        val base = normalizeBaseUrl(store.serverUrl.orEmpty())
        return "$base/devices/${store.deviceId}/$path"
    }

    /** The contract prefers the dedicated `Device` scheme for device tokens. */
    private fun authHeader(): String = "Device ${store.deviceToken.orEmpty()}"

    private fun execute(request: Request): JSONObject {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            Logger.w("HTTP request failed: ${request.url}", e)
            throw NetworkException(e)
        }

        response.use {
            val text = it.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()

            if (!it.isSuccessful) {
                val error = json?.optObjectOrNull("error")
                val code = error?.optStringOrNull("code") ?: "HTTP_${it.code}"
                val message = error?.optStringOrNull("message")
                    ?: text.ifBlank { it.message.ifBlank { "HTTP ${it.code}" } }
                throw ApiException(code, message, it.code)
            }

            if (json == null) {
                throw ApiException("BAD_RESPONSE", "Malformed response from server", it.code)
            }

            if (!json.optBoolean("ok", false)) {
                val error = json.optObjectOrNull("error")
                throw ApiException(
                    error?.optStringOrNull("code") ?: "UNKNOWN",
                    error?.optStringOrNull("message") ?: "Unknown server error",
                    it.code
                )
            }

            return json.optObjectOrNull("data") ?: JSONObject()
        }
    }
}
