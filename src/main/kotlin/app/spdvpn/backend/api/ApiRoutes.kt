package app.spdvpn.backend.api

import app.spdvpn.backend.model.ApiResponse
import app.spdvpn.backend.model.CountriesData
import app.spdvpn.backend.model.PageMetadata
import app.spdvpn.backend.model.ProbeResult
import app.spdvpn.backend.model.ProbeResultAcceptedData
import app.spdvpn.backend.model.ServerHealthStatus
import app.spdvpn.backend.model.ServerConfigData
import app.spdvpn.backend.model.ServersData
import app.spdvpn.backend.model.ServiceInfo
import app.spdvpn.backend.model.VpnProtocol
import app.spdvpn.backend.model.toSummary
import app.spdvpn.backend.service.CatalogService
import app.spdvpn.backend.service.ServerQuery
import app.spdvpn.backend.service.ServerSort
import app.spdvpn.backend.service.SortOrder
import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

fun Route.apiRoutes(
    service: CatalogService,
    proberSharedSecret: String?,
    json: Json,
) {
    get("/") {
        call.respond(ApiResponse(data = ServiceInfo()))
    }

    get("/health/live") {
        call.respondText("ok")
    }

    route("/v1") {
        get("/health") {
            val health = service.health()
            call.respond(
                status = if (health.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                message = ApiResponse(data = health),
            )
        }

        get("/countries") {
            val snapshot = service.currentSnapshot()
            val source = service.sourceMetadata(snapshot)
            if (call.respondNotModifiedIfMatched(snapshot.version, call.request.queryString())) return@get

            call.markCatalogCache(source.stale)
            call.respond(
                ApiResponse(
                    data = CountriesData(
                        countries = service.countries(snapshot),
                        source = source,
                    ),
                ),
            )
        }

        get("/servers") {
            val query = call.toServerQuery()
            val snapshot = service.currentSnapshot()
            val health = service.serverHealthSnapshot()
            val source = service.sourceMetadata(snapshot)
            if (call.respondNotModifiedIfMatched(service.serverListVersion(snapshot, health), call.request.queryString())) {
                return@get
            }

            val result = service.queryServers(query, snapshot, health)
            val returned = result.servers.size
            call.markCatalogCache(source.stale)
            call.respond(
                ApiResponse(
                    data = ServersData(
                        servers = result.servers.map { it.toSummary(health.records[it.id]) },
                        page = PageMetadata(
                            offset = query.offset,
                            limit = query.limit,
                            returned = returned,
                            total = result.total,
                            hasMore = query.offset + returned < result.total,
                        ),
                        source = source,
                    ),
                ),
            )
        }

        get("/servers/{id}/config") {
            val id = call.parameters["id"]?.takeIf(String::isNotBlank)
                ?: throw ApiBadRequestException("Server id is required")
            val snapshot = service.currentSnapshot()
            val server = service.findServer(id, snapshot)
                ?: throw ApiNotFoundException("VPN server was not found")
            val source = service.sourceMetadata(snapshot)
            if (call.respondNotModifiedIfMatched(snapshot.version, id)) return@get

            call.markCatalogCache(source.stale)
            call.respond(
                ApiResponse(
                    data = ServerConfigData(
                        id = server.id,
                        protocol = server.protocol,
                        port = server.port,
                        openVpnConfig = server.openVpnConfig,
                        source = source,
                    ),
                ),
            )
        }
    }

    route("/internal/v1") {
        post("/probe-results") {
            val secret = proberSharedSecret?.takeIf(String::isNotBlank)
                ?: throw ApiNotFoundException("Probe reporting endpoint is disabled")
            val suppliedToken = call.request.headers[PROBER_TOKEN_HEADER]
            if (!constantTimeEquals(secret, suppliedToken)) {
                throw ApiUnauthorizedException("Missing or invalid prober token")
            }

            val result = call.receiveProbeResult(json)
            val health = service.recordProbeResult(result)
            call.respond(
                ApiResponse(
                    data = ProbeResultAcceptedData(
                        serverId = health.serverId,
                        healthStatus = health.status,
                        consecutiveFailures = health.consecutiveFailures,
                    ),
                ),
            )
        }
    }
}

private fun ApplicationCall.toServerQuery(): ServerQuery {
    val country = request.queryParameters["country"]
        ?.trim()
        ?.uppercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)
        ?.also { requireParameter(it.matches(Regex("^[A-Z]{2}$")), "country must be a two-letter code") }

    val protocol = request.queryParameters["protocol"]?.let { raw ->
        enumValueOrNull<VpnProtocol>(raw) ?: throw ApiBadRequestException("protocol must be TCP, UDP, or UNKNOWN")
    }
    val sort = request.queryParameters["sort"]?.let { raw ->
        enumValueOrNull<ServerSort>(raw) ?: throw ApiBadRequestException(
            "sort must be score, speed, ping, uptime, users, or sessions",
        )
    } ?: ServerSort.SCORE
    val order = request.queryParameters["order"]?.let { raw ->
        enumValueOrNull<SortOrder>(raw) ?: throw ApiBadRequestException("order must be asc or desc")
    } ?: SortOrder.DESC

    val minimumScore = request.queryParameters.nonNegativeLong("minScore")
    val maximumPingMs = request.queryParameters.nonNegativeInt("maxPingMs")
    val healthStatus = request.queryParameters["health"]?.let { raw ->
        enumValueOrNull<ServerHealthStatus>(raw) ?: throw ApiBadRequestException(
            "health must be healthy, degraded, unhealthy, or unknown",
        )
    }
    val offset = request.queryParameters.nonNegativeInt("offset") ?: 0
    val limit = request.queryParameters.nonNegativeInt("limit") ?: 50
    requireParameter(limit in 1..200, "limit must be between 1 and 200")

    return ServerQuery(
        countryCode = country,
        protocol = protocol,
        minimumScore = minimumScore,
        maximumPingMs = maximumPingMs,
        healthStatus = healthStatus,
        search = request.queryParameters["q"]?.trim()?.takeIf(String::isNotEmpty),
        sort = sort,
        order = order,
        offset = offset,
        limit = limit,
    )
}

