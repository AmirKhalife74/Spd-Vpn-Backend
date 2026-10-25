package app.spdvpn.backend

import app.spdvpn.backend.premium.PremiumConfig
import app.spdvpn.backend.premium.PremiumRuntime
import app.spdvpn.backend.premium.PremiumProblem
import app.spdvpn.backend.premium.premiumRoutes
import app.spdvpn.backend.premium.premiumPublicRoutes
import kotlinx.coroutines.runBlocking

import app.spdvpn.backend.api.ApiBadRequestException
import app.spdvpn.backend.api.ApiNotFoundException
import app.spdvpn.backend.api.ApiUnauthorizedException
import app.spdvpn.backend.api.apiRoutes
import app.spdvpn.backend.cache.FileCatalogCacheStore
import app.spdvpn.backend.config.AppConfig
import app.spdvpn.backend.health.FileServerHealthSnapshotStore
import app.spdvpn.backend.health.ServerHealthTracker
import app.spdvpn.backend.health.StaleProbeResultException
import app.spdvpn.backend.model.ApiError
import app.spdvpn.backend.model.ApiErrorResponse
import app.spdvpn.backend.service.CatalogService
import app.spdvpn.backend.service.CatalogUnavailableException
import app.spdvpn.backend.service.InvalidProbeResultException
import app.spdvpn.backend.service.ProbeServerNotFoundException
import app.spdvpn.backend.source.HttpVpnGateSource
import app.spdvpn.backend.source.HttpVpnGateHtmlSource
import app.spdvpn.backend.source.VpnGateCsvParser
import app.spdvpn.backend.source.VpnGateHtmlParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.cors.CORSConfig
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.net.URI

fun Application.module() {
    val config = AppConfig.fromEnvironment()
    val json = applicationJson()
    val httpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.fetchTimeout.inWholeMilliseconds
            connectTimeoutMillis = config.fetchTimeout.inWholeMilliseconds
            socketTimeoutMillis = config.fetchTimeout.inWholeMilliseconds
        }
    }
    val service = CatalogService(
        source = HttpVpnGateSource(httpClient, config.sourceUrl),
        parser = VpnGateCsvParser(),
        htmlSource = HttpVpnGateHtmlSource(httpClient, config.htmlUrl),
        htmlParser = VpnGateHtmlParser(),
        cacheStore = FileCatalogCacheStore(config.cachePath, json),
        refreshInterval = config.refreshInterval,
        maxStaleAge = config.maxStaleAge,
        healthTracker = ServerHealthTracker(
            snapshotStore = FileServerHealthSnapshotStore(config.healthSnapshotPath, json),
        ),
    )
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val premiumConfig = PremiumConfig.fromEnvironment()
    require(listOf(config.cachePath, config.healthSnapshotPath, premiumConfig.databasePath, premiumConfig.panelContentPath)
        .map { it.toAbsolutePath().normalize() }.distinct().size == 4) { "Catalog, health, panel, and account storage paths must differ" }
    val premium = runBlocking { PremiumRuntime.open(premiumConfig) }
    service.start(serviceScope)
    premium.start(serviceScope)
    configureApplication(service, config, json, premium)

    monitor.subscribe(ApplicationStopping) {
        serviceScope.cancel()
        httpClient.close()
        premium.close()
    }
}

fun Application.configureApplication(
    service: CatalogService,
    config: AppConfig,
    json: Json = applicationJson(),
    premium: PremiumRuntime? = null,
) {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
    }
    install(ContentNegotiation) {
        json(json)
    }
    install(Compression) {
        gzip()
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/v1") &&
            !call.request.path().startsWith("/v1/account") && !call.request.path().startsWith("/v1/premium") }
    }
    // Public API CORS is not admin authorization. Scope it away from same-origin admin routes:
    // behind TLS termination, Ktor's local HTTP origin must not reject admin PUT before CSRF.
    val publicCors: CORSConfig.() -> Unit = {
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.IfNoneMatch)
        exposeHeader(HttpHeaders.ETag)
        exposeHeader("X-Data-Stale")

        if (config.corsAllowedHosts.any { it == "*" }) {
            anyHost()
        } else {
            config.corsAllowedHosts.forEach { configuredHost ->
                val uri = URI(if (configuredHost.contains("://")) configuredHost else "https://$configuredHost")
                allowHost(uri.authority, schemes = listOfNotNull(uri.scheme))
            }
        }
    }
    install(RateLimit) {
        global {
            rateLimiter(
                limit = config.rateLimitRequests,
                refillPeriod = config.rateLimitWindow,
            )
            requestKey { call ->
                call.request.headers["CF-Connecting-IP"]
                    ?: call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
                    ?: call.request.origin.remoteHost
            }
        }
    }
    install(StatusPages) {
        exception<PremiumProblem> { call, error ->
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondError(HttpStatusCode.fromValue(error.status), error.code, error.safeMessage)
        }
        exception<ApiBadRequestException> { call, error ->
            call.respondError(HttpStatusCode.BadRequest, "BAD_REQUEST", error.message.orEmpty())
        }
        exception<ApiNotFoundException> { call, error ->
            call.respondError(HttpStatusCode.NotFound, "NOT_FOUND", error.message.orEmpty())
        }
        exception<ApiUnauthorizedException> { call, error ->
            call.respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", error.message.orEmpty())
        }
        exception<InvalidProbeResultException> { call, error ->
            call.respondError(HttpStatusCode.BadRequest, "BAD_REQUEST", error.message.orEmpty())
        }
        exception<StaleProbeResultException> { call, error ->
            call.respondError(HttpStatusCode.BadRequest, "BAD_REQUEST", error.message.orEmpty())
        }
        exception<ProbeServerNotFoundException> { call, error ->
            call.respondError(HttpStatusCode.NotFound, "NOT_FOUND", error.message.orEmpty())
        }
        exception<CatalogUnavailableException> { call, error ->
            call.respondError(HttpStatusCode.ServiceUnavailable, "CATALOG_UNAVAILABLE", error.message.orEmpty())
        }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respondError(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Too many requests")
        }
        exception<Throwable> { call, error ->
            if (error is CancellationException) throw error
            if (call.request.path().startsWith("/admin") || call.request.path().startsWith("/v1/account") ||
                call.request.path().startsWith("/v1/premium")) {
                call.application.environment.log.error("Private API request failed (details suppressed)")
            } else call.application.environment.log.error("Unhandled request error", error)
            call.respondError(
                HttpStatusCode.InternalServerError,
                "INTERNAL_ERROR",
                "An unexpected error occurred",
            )
        }
    }
    routing {
        route("/") {
            install(CORS, publicCors)
            apiRoutes(service, config.proberSharedSecret, json)
            if (premium != null) premiumPublicRoutes(premium, json)
        }
        if (premium != null) premiumRoutes(premium, service, json)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respond(status, ApiErrorResponse(error = ApiError(code = code, message = message)))
}

fun applicationJson(): Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
