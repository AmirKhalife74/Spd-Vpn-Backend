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
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

private suspend fun HttpResponse.objectData() = applicationJson().parseToJsonElement(bodyAsText()).jsonObject.getValue("data").jsonObject
private suspend fun HttpClient.jsonPost(path: String, body: String, cookie: String? = null, csrf: String? = null,
    origin: String = "http://localhost"): HttpResponse = post(path) {
    contentType(ContentType.Application.Json); setBody(body); header(HttpHeaders.Origin, origin)
    if (cookie != null) header(HttpHeaders.Cookie, cookie)
    if (csrf != null) header("X-CSRF-Token", csrf)
}
private suspend fun catalog(): CatalogService = CatalogService(VpnGateSource { vpnGateCsv() }, VpnGateCsvParser(),
    InMemoryCatalogCacheStore(), 300.seconds, 86400.seconds).also { it.refresh() }
private fun appConfig() = AppConfig("https://example.test/", refreshInterval = 300.seconds,
    fetchTimeout = 5.seconds, cachePath = Path.of("unused-test-cache.json"), maxStaleAge = 86400.seconds,
    rateLimitRequests = 10_000, rateLimitWindow = 60.seconds, corsAllowedHosts = listOf("*"))

class PremiumRoutesTest {
    @Test fun `purchase admin updates require session origin csrf and public product exposes only safe settings`() = testApplication {
        val f = PremiumFixture(); f.open(); f.service.security.bootstrap("test-admin", "admin-test-password-123")
        val source = catalog()
        application { configureApplication(source, appConfig(), premium = f.runtime) }
        val path = "/admin/api/premium-purchase"
        assertEquals(401, client.get(path).status.value)
        val login = client.jsonPost("/admin/api/login", """{"username":"test-admin","password":"admin-test-password-123"}""")
        val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val csrf = login.objectData()["csrfToken"]!!.jsonPrimitive.content
        suspend fun update(body: String, token: String? = csrf, origin: String = "http://localhost") = client.put(path) {
            contentType(ContentType.Application.Json); setBody(body)
            header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin)
            token?.let { header("X-CSRF-Token", it) }
        }
        val body = """{"premiumPurchaseUrl":"https://t.me/example_admin","premiumPurchaseEnabled":true}"""
        assertEquals(403, update(body, null).status.value)
        assertEquals(403, update(body, origin = "https://foreign.test").status.value)
        assertEquals(200, update(body).status.value)
        val public = client.get("/v1/product")
        assertEquals("no-store", public.headers[HttpHeaders.CacheControl])
        assertTrue(public.objectData()["premiumPurchaseEnabled"]!!.jsonPrimitive.boolean)
        assertEquals("https://t.me/example_admin", public.objectData()["premiumPurchaseUrl"]!!.jsonPrimitive.content)
        assertFalse(public.bodyAsText().contains("csrfToken")); assertFalse(public.bodyAsText().contains("password"))
        assertEquals(400, update("""{"premiumPurchaseUrl":"http://t.me/example_admin","premiumPurchaseEnabled":true}""").status.value)
        assertEquals(200, update(body.replace("example_admin", "another_admin")).status.value)
        assertEquals("https://t.me/another_admin", client.get("/v1/product").objectData()["premiumPurchaseUrl"]!!.jsonPrimitive.content)
        assertEquals(200, update("""{"premiumPurchaseEnabled":false}""").status.value)
        val disabled = client.get("/v1/product").objectData()
        assertFalse(disabled["premiumPurchaseEnabled"]!!.jsonPrimitive.boolean)
        assertNull(disabled["premiumPurchaseUrl"])
    }
    @Test fun `public product preserves wrapper and admin updates persist catalog metadata with auth and csrf`() = testApplication {
        val f = PremiumFixture(); f.open(); f.service.security.bootstrap("test-admin", "admin-test-password-123")
        val source = catalog()
        application { configureApplication(source, appConfig(), premium = f.runtime) }
        val p = f.service.plans().first().copy(priceMinor = 1999, currency = "USD", displayOrder = 6,
            localizedTitles = mapOf("en" to "Monthly", "fa" to "ماهانه"))
        val body = applicationJson().encodeToString(p)
        assertEquals(401, client.put("/admin/api/plans/${p.id}") {
            header(HttpHeaders.Origin, "http://localhost"); contentType(ContentType.Application.Json); setBody(body)
        }.status.value)
        val login = client.jsonPost("/admin/api/login", """{"username":"test-admin","password":"admin-test-password-123"}""")
        val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val csrf = login.objectData()["csrfToken"]!!.jsonPrimitive.content
        assertEquals(403, client.put("/admin/api/plans/${p.id}") {
            header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json); setBody(body)
        }.status.value)
        assertEquals(200, client.put("/admin/api/plans/${p.id}") {
            header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, "http://localhost"); header("X-CSRF-Token", csrf)
            contentType(ContentType.Application.Json); setBody(body)
        }.status.value)
        val public = client.get("/v1/product") // No user or admin credentials required.
        assertEquals(200, public.status.value); assertEquals("no-store", public.headers[HttpHeaders.CacheControl])
        val data = public.objectData()
        val returned = data["plans"]!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == p.id }.jsonObject
        assertEquals(1999, returned["priceMinor"]!!.jsonPrimitive.long)
        assertEquals("USD", returned["currency"]!!.jsonPrimitive.content)
        assertEquals("Monthly", returned["localizedTitles"]!!.jsonObject["en"]!!.jsonPrimitive.content)
        assertTrue(public.bodyAsText().contains("\"success\":true"))
        assertFalse(public.bodyAsText().contains("csrfToken")); assertFalse(public.bodyAsText().contains("password"))
        for (plan in f.service.plans()) f.service.updatePlan(plan.copy(published = false), "test-admin")
        assertTrue(client.get("/v1/product").objectData()["plans"]!!.jsonArray.isEmpty())
    }
    @Test fun `catalog and profile independently refetch same owner and stale id returns structured not found`() = testApplication {
        val f = PremiumFixture(); f.open(); val user = f.create(); f.service.synchronizePending()
        val source = catalog()
        application { configureApplication(source, appConfig(), premium = f.runtime) }
        val token = f.service.login(CredentialsRequest(user.username, "test-password-12345"), "test-peer").accessToken
        val listing = client.get("/v1/premium/servers") { bearerAuth(token) }
        val id = listing.objectData()["servers"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("no-store", listing.headers[HttpHeaders.CacheControl])
        assertEquals(1, f.pg.profileCalls)
        val current = client.get("/v1/premium/servers/$id/profile") { bearerAuth(token) }
        assertEquals(200, current.status.value)
        assertEquals(id, current.objectData()["server"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(2, f.pg.profileCalls)
        f.pg.profileOverride = emptyList() // Upstream removed/reidentified/filtered the selected entry.
        val stale = client.get("/v1/premium/servers/$id/profile") { bearerAuth(token) }
        assertEquals(404, stale.status.value)
        val error = applicationJson().parseToJsonElement(stale.bodyAsText()).jsonObject
        assertEquals(false, error["success"]!!.jsonPrimitive.boolean)
        assertEquals("NOT_FOUND", error["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("no-store", stale.headers[HttpHeaders.CacheControl])
        assertEquals(3, f.pg.profileCalls)
        assertEquals(400, client.get("/v1/premium/servers/not-a-valid-id/profile") { bearerAuth(token) }.status.value)
        assertEquals(401, client.get("/v1/premium/servers/$id/profile").status.value)
        assertEquals(3, f.pg.profileCalls) // Neither invalid IDs nor unauthorized requests reach subscription.
    }
    @Test fun `secure production cookie is independent from user tokens and survives restart until absolute expiry`() = testApplication {
        val directory = java.nio.file.Files.createTempDirectory("spd-secure-cookie-test-")
        val config = PremiumConfig(directory.resolve("accounts.sqlite"), adminOrigin = "https://admin.example.test")
        val pg = TestPasarGuard()
        val runtime = PremiumRuntime.open(config, pg, PasswordHasher(8192, 1))
        runtime.service.security.bootstrap("secure-admin", "secure-test-password-123")
        val source = catalog()
        application { configureApplication(source, appConfig(), premium = runtime) }
        val response = client.jsonPost("/admin/api/login", """{"username":"secure-admin","password":"secure-test-password-123"}""", origin = "https://admin.example.test")
        assertEquals(200, response.status.value)
        val header = response.headers[HttpHeaders.SetCookie]!!
        assertTrue(header.contains("; Secure")); assertTrue(header.contains("HttpOnly"))
        val raw = header.substringBefore(';').substringAfter('=')
        val reopened = PremiumRuntime.open(config, pg, PasswordHasher(8192, 1))
        assertEquals("secure-admin", reopened.service.security.admin(raw).username)
    }
    @Test fun `admin routes require distinct session csrf and same origin with expiry and logout`() = testApplication {
        val f = PremiumFixture(); f.open(); f.service.security.bootstrap("test-admin", "admin-test-password-123")
        val source = catalog()
        application { configureApplication(source, appConfig(), premium = f.runtime) }
        assertEquals(401, client.get("/admin/api/users").status.value)
        assertEquals(403, client.jsonPost("/admin/api/login", """{"username":"test-admin","password":"admin-test-password-123"}""", origin = "https://foreign.test").status.value)
        assertEquals(401, client.jsonPost("/admin/api/login", """{"username":"test-admin","password":"wrong"}""").status.value)
        val login = client.jsonPost("/admin/api/login", """{"username":"test-admin","password":"admin-test-password-123"}""")
        assertEquals(200, login.status.value)
        val cookieHeader = login.headers[HttpHeaders.SetCookie]!!
        assertTrue(cookieHeader.contains("HttpOnly")); assertTrue(cookieHeader.contains("SameSite=Strict"))
        assertTrue(cookieHeader.contains("Path=/admin")); assertFalse(cookieHeader.contains("Secure"))
        val cookie = cookieHeader.substringBefore(';'); val csrf = login.objectData().getValue("csrfToken").jsonPrimitive.content
        assertEquals(200, client.get("/admin/api/users") { header(HttpHeaders.Cookie, cookie) }.status.value)
        assertEquals(403, client.jsonPost("/admin/api/logout", "{}", cookie).status.value)
        assertEquals(403, client.jsonPost("/admin/api/logout", "{}", cookie, csrf, "https://foreign.test").status.value)
        assertEquals(200, client.jsonPost("/admin/api/logout", "{}", cookie, csrf).status.value)
        assertEquals(401, client.get("/admin/api/users") { header(HttpHeaders.Cookie, cookie) }.status.value)
        val next = client.jsonPost("/admin/api/login", """{"username":"test-admin","password":"admin-test-password-123"}""")
        val expired = next.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        f.now += f.config.adminSessionMillis
        assertEquals(401, client.get("/admin/api/session") { header(HttpHeaders.Cookie, expired) }.status.value)
    }
    @Test fun `user owner is taken from bearer session and private profile requires current subscription`() = testApplication {
        val f = PremiumFixture(); f.open(); val one = f.create("first-user"); val two = f.create("second-user")
        f.service.synchronizePending()
        val source = catalog()
        application { configureApplication(source, appConfig(), premium = f.runtime) }
        suspend fun login(name: String) = client.jsonPost("/v1/account/login", """{"username":"$name","password":"test-password-12345"}""").objectData()
        val first = login(one.username); val second = login(two.username)
        val firstToken = first.getValue("accessToken").jsonPrimitive.content
        val secondToken = second.getValue("accessToken").jsonPrimitive.content
        val me = client.get("/v1/account/me?userId=${one.id}") { bearerAuth(secondToken) }.objectData()
        assertEquals(two.username, me.getValue("username").jsonPrimitive.content)
        assertEquals(401, client.get("/admin/api/users") { header(HttpHeaders.Cookie, "spd_admin_session=$firstToken") }.status.value)
        val listing = client.get("/v1/premium/servers") { bearerAuth(firstToken) }.objectData()
        val id = listing.getValue("servers").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
        assertFalse(listing.toString().contains("testOwner"))
        assertEquals(404, client.get("/v1/premium/servers/$id/profile") { bearerAuth(secondToken) }.status.value)
        val profile = client.get("/v1/premium/servers/$id/profile") { bearerAuth(firstToken) }
        assertEquals(200, profile.status.value); assertEquals("no-store", profile.headers[HttpHeaders.CacheControl])
        f.service.change(one.id, ChangeSubscriptionRequest("SET_DISABLED", disabled = true), "disable-key-123", "test-admin")
        assertTrue(client.get("/v1/premium/servers/$id/profile") { bearerAuth(firstToken) }.status.value in setOf(403, 503))
        val refresh = first.getValue("refreshToken").jsonPrimitive.content
        assertEquals(200, client.jsonPost("/v1/account/logout", """{"refreshToken":"$refresh"}""").status.value)
        assertEquals(401, client.get("/v1/account/me") { bearerAuth(firstToken) }.status.value)
        assertEquals(200, client.get("/v1/servers?limit=1").status.value) // Free compatibility.
    }
    @Test fun `login attempts are bounded and oversized or malformed bodies do not leak details`() = testApplication {
        val f = PremiumFixture(); f.open(); val source = catalog()
        application { configureApplication(source, appConfig(), premium = f.runtime) }
        repeat(5) { assertEquals(401, client.jsonPost("/v1/account/login", """{"username":"unknown","password":"wrong"}""").status.value) }
        assertEquals(429, client.jsonPost("/v1/account/login", """{"username":"unknown","password":"wrong"}""").status.value)
        assertEquals(400, client.jsonPost("/v1/account/login", "x".repeat(16_385)).status.value)
        val invalid = client.jsonPost("/v1/account/login", "not-json")
        assertEquals(400, invalid.status.value); assertFalse(invalid.bodyAsText().contains("not-json"))
        assertEquals(404, client.post("/admin/api/bootstrap").status.value)
    }
    @Test fun `public presence bootstrap and existing free config keep their wrapper`() = testApplication {
        val f = PremiumFixture(); f.open(); val source = catalog()
        application { configureApplication(source, appConfig(), premium = f.runtime) }
        val bootstrap = client.get("/v1/app/bootstrap?platform=ANDROID_DIRECT&versionCode=1").objectData()
        assertTrue(bootstrap.containsKey("announcements"))
        val payload = """{"sessionId":"test-session-123","installationId":"test-installation-123","platform":"ANDROID_DIRECT","appVersionCode":1,"appVersionName":"test","connectedAtEpochMillis":${f.now}}"""
        assertEquals(200, client.jsonPost("/v1/app/presence/heartbeat", payload).status.value)
        assertEquals(1, f.runtime.panel.dashboard().presence.activeConnections)
        assertEquals(200, client.jsonPost("/v1/app/presence/disconnect", """{"sessionId":"test-session-123"}""").status.value)
        assertEquals(0, f.runtime.panel.dashboard().presence.activeConnections)
        val listing = client.get("/v1/servers").objectData()
        val id = listing.getValue("servers").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
        assertTrue(client.get("/v1/servers/$id/config").objectData().containsKey("openVpnConfig"))
        val page = client.get("/admin")
        assertEquals(200, page.status.value)
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
    }
}