private fun io.ktor.http.Parameters.nonNegativeInt(name: String): Int? = this[name]?.let { raw ->
    raw.toIntOrNull()?.takeIf { it >= 0 }
        ?: throw ApiBadRequestException("$name must be a non-negative integer")
}

private fun io.ktor.http.Parameters.nonNegativeLong(name: String): Long? = this[name]?.let { raw ->
    raw.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw ApiBadRequestException("$name must be a non-negative integer")
}

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }

private fun requireParameter(condition: Boolean, message: String) {
    if (!condition) throw ApiBadRequestException(message)
}

private suspend fun ApplicationCall.respondNotModifiedIfMatched(version: String, variant: String): Boolean {
    val etag = "\"$version-${variant.hashCode().toUInt()}\""
    response.header(HttpHeaders.ETag, etag)
    val matches = request.headers[HttpHeaders.IfNoneMatch]
        ?.split(',')
        ?.map(String::trim)
        ?.any { it == etag || it == "*" }
        ?: false
    if (matches) {
        respond(HttpStatusCode.NotModified)
    }
    return matches
}

private fun ApplicationCall.markCatalogCache(stale: Boolean) {
    response.header(
        HttpHeaders.CacheControl,
        CacheControl.MaxAge(maxAgeSeconds = 60, visibility = CacheControl.Visibility.Public).toString(),
    )
    response.header("X-Data-Stale", stale.toString())
}

class ApiBadRequestException(message: String) : RuntimeException(message)
class ApiNotFoundException(message: String) : RuntimeException(message)
class ApiUnauthorizedException(message: String) : RuntimeException(message)

private suspend fun ApplicationCall.receiveProbeResult(json: Json): ProbeResult {
    request.headers[HttpHeaders.ContentLength]?.let { raw ->
        val contentLength = raw.toLongOrNull()
            ?: throw ApiBadRequestException("Content-Length must be a valid integer")
        requireParameter(contentLength in 0..MAX_PROBE_BODY_BYTES.toLong(), "Probe result body is too large")
    }

    val channel = receiveChannel()
    val bytes = ByteArray(MAX_PROBE_BODY_BYTES + 1)
    var total = 0
    while (total < bytes.size) {
        val read = channel.readAvailable(bytes, total, bytes.size - total)
        if (read == -1) break
        total += read
    }
    requireParameter(total <= MAX_PROBE_BODY_BYTES, "Probe result body is too large")
    requireParameter(total > 0, "Probe result body is required")

    val body = bytes.copyOf(total).toString(Charsets.UTF_8)
    return try {
        json.decodeFromString<ProbeResult>(body)
    } catch (_: SerializationException) {
        throw ApiBadRequestException("Probe result body must be valid JSON")
    } catch (_: IllegalArgumentException) {
        throw ApiBadRequestException("Probe result body must be valid JSON")
    }
}

private fun constantTimeEquals(expected: String, supplied: String?): Boolean {
    if (supplied == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        supplied.toByteArray(Charsets.UTF_8),
    )
}

private const val PROBER_TOKEN_HEADER = "X-SpdVpn-Prober-Token"
private const val MAX_PROBE_BODY_BYTES = 4 * 1_024
