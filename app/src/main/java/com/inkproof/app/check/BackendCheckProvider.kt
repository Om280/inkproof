package com.inkproof.app.check

import com.inkproof.app.model.CheckRequest
import com.inkproof.app.model.CheckResponse
import com.inkproof.app.model.CheckStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the InkProof backend. The backend owns all API secrets; the
 * Android app never carries production credentials.
 *
 * All failures are converted into structured ERROR responses — the UI never
 * sees raw exceptions or malformed AI prose.
 */
class BackendCheckProvider(
    private val baseUrl: String,
    client: OkHttpClient? = null
) : CheckProvider {

    override val name: String = "backend"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun check(request: CheckRequest): CheckResponse =
        post("/api/check", request)

    override suspend fun solve(request: CheckRequest): CheckResponse =
        post("/api/solve", request)

    private suspend fun post(path: String, request: CheckRequest): CheckResponse =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(CheckRequest.serializer(), request)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val httpRequest = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .post(body)
                .build()
            try {
                http.newCall(httpRequest).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.code == 429 -> CheckResponse(
                            status = CheckStatus.ERROR,
                            message = "Too many checks in a short time. Please wait a moment and try again."
                        )
                        !response.isSuccessful -> CheckResponse(
                            status = CheckStatus.ERROR,
                            message = "The check service returned an error (${response.code}). Your work is saved — try again shortly."
                        )
                        else -> parseStrict(text)
                    }
                }
            } catch (e: IOException) {
                CheckResponse(
                    status = CheckStatus.ERROR,
                    message = "No connection to the check service. Writing and notebooks keep working offline; checking needs internet."
                )
            }
        }

    /** Strict parsing: malformed AI JSON becomes a clean, visible error state. */
    internal fun parseStrict(text: String): CheckResponse =
        try {
            json.decodeFromString(CheckResponse.serializer(), text)
        } catch (e: Exception) {
            CheckResponse(
                status = CheckStatus.ERROR,
                message = "The check service sent an unreadable response. Please try again."
            )
        }
}
