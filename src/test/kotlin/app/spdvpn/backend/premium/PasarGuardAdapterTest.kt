package app.spdvpn.backend.premium

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class PasarGuardAdapterTest {
    @Test fun `v5 adapter uses verified routes form login ISO dates numeric id and absolute targets`() = runBlocking {
        val seen = mutableListOf<String>()
        var quota: Long? = 123456
        var expiry: String? = "2027-01-01T00:00:00Z"
        val marker = "test-marker"
        val privateTestValue = "test-private-value-do-not-forward"
        val profile = buildJsonObject {
            put("remarks", "Actual upstream test fixture")
            put("inbounds", JsonArray(listOf(buildJsonObject { put("protocol", "tun") })))
            put("outbounds", JsonArray(listOf(buildJsonObject {
                put("protocol", "vless")
                put("settings", buildJsonObject { put("vnext", JsonArray(listOf(buildJsonObject {
                    put("address", "vpn.example.test"); put("port", 443)
                    put("users", JsonArray(listOf(buildJsonObject { put("id", privateTestValue) })))
                }))) })
                put("streamSettings", buildJsonObject { put("network", "tcp") })
            })))
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            seen += "${exchange.requestMethod} $path"
            val output: String
            if (path == "/api/admin/token") {
                assertTrue(exchange.requestHeaders.getFirst("Content-Type").startsWith("application/x-www-form-urlencoded"))
                assertTrue(body.contains("username=test-admin")); assertTrue(body.contains("password="))
                output = """{"access_token":"test_bearer_token_1234567890","token_type":"bearer"}"""
            } else {
                assertTrue(exchange.requestHeaders.getFirst("Authorization")?.startsWith("Bearer ") == true)
                if (exchange.requestMethod in setOf("POST", "PUT")) {
                    val obj = Json.parseToJsonElement(body).jsonObject
                    if (exchange.requestMethod == "POST") {
                        assertEquals(JsonPrimitive(7), obj["group_ids"]?.jsonArray?.single())
                        assertEquals("no_reset", obj["data_limit_reset_strategy"]?.jsonPrimitive?.content)
                    }
                    val exp = obj.getValue("expire").jsonPrimitive
                    expiry = if (exp.intOrNull == 0) null else exp.content
                    quota = obj.getValue("data_limit").jsonPrimitive.long.takeIf { it > 0 }
                }
                output = when {
                    path.endsWith("/subscription/xray") -> JsonArray(listOf(profile,
                        JsonObject(profile.toMutableMap().apply { remove("inbounds") }),
                        JsonObject(profile.toMutableMap().apply { put("test", buildJsonObject { put("allowInsecure", true) }) }))).toString()
                    else -> buildJsonObject {
                        put("id", 42); put("username", "spd_test"); put("status", "active")
                        put("expire", expiry?.let(::JsonPrimitive) ?: JsonNull)
                        put("data_limit", quota?.let(::JsonPrimitive) ?: JsonNull)
                        put("used_traffic", 0); put("note", marker)
                        put("subscription_url", privateTestValue); put("proxy_settings", buildJsonObject { put("private", privateTestValue) })
                    }.toString()
                }
            }
            val bytes = output.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val config = PremiumConfig(pasarGuardUrl = "http://127.0.0.1:${server.address.port}",
            pasarGuardUsername = "test-admin", pasarGuardPassword = "test-password", pasarGuardGroupIds = listOf(7), allowLocalPasarGuardHttp = true)
        val adapter = HttpPasarGuard(config)
        try {
            val first = adapter.byUsername("spd_test")!!
            assertEquals(42L, first.id); assertFalse(first.toString().contains(privateTestValue))
            val trialBytes = 10_000_000_000L // Decimal 10 GB, not 10 GiB.
            adapter.create("spd_test", marker, SyncTarget(first.expiresAtEpochMillis, trialBytes))
            assertEquals(trialBytes, adapter.byId(42)!!.quotaBytes)
            adapter.update(42, SyncTarget(null, null))
            val permanent = adapter.byId(42)!!
            assertNull(permanent.expiresAtEpochMillis); assertNull(permanent.quotaBytes)
            val profiles = adapter.profiles(42)
            assertEquals(1, profiles.size)
            assertFalse(profiles.single().summary.toString().contains(privateTestValue))
            assertTrue(seen.contains("PUT /api/user/by-id/42"))
            assertTrue(seen.contains("GET /api/user/42/subscription/xray"))
            assertEquals(1, seen.count { it.endsWith("/api/admin/token") })
        } finally { adapter.close(); server.stop(0) }
    }
    @Test fun `upstream errors never forward body credentials or become successful mock data`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val secret = "test-only-sensitive-upstream-error"
        server.createContext("/") { e ->
            e.requestBody.close()
            val bytes = secret.toByteArray(); e.sendResponseHeaders(500, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val adapter = HttpPasarGuard(PremiumConfig(pasarGuardUrl = "http://127.0.0.1:${server.address.port}",
            pasarGuardUsername = "test-admin", pasarGuardPassword = "test-password", pasarGuardGroupIds = listOf(1), allowLocalPasarGuardHttp = true))
        try {
            val failure = assertFailsWith<PremiumProblem> { adapter.byId(1) }
            assertEquals(503, failure.status); assertFalse(failure.safeMessage.contains(secret))
        } finally { adapter.close(); server.stop(0) }
    }
    @Test fun `remote HTTP and incomplete configuration are rejected outside explicit loopback tests`() {
        assertFailsWith<IllegalArgumentException> { PremiumConfig(pasarGuardUrl = "https://example.test") }
        assertFailsWith<IllegalArgumentException> { PremiumConfig(pasarGuardUrl = "http://example.test", pasarGuardUsername = "a", pasarGuardPassword = "b", pasarGuardGroupIds = listOf(1), allowLocalPasarGuardHttp = true) }
        assertFailsWith<IllegalArgumentException> { PremiumConfig(adminOrigin = "http://example.test", insecureLocalCookies = true) }
        assertFalse(HttpPasarGuard(PremiumConfig()).let { try { it.configured } finally { it.close() } })
    }
}
