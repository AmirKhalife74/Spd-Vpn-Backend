package app.spdvpn.backend.premium

import app.spdvpn.backend.applicationJson
import app.spdvpn.backend.configureApplication
import app.spdvpn.backend.cache.InMemoryCatalogCacheStore
import app.spdvpn.backend.config.AppConfig
import app.spdvpn.backend.service.CatalogService
import app.spdvpn.backend.source.VpnGateCsvParser
import app.spdvpn.backend.source.VpnGateSource
import app.spdvpn.backend.vpnGateCsv
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Browser HTTPS -> reverse proxy -> HTTP Ktor. Never uses production accounts or tokens. */
class AdminProxyCsrfTest {
    private val origin = "https://admin.example.test"
    private val json = applicationJson()
    private suspend fun runtime(): PremiumRuntime {
        val config = PremiumConfig(Files.createTempDirectory("spd-proxy-csrf-").resolve("accounts.sqlite"),
            adminOrigin = origin)
        return PremiumRuntime.open(config, TestPasarGuard(), PasswordHasher(8192, 1)).also {
            it.service.security.bootstrap("proxy-admin", "isolated-fixture-password-123")
        }
    }
    private suspend fun catalog() = CatalogService(VpnGateSource { vpnGateCsv() }, VpnGateCsvParser(),
        InMemoryCatalogCacheStore(), 300.seconds, 86400.seconds).also { it.refresh() }
    private fun config(hosts: List<String> = listOf("*")) = AppConfig("https://example.test/",
        refreshInterval = 300.seconds, fetchTimeout = 5.seconds, cachePath = Path.of("unused-proxy-cache.json"),
        maxStaleAge = 86400.seconds, rateLimitRequests = 10_000, rateLimitWindow = 60.seconds, corsAllowedHosts = hosts)

    private suspend fun HttpClient.adminRequest(method: HttpMethod, path: String, body: String? = null,
        cookie: String? = null, csrf: String? = null, requestOrigin: String? = origin,
        fetchSite: String = "same-origin"): HttpResponse = request(path) {
        this.method = method
        header(HttpHeaders.Host, "admin.example.test")
        header("X-Forwarded-Proto", "https") // Not trusted to manufacture request.origin.scheme.
        header(HttpHeaders.Referrer, "$origin/admin")
        header("Sec-Fetch-Site", fetchSite)
        requestOrigin?.let { header(HttpHeaders.Origin, it) }
        cookie?.let { header(HttpHeaders.Cookie, it) }
        csrf?.let { header("X-CSRF-Token", it) }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }
    private suspend fun HttpClient.session(): Pair<String, String> {
        val login = adminRequest(HttpMethod.Post, "/admin/api/login",
            """{"username":"proxy-admin","password":"isolated-fixture-password-123"}""")
        assertEquals(200, login.status.value)
        val cookie = login.headers[HttpHeaders.SetCookie]!!
        assertTrue(cookie.contains("; Secure")); assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Strict"))
        val csrf = json.parseToJsonElement(login.bodyAsText()).jsonObject["data"]!!.jsonObject["csrfToken"]!!.jsonPrimitive.content
        return cookie.substringBefore(';') to csrf
    }
    private suspend fun assertError(response: HttpResponse, status: Int, code: String) {
        assertEquals(status, response.status.value)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val data = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertFalse(data["success"]!!.jsonPrimitive.boolean)
        assertEquals(code, data["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun validCsrfPutBehindTlsProxyReachesRoute() = testApplication {
        val runtime = runtime(); val source = catalog()
        application { configureApplication(source, config(), premium = runtime) }
        val (cookie, csrf) = client.session()
        assertEquals(200, client.adminRequest(HttpMethod.Get, "/admin/api/premium-purchase", cookie = cookie).status.value)
        val response = client.adminRequest(HttpMethod.Put, "/admin/api/premium-purchase",
            """{"premiumPurchaseUrl":null,"premiumPurchaseEnabled":false}""", cookie, csrf)
        assertEquals(200, response.status.value)
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin]) // Admin is same-origin, not public CORS.
        assertEquals(false, json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject["premiumPurchaseEnabled"]!!.jsonPrimitive.boolean)
        assertTrue(runtime.service.users("", 0, 10).items.isEmpty())
    }

