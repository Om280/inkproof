package app.inkproof.ai

import app.inkproof.core.ai.AnalysisTransport
import app.inkproof.core.ai.CheckRequest
import app.inkproof.core.ai.SolveRequest
import app.inkproof.core.ai.TransportResult
import app.inkproof.core.ai.encodeRequest
import app.inkproof.core.ai.encodeSolve
import app.inkproof.core.model.AppSettings
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class HttpAnalysisTransport(
    private val settings: () -> AppSettings,
) : AnalysisTransport {
    override fun check(request: CheckRequest): TransportResult = post("/v1/check", encodeRequest(request))

    override fun solve(request: SolveRequest): TransportResult = post("/v1/solve", encodeSolve(request))

    override fun health(): TransportResult = get("/health")

    fun geminiHealth(): TransportResult = get("/health/gemini")

    fun recognize(json: String): TransportResult = post("/v1/recognize", json)

    private fun post(path: String, json: String): TransportResult {
        val conn = open(path, "POST") ?: return TransportResult(0, "", "NETWORK")
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            read(conn)
        } catch (e: SocketTimeoutException) {
            TransportResult(0, e.message ?: "", "TIMEOUT")
        } catch (e: Exception) {
            TransportResult(0, e.message ?: "", "NETWORK")
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String): TransportResult {
        val conn = open(path, "GET") ?: return TransportResult(0, "", "NETWORK")
        return try {
            read(conn)
        } catch (e: SocketTimeoutException) {
            TransportResult(0, e.message ?: "", "TIMEOUT")
        } catch (e: Exception) {
            TransportResult(0, e.message ?: "", "NETWORK")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection? {
        val base = settings().backendUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        return try {
            (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8000
                readTimeout = 60000
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun read(conn: HttpURLConnection): TransportResult {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return TransportResult(code, body)
    }
}
