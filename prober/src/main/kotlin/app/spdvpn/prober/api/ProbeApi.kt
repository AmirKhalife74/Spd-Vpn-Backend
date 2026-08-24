package app.spdvpn.prober.api

import app.spdvpn.prober.model.ApiResponse
import app.spdvpn.prober.model.ProbeResultRequest
import app.spdvpn.prober.model.ProbeResultAcceptedData
import app.spdvpn.prober.model.ProbeServer
import app.spdvpn.prober.model.ServerConfigData
import app.spdvpn.prober.model.ServersData
import app.spdvpn.prober.scheduling.ConfigRequestGate
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException

interface ProbeApi {
    suspend fun fetchServers(): List<ProbeServer>

    suspend fun fetchServerConfig(serverId: String): String

    suspend fun submitProbeResult(result: ProbeResultRequest)
}

class ApiInfrastructureException(
    val reasonCode: String,
    cause: Throwable? = null,
) : RuntimeException(reasonCode, cause)

class HttpProbeApi(
    private val baseUrl: String,
    private val sharedSecret: String?,
    private val configRequestGate: ConfigRequestGate,
    private val client: HttpClient = createClient(),
) : ProbeApi, AutoCloseable {
    override suspend fun fetchServers(): List<ProbeServer> = apiCall {
        val allServers = mutableListOf<ProbeServer>()
        var offset = 0
        var pageCount = 0
        var hasMore: Boolean

        do {
            if (++pageCount > MAX_CATALOG_PAGES) {
                throw ApiInfrastructureException("CATALOG_PAGE_LIMIT_EXCEEDED")
            }
            configRequestGate.awaitTurn()
            val response = client.get("$baseUrl/v1/servers") {
                url {
                    parameters.append("limit", PAGE_SIZE.toString())
                    parameters.append("offset", offset.toString())
                }
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
            }
            ensureSuccess(response.status)
            val envelope = decodeJsonResponse<ApiResponse<ServersData>>(response, MAX_CATALOG_RESPONSE_BYTES)
            requireSuccessfulEnvelope(envelope.success)
            val page = envelope.data.page
            if (page.offset != offset || page.returned != envelope.data.servers.size) {
                throw ApiInfrastructureException("MALFORMED_API_RESPONSE")
            }
            allServers += envelope.data.servers
            offset += page.returned
            if (page.hasMore && page.returned == 0) {
                throw ApiInfrastructureException("MALFORMED_API_RESPONSE")
            }
            hasMore = page.hasMore
        } while (hasMore)

        allServers
    }

    override suspend fun fetchServerConfig(serverId: String): String = apiCall {
        configRequestGate.awaitTurn()
        val encodedId = serverId.encodeURLPathPart()
        val response = client.get("$baseUrl/v1/servers/$encodedId/config") {
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
        }
        ensureSuccess(response.status)
        val envelope = decodeJsonResponse<ApiResponse<ServerConfigData>>(response, MAX_CONFIG_RESPONSE_BYTES)
        requireSuccessfulEnvelope(envelope.success)
        if (envelope.data.id != serverId || envelope.data.openVpnConfig.isBlank()) {
            throw ApiInfrastructureException("MALFORMED_API_RESPONSE")
        }
        envelope.data.openVpnConfig
    }

    override suspend fun submitProbeResult(result: ProbeResultRequest): Unit = apiCall {
        val token = sharedSecret ?: throw ApiInfrastructureException("PROBER_TOKEN_UNAVAILABLE")
        val response = client.post("$baseUrl/internal/v1/probe-results") {
            contentType(ContentType.Application.Json)
            header(PROBER_TOKEN_HEADER, token)
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
            setBody(result)
        }
        ensureSuccess(response.status)
        val envelope = decodeJsonResponse<ApiResponse<ProbeResultAcceptedData>>(
            response,
            MAX_REPORT_RESPONSE_BYTES,
        )
        requireSuccessfulEnvelope(envelope.success)
        if (envelope.data.serverId != result.serverId) {
            throw ApiInfrastructureException("MALFORMED_API_RESPONSE")
        }
    }

    override fun close() {
        client.close()
    }

    private suspend fun <T> apiCall(block: suspend () -> T): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (known: ApiInfrastructureException) {
        throw known
    } catch (serialization: SerializationException) {
        throw ApiInfrastructureException("MALFORMED_API_RESPONSE", serialization)
    } catch (illegalState: IllegalStateException) {
        throw ApiInfrastructureException("MALFORMED_API_RESPONSE", illegalState)
    } catch (io: IOException) {
        throw ApiInfrastructureException("API_TRANSPORT_ERROR", io)
    } catch (failure: Throwable) {
        throw ApiInfrastructureException("API_CLIENT_ERROR", failure)
    }

    private fun ensureSuccess(status: HttpStatusCode) {
        if (status.value in 200..299) return
        val code = when {
            status == HttpStatusCode.TooManyRequests -> "API_RATE_LIMITED"
            status.value >= 500 -> "API_UNAVAILABLE"
            else -> "API_REQUEST_REJECTED"
        }
        throw ApiInfrastructureException(code)
    }

    private fun requireSuccessfulEnvelope(success: Boolean) {
        if (!success) throw ApiInfrastructureException("API_REQUEST_REJECTED")
    }

    private suspend inline fun <reified T> decodeJsonResponse(response: HttpResponse, maximumBytes: Int): T = try {
        response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { contentLength ->
            if (contentLength > maximumBytes) throw ApiInfrastructureException("API_RESPONSE_TOO_LARGE")
        }
        val channel = response.bodyAsChannel()
        val bytes = ByteArray(maximumBytes + 1)
        var total = 0
        while (total < bytes.size) {
            val read = channel.readAvailable(bytes, total, bytes.size - total)
            if (read == -1) break
            total += read
        }
        if (total > maximumBytes) throw ApiInfrastructureException("API_RESPONSE_TOO_LARGE")
        defaultJson.decodeFromString(bytes.copyOf(total).toString(Charsets.UTF_8))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (known: ApiInfrastructureException) {
        throw known
    } catch (failure: Throwable) {
        throw ApiInfrastructureException("MALFORMED_API_RESPONSE", failure)
    }

    companion object {
        const val PROBER_TOKEN_HEADER = "X-SpdVpn-Prober-Token"
        private const val PAGE_SIZE = 200
        private const val MAX_CATALOG_PAGES = 100
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val MAX_CATALOG_RESPONSE_BYTES = 4 * 1_024 * 1_024
        private const val MAX_CONFIG_RESPONSE_BYTES = 2 * 1_024 * 1_024
        private const val MAX_REPORT_RESPONSE_BYTES = 64 * 1_024

        val defaultJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = true
        }

        fun createClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(defaultJson)
            }
            install(HttpTimeout)
            expectSuccess = false
        }
    }
}