    @Test fun missingAndInvalidCsrfRemainStructuredForbiddenForAuthenticatedPut() = testApplication {
        val runtime = runtime(); val source = catalog()
        application { configureApplication(source, config(), premium = runtime) }
        val (cookie, csrf) = client.session()
        val before = runtime.panel.premiumPurchaseSettings()
        for (token in listOf(null, "incorrect-test-token")) {
            assertError(client.adminRequest(HttpMethod.Put, "/admin/api/premium-purchase", "{}", cookie, token), 403, "CSRF_INVALID")
        }
        assertError(client.adminRequest(HttpMethod.Put, "/admin/api/premium-purchase", "{}", csrf = csrf), 401, "TOKEN_INVALID")
        assertEquals(before, runtime.panel.premiumPurchaseSettings())
    }

    @Test fun originAndFetchMetadataCannotBeReplacedByRefererOrForwardedHeaders() = testApplication {
        val runtime = runtime(); val source = catalog()
        application { configureApplication(source, config(), premium = runtime) }
        val (cookie, csrf) = client.session()
        for (badOrigin in listOf(null, "https://foreign.example.test", "http://admin.example.test", "null")) {
            assertError(client.adminRequest(HttpMethod.Put, "/admin/api/premium-purchase", "{}", cookie, csrf,
                requestOrigin = badOrigin), 403, "ORIGIN_INVALID")
        }
        assertError(client.adminRequest(HttpMethod.Put, "/admin/api/premium-purchase", "{}", cookie, csrf,
            fetchSite = "cross-site"), 403, "ORIGIN_INVALID")
    }

    @Test fun existingAdminPlanPutAndLogoutPostKeepTheSameSessionAndCsrfRequirements() = testApplication {
        val runtime = runtime(); val source = catalog()
        application { configureApplication(source, config(), premium = runtime) }
        val (cookie, csrf) = client.session()
        val plan = runtime.service.plans().first()
        for (token in listOf(null, "incorrect-test-token")) {
            assertError(client.adminRequest(HttpMethod.Put, "/admin/api/plans/${plan.id}", json.encodeToString(plan), cookie, token), 403, "CSRF_INVALID")
        }
        assertEquals(200, client.adminRequest(HttpMethod.Put, "/admin/api/plans/${plan.id}", json.encodeToString(plan), cookie, csrf).status.value)
        assertEquals(plan, runtime.service.plans().first { it.id == plan.id })
        assertEquals(200, client.adminRequest(HttpMethod.Post, "/admin/api/logout", "{}", cookie, csrf).status.value)
        assertError(client.adminRequest(HttpMethod.Get, "/admin/api/session", cookie = cookie), 401, "TOKEN_INVALID")
    }

    @Test fun publicCorsPolicyStillWorksWithoutGrantingAdminCrossOriginAccess() = testApplication {
        val runtime = runtime(); val source = catalog()
        application { configureApplication(source, config(listOf("https://public.example.test")), premium = runtime) }
        for (path in listOf("/v1/product", "/v1/servers", "/health/live")) {
            val response = client.get(path) { header(HttpHeaders.Origin, "https://public.example.test") }
            assertEquals(200, response.status.value)
            assertEquals("https://public.example.test", response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals(403, client.get(path) { header(HttpHeaders.Origin, "https://foreign.example.test") }.status.value)
        }
        assertEquals(403, client.options("/v1/product") {
            header(HttpHeaders.Origin, "https://public.example.test")
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
        }.status.value)
        // Admin origin need not be in the unrelated public catalog CORS allowlist.
        val (cookie, csrf) = client.session()
        val response = client.adminRequest(HttpMethod.Put, "/admin/api/premium-purchase", "{}", cookie, csrf)
        assertEquals(200, response.status.value); assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
