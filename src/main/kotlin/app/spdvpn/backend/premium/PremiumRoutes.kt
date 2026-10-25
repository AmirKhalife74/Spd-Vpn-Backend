package app.spdvpn.backend.premium

import app.spdvpn.backend.model.ApiResponse
import app.spdvpn.backend.service.CatalogService
import app.spdvpn.backend.service.RefreshResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import app.spdvpn.backend.panel.*

fun Route.premiumRoutes(runtime: PremiumRuntime, catalog: CatalogService, json: Json) {
    val service = runtime.service
    val security = service.security
    val config = runtime.config
    val manualRefresh = Mutex()
    var lastManualAttempt = 0L
    fun ApplicationCall.originCheck() {
        if (request.headers[HttpHeaders.Origin] != config.adminOrigin ||
            request.headers["Sec-Fetch-Site"]?.let { it !in setOf("same-origin", "none") } == true)
            throw PremiumProblem(403, "ORIGIN_INVALID", "مبدأ درخواست معتبر نیست")
        if (config.insecureLocalCookies && request.origin.remoteHost !in setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1"))
            throw PremiumProblem(403, "LOCAL_ONLY", "HTTP مدیریتی فقط روی loopback مجاز است")
    }
    suspend fun ApplicationCall.admin(mutation: Boolean = false): AdminPrincipal {
        if (mutation) originCheck()
        return security.admin(request.cookies[ADMIN_COOKIE], request.headers["X-CSRF-Token"], mutation)
    }
    fun ApplicationCall.cookie(value: String, seconds: Long) {
        response.header(HttpHeaders.SetCookie, "$ADMIN_COOKIE=$value; Path=/admin; Max-Age=$seconds; HttpOnly; SameSite=Strict" +
            if (config.insecureLocalCookies) "" else "; Secure")
    }
    get("/admin") { call.respondAsset("index.html", ContentType.Text.Html) }
    get("/admin/") { call.respondAsset("index.html", ContentType.Text.Html) }
    get("/admin/app.js") { call.respondAsset("app.js", ContentType.Application.JavaScript) }
    get("/admin/styles.css") { call.respondAsset("styles.css", ContentType.Text.CSS) }
    route("/admin/api") {
        intercept(io.ktor.server.application.ApplicationCallPipeline.Call) { call.response.header(HttpHeaders.CacheControl, "no-store") }
        post("/login") {
            call.originCheck()
            val session = security.loginAdmin(call.smallBody(json), call.request.origin.remoteHost)
            call.cookie(session.token, config.adminSessionMillis / 1000)
            call.respond(ApiResponse(data = session.data))
        }
        get("/session") {
            val admin = call.admin()
            call.respond(ApiResponse(data = AdminSessionData(admin.username, admin.csrf, admin.expiresAt)))
        }
        post("/logout") {
            val admin = call.admin(true)
            security.logoutAdmin(call.request.cookies[ADMIN_COOKIE], admin.username)
            call.cookie("", 0)
            call.respond(ApiResponse(data = mapOf("loggedOut" to true)))
        }
        get("/dashboard") {
            call.admin()
            call.respond(ApiResponse(data = DashboardData(service.dashboard(), runtime.panel.dashboard(), service.configured)))
        }
        get("/plans") { call.admin(); call.respond(ApiResponse(data = service.plans())) }
        post("/plans") {
            val admin = call.admin(true)
            call.respond(HttpStatusCode.Created, ApiResponse(data = service.createPlan(call.smallBody(json), admin.username)))
        }
        get("/premium-purchase") {
            call.admin()
            call.respond(ApiResponse(data = runtime.panel.premiumPurchaseSettings()))
        }
        put("/premium-purchase") {
            call.admin(true)
            call.respond(ApiResponse(data = runtime.panel.updatePremiumPurchaseSettings(call.smallBody(json))))
        }
        put("/plans/{id}") {
            val admin = call.admin(true)
            val document = call.smallBody<JsonObject>(json)
            val plan = try { json.decodeFromJsonElement<Plan>(document) }
                catch (_: SerializationException) { bad("بدنهٔ JSON معتبر نیست") }
            if (plan.id != call.parameters["id"]) bad("شناسهٔ پلن یکسان نیست")
            call.respond(ApiResponse(data = service.updatePlan(plan, admin.username, preserveAssignable = "isAssignable" !in document)))
        }
        get("/users") {
            call.admin()
            call.respond(ApiResponse(data = service.users(call.request.queryParameters["q"].orEmpty(), call.offset(), call.limit())))
        }
        post("/users") {
            val admin = call.admin(true)
            val user = service.create(call.smallBody(json), call.requestKey(), admin.username)
            call.respond(HttpStatusCode.Accepted, ApiResponse(data = user))
        }
        get("/users/{id}") { call.admin(); call.respond(ApiResponse(data = service.user(call.userId()))) }
        get("/users/{id}/operations") { call.admin(); call.respond(ApiResponse(data = service.operations(call.userId()))) }
        post("/users/{id}/subscription") {
            val admin = call.admin(true)
            call.respond(HttpStatusCode.Accepted, ApiResponse(data = service.change(call.userId(), call.smallBody(json), call.requestKey(), admin.username)))
        }
        post("/users/{id}/password") {
            val admin = call.admin(true)
            security.changePassword(call.userId(), call.smallBody<PasswordRequest>(json).password, admin.username)
            call.respond(ApiResponse(data = mapOf("changed" to true)))
        }
        post("/users/{id}/link") {
            val admin = call.admin(true)
            call.respond(HttpStatusCode.Accepted, ApiResponse(data = service.repairLink(call.userId(), call.smallBody(json), call.requestKey(), admin.username)))
        }
        post("/users/{id}/retry") {
            val admin = call.admin(true)
            call.respond(HttpStatusCode.Accepted, ApiResponse(data = service.retry(call.userId(), admin.username)))
        }
        post("/users/{id}/refresh") {
            val admin = call.admin(true)
            call.respond(ApiResponse(data = service.refreshUser(call.userId(), admin.username)))
        }
        get("/audit") { call.admin(); call.respond(ApiResponse(data = service.audit(call.offset(), call.limit()))) }
        get("/releases") { call.admin(); call.respond(ApiResponse(data = runtime.panel.releases())) }
        post("/releases") { call.admin(true); call.respond(ApiResponse(data = runtime.panel.createRelease(call.smallBody(json)))) }
        put("/releases/{id}") { call.admin(true); call.respond(ApiResponse(data = runtime.panel.updateRelease(call.contentId(), call.smallBody(json)))) }
        delete("/releases/{id}") { call.admin(true); runtime.panel.deleteRelease(call.contentId()); call.respond(ApiResponse(data = mapOf("deleted" to true))) }
        get("/announcements") { call.admin(); call.respond(ApiResponse(data = runtime.panel.announcements())) }
        post("/announcements") { call.admin(true); call.respond(ApiResponse(data = runtime.panel.createAnnouncement(call.smallBody(json)))) }
        put("/announcements/{id}") { call.admin(true); call.respond(ApiResponse(data = runtime.panel.updateAnnouncement(call.contentId(), call.smallBody(json)))) }
        delete("/announcements/{id}") { call.admin(true); runtime.panel.deleteAnnouncement(call.contentId()); call.respond(ApiResponse(data = mapOf("deleted" to true))) }
        get("/recommendation") { call.admin(); call.respond(ApiResponse(data = runtime.panel.recommendation())) }
        put("/recommendation") { call.admin(true); call.respond(ApiResponse(data = runtime.panel.updateRecommendation(call.smallBody(json)))) }
        get("/free") { call.admin(); call.respond(ApiResponse(data = catalog.health())) }
        post("/free/refresh") {
            val admin = call.admin(true)
            if (!manualRefresh.tryLock()) conflict("بروزرسانی در حال اجراست")
            try {
                val now = System.currentTimeMillis()
                if (now - lastManualAttempt < 30_000) conflict("برای بروزرسانی بعدی ۳۰ ثانیه صبر کنید")
                lastManualAttempt = now
                service.database.tx { service.database.audit(it, admin.username, "FREE_REFRESH_REQUESTED", now) }
                when (catalog.refreshIfIdle()) {
                    null -> conflict("pipeline فعلی در حال بروزرسانی است")
                    is RefreshResult.Failure -> unavailable("FREE_REFRESH_FAILED")
                    is RefreshResult.Success -> call.respond(ApiResponse(data = catalog.health()))
                }
            } finally { manualRefresh.unlock() }
        }
    }
}

/** Public/client APIs share catalog CORS; admin routes keep their own exact Origin + cookie + CSRF checks. */
fun Route.premiumPublicRoutes(runtime: PremiumRuntime, json: Json) {
    val service = runtime.service
    val security = service.security
    suspend fun ApplicationCall.owner(): String {
        response.header(HttpHeaders.CacheControl, "no-store")
        return security.owner(request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer "))
    }
    get("/v1/product") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(ApiResponse(data = service.product()))
    }
    route("/v1/app") {
        get("/bootstrap") {
            val platform = ClientPlatform.entries.firstOrNull { it.name == call.request.queryParameters["platform"] } ?: bad("platform معتبر نیست")
            val version = call.request.queryParameters["versionCode"]?.toIntOrNull()?.takeIf { it > 0 } ?: bad("versionCode معتبر نیست")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(ApiResponse(data = runtime.panel.bootstrap(platform, version)))
        }
        post("/presence/heartbeat") { call.respond(ApiResponse(data = runtime.panel.heartbeat(call.smallBody(json)))) }
        post("/presence/disconnect") { call.respond(ApiResponse(data = runtime.panel.disconnect(call.smallBody(json)))) }
    }
    route("/v1/account") {
        intercept(io.ktor.server.application.ApplicationCallPipeline.Call) { call.response.header(HttpHeaders.CacheControl, "no-store") }
        post("/login") { call.respond(ApiResponse(data = service.login(call.smallBody(json), call.request.origin.remoteHost))) }
        post("/refresh") { call.respond(ApiResponse(data = service.refreshSession(call.smallBody<RefreshRequest>(json).refreshToken))) }
        post("/logout") {
            security.logoutUser(call.smallBody<RefreshRequest>(json).refreshToken)
            call.respond(ApiResponse(data = mapOf("loggedOut" to true)))
        }
        get("/me") {
            val owner = call.owner()
            val profile = service.me(owner)
            if (call.owner() != owner) unauthorized()
            call.respond(ApiResponse(data = profile))
        }
    }
    route("/v1/premium") {
        get("/servers") {
            val owner = call.owner()
            val servers = service.catalog(owner)
            if (call.owner() != owner) unauthorized()
            call.respond(ApiResponse(data = servers))
        }
        get("/servers/{id}/profile") {
            val owner = call.owner()
            val id = call.parameters["id"]?.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: bad("شناسهٔ سرور معتبر نیست")
            val profile = service.xray(owner, id)
            if (call.owner() != owner) unauthorized()
            call.respond(ApiResponse(data = profile))
        }
    }
}

@kotlinx.serialization.Serializable
private data class DashboardData(val accounts: Map<String, Int>, val activity: AdminDashboardData, val premiumVpnConfigured: Boolean)

private const val ADMIN_COOKIE = "spd_admin_session"
private fun ApplicationCall.requestKey() = request.headers["Idempotency-Key"] ?: bad("Idempotency-Key لازم است")
private fun ApplicationCall.contentId() = parameters["id"]?.takeIf { it.matches(Regex("[A-Za-z0-9-]{8,64}")) } ?: bad("شناسه معتبر نیست")
private fun ApplicationCall.userId() = parameters["id"]?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: bad("شناسه معتبر نیست")
private fun ApplicationCall.offset() = request.queryParameters["offset"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: bad("offset معتبر نیست") } ?: 0
private fun ApplicationCall.limit() = request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..100 } ?: bad("limit معتبر نیست") } ?: 25
private suspend inline fun <reified T> ApplicationCall.smallBody(json: Json): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) bad("Content-Type باید application/json باشد")
    val channel = receiveChannel(); val buffer = ByteArray(16_385); var count = 0
    while (count < buffer.size) {
        val read = channel.readAvailable(buffer, count, buffer.size - count)
        if (read == -1) break
        count += read
    }
    if (count > 16_384) bad("بدنهٔ درخواست بزرگ است")
    return try { json.decodeFromString<T>(buffer.decodeToString(0, count)) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { bad("بدنهٔ JSON معتبر نیست") }
}
private suspend fun ApplicationCall.respondAsset(name: String, type: ContentType) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
    response.header("X-Frame-Options", "DENY")
    val bytes = PremiumRuntime::class.java.getResourceAsStream("/admin/$name")?.use { it.readBytes() } ?: missing()
    respondBytes(bytes, type)
}
