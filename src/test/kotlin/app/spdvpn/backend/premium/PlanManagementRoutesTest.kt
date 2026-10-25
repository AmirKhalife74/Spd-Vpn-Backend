package app.spdvpn.backend.premium

import app.spdvpn.backend.applicationJson
import app.spdvpn.backend.configureApplication
import app.spdvpn.backend.cache.InMemoryCatalogCacheStore
import app.spdvpn.backend.config.AppConfig
import app.spdvpn.backend.service.CatalogService
import app.spdvpn.backend.source.VpnGateCsvParser
import app.spdvpn.backend.source.VpnGateSource
import app.spdvpn.backend.vpnGateCsv
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PlanManagementRoutesTest {
    @Test fun `only authorized admin with origin and csrf can create assign and manage private plans`() = testApplication {
        val f = PremiumFixture(); f.open(); f.service.security.bootstrap("test-admin", "admin-test-password-123")
        val source = CatalogService(VpnGateSource { vpnGateCsv() }, VpnGateCsvParser(), InMemoryCatalogCacheStore(),
            300.seconds, 86400.seconds).also { it.refresh() }
        val config = AppConfig("https://example.test/", refreshInterval = 300.seconds, fetchTimeout = 5.seconds,
            cachePath = Path.of("unused-plans-test.json"), maxStaleAge = 86400.seconds,
            rateLimitRequests = 10_000, rateLimitWindow = 60.seconds, corsAllowedHosts = listOf("*"))
        application { configureApplication(source, config, premium = f.runtime) }
        val json = applicationJson()
        val plan = Plan("TRIAL_PRIVATE", "Trial", durationDays = 7, quotaBytes = 10_000_000_000L,
            published = false, localizedTitles = mapOf("en" to "Trial", "fa" to "آزمایشی"))
        var cookie: String? = null; var csrf: String? = null
        suspend fun write(path: String, body: String, method: HttpMethod = HttpMethod.Post,
            token: String? = csrf, origin: String? = "http://localhost", bearer: String? = null, useCookie: Boolean = true) = client.request(path) {
            this.method = method; contentType(ContentType.Application.Json); setBody(body)
            if (useCookie) cookie?.let { header(HttpHeaders.Cookie, it) }
            token?.let { header("X-CSRF-Token", it) }; origin?.let { header(HttpHeaders.Origin, it) }
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header("Idempotency-Key", "plan-route-test-" + java.util.UUID.randomUUID())
        }
        suspend fun assertError(response: HttpResponse, status: Int, code: String) {
            assertEquals(status, response.status.value)
            assertEquals(code, json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
        assertError(write("/admin/api/plans", json.encodeToString(plan)), 401, "TOKEN_INVALID")
        val login = write("/admin/api/login", """{"username":"test-admin","password":"admin-test-password-123"}""")
        assertEquals(200, login.status.value)
        cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        csrf = json.parseToJsonElement(login.bodyAsText()).jsonObject["data"]!!.jsonObject["csrfToken"]!!.jsonPrimitive.content
        for (badToken in listOf(null, "invalid-test-token")) {
            assertError(write("/admin/api/plans", json.encodeToString(plan), token = badToken), 403, "CSRF_INVALID")
        }
        assertError(write("/admin/api/plans", json.encodeToString(plan), origin = "https://foreign.test"), 403, "ORIGIN_INVALID")
        val created = write("/admin/api/plans", json.encodeToString(plan))
        assertEquals(201, created.status.value)
        assertTrue(json.parseToJsonElement(created.bodyAsText()).jsonObject["success"]!!.jsonPrimitive.boolean)
        val adminPlans = client.get("/admin/api/plans") { header(HttpHeaders.Cookie, cookie!!) }
        assertTrue(adminPlans.bodyAsText().contains("TRIAL_PRIVATE"))
        val public = client.get("/v1/product")
        assertEquals("no-store", public.headers[HttpHeaders.CacheControl])
        val product = json.parseToJsonElement(public.bodyAsText()).jsonObject
        assertTrue(product["success"]!!.jsonPrimitive.boolean)
        assertEquals(3, product["data"]!!.jsonObject["plans"]!!.jsonArray.size)
        assertFalse(public.bodyAsText().contains("TRIAL_PRIVATE"))
        assertError(write("/admin/api/plans/${plan.id}", json.encodeToString(plan.copy(published = true)), HttpMethod.Put), 400, "PUBLIC_PLAN_LIMIT")
        val request = """{"username":"trial-route-user","password":"fixture-password-12345","planId":"TRIAL_PRIVATE"}"""
        val account = write("/admin/api/users", request)
        assertEquals(202, account.status.value)
        val accountId = json.parseToJsonElement(account.bodyAsText()).jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(plan.quotaBytes, f.service.user(accountId).requested.quotaBytes)
        val tokens = f.service.login(CredentialsRequest("trial-route-user", "fixture-password-12345"), "test-peer")
        assertError(write("/admin/api/plans", json.encodeToString(plan.copy(id = "SELF_GRANT")), bearer = tokens.accessToken, useCookie = false), 401, "TOKEN_INVALID")
        assertError(write("/admin/api/users/$accountId/subscription", """{"action":"CHANGE_PLAN","planId":"TRIAL_PRIVATE"}""",
            bearer = tokens.accessToken, useCookie = false), 401, "TOKEN_INVALID")
        assertEquals(200, write("/admin/api/plans/${plan.id}", json.encodeToString(plan.copy(isAssignable = false)), HttpMethod.Put).status.value)
        // Legacy admin PUT bodies cannot accidentally re-enable a newly restricted assignment policy.
        val legacy = JsonObject(json.encodeToJsonElement(plan).jsonObject.filterKeys { it != "isAssignable" })
        assertEquals(200, write("/admin/api/plans/${plan.id}", legacy.toString(), HttpMethod.Put).status.value)
        assertFalse(f.service.plans().single { it.id == plan.id }.isAssignable)
        assertError(write("/admin/api/users", request.replace("trial-route-user", "second-trial-user")), 400, "PLAN_NOT_ASSIGNABLE")
        assertEquals(plan.quotaBytes, f.service.user(accountId).requested.quotaBytes)
    }
}
