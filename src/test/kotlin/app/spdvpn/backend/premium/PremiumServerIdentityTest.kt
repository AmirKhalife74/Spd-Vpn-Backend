package app.spdvpn.backend.premium

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real HTTP adapter, loopback fixture only: no production account or private subscription. */
class PremiumServerIdentityTest {
    private fun config(host: String = "one.example.test", port: Int = 443, name: String? = "Logical server",
        protocol: String = "vless", transport: String = "tcp", credential: String = "test-only-value") = buildJsonObject {
        name?.let { put("remarks", it) }
        put("inbounds", buildJsonArray { add(buildJsonObject { put("protocol", "tun") }) })
        put("outbounds", buildJsonArray { add(buildJsonObject {
            put("protocol", protocol)
            put("settings", buildJsonObject { put(if (protocol in setOf("vless", "vmess")) "vnext" else "servers",
                buildJsonArray { add(buildJsonObject {
                    put("address", host); put("port", port)
                    if (protocol in setOf("vless", "vmess")) put("users", buildJsonArray {
                        add(buildJsonObject { put("id", credential) })
                    }) else { put("password", credential); put("method", "aes-256-gcm") }
                }) }) })
            put("streamSettings", buildJsonObject { put("network", transport) })
        }) })
    }

    private class Upstream : AutoCloseable {
        @Volatile var configs: List<JsonObject> = emptyList()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.close()
                val body = if (exchange.requestURI.path == "/api/admin/token")
                    """{"access_token":"test-fixture-token-long-enough-only","token_type":"bearer"}"""
                else JsonArray(configs).toString()
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        fun adapter() = HttpPasarGuard(PremiumConfig(pasarGuardUrl = "http://127.0.0.1:${server.address.port}",
            pasarGuardUsername = "test-admin", pasarGuardPassword = "test-password",
            pasarGuardGroupIds = listOf(1), allowLocalPasarGuardHttp = true))
        override fun close() = server.stop(0)
    }

    @Test fun `named ids survive endpoint credential rotation and adapter restart for all accepted protocols`() = runBlocking {
        Upstream().use { upstream ->
            for (protocol in listOf("vless", "vmess", "trojan", "shadowsocks")) {
                upstream.configs = listOf(config(protocol = protocol))
                val firstAdapter = upstream.adapter()
                val id = try { firstAdapter.profiles(1).single().summary.id } finally { firstAdapter.close() }
                val updated = config("two.example.test", 8443, protocol = protocol, credential = "rotated-test-value")
                upstream.configs = listOf(updated)
                val restartedAdapter = upstream.adapter()
                try {
                    val fresh = restartedAdapter.profiles(1).single()
                    assertEquals(id, fresh.summary.id, protocol)
                    assertEquals(updated, fresh.config)
                } finally { restartedAdapter.close() }
            }
        }
    }

    @Test fun `historical endpoint hash changes on rotation while current named id does not`() = runBlocking {
        fun historical(host: String, port: Int, profile: JsonObject) =
            sha256("vless|$host|$port|tcp|${profile["remarks"] ?: ""}").take(32)
        Upstream().use { upstream ->
            val first = config(); val rotated = config("two.example.test", 8443)
            assertNotEquals(historical("one.example.test", 443, first), historical("two.example.test", 8443, rotated))
            val adapter = upstream.adapter()
            try {
                upstream.configs = listOf(first); val id = adapter.profiles(1).single().summary.id
                upstream.configs = listOf(rotated); assertEquals(id, adapter.profiles(1).single().summary.id)
            } finally { adapter.close() }
        }
    }

    @Test fun `IP and port rotations independently preserve the logical v2 identity and return fresh config`() = runBlocking {
        Upstream().use { upstream ->
            val adapter = upstream.adapter()
            try {
                upstream.configs = listOf(config(host = "192.0.2.1", port = 443))
                val expected = sha256("logical-v2|vless|tcp|Logical server").take(32)
                assertEquals(expected, adapter.profiles(1).single().summary.id)
                for (fresh in listOf(config(host = "192.0.2.2", port = 443),
                    config(host = "192.0.2.2", port = 8443))) {
                    upstream.configs = listOf(fresh)
                    val issued = adapter.profiles(1).single()
                    assertEquals(expected, issued.summary.id)
                    assertEquals(fresh, issued.config)
                }
            } finally { adapter.close() }
        }
    }

    @Test fun `subscription order and unrelated additions do not change named identities`() = runBlocking {
        Upstream().use { upstream ->
            val adapter = upstream.adapter()
            try {
                val one = config(name = "One")
                val two = config(name = "Two", host = "two.example.test")
                upstream.configs = listOf(one, two)
                val original = adapter.profiles(1).associate { it.summary.displayName to it.summary.id }
                repeat(3) { refresh ->
                    upstream.configs = listOf(two, config(name = "Added $refresh"), one)
                    val current = adapter.profiles(1).associate { it.summary.displayName to it.summary.id }
                    assertEquals(original["One"], current["One"])
                    assertEquals(original["Two"], current["Two"])
                }
            } finally { adapter.close() }
        }
    }

    @Test fun `rename removal and duplicate aliases cannot resolve a previously requested identity`() = runBlocking {
        Upstream().use { upstream ->
            val adapter = upstream.adapter()
            try {
                upstream.configs = listOf(config())
                val requested = adapter.profiles(1).single().summary.id
                for (changed in listOf(emptyList(), listOf(config(name = "Renamed")),
                    listOf(config(), config(host = "two.example.test")))) {
                    upstream.configs = changed
                    assertTrue(adapter.profiles(1).none { it.summary.id == requested })
                }
                // Restoring the unique logical entry needs neither process state nor a mapping file.
                upstream.configs = listOf(config(host = "new.example.test"))
                assertEquals(requested, adapter.profiles(1).single().summary.id)
            } finally { adapter.close() }
        }
    }

    @Test fun `unnamed profiles have no guessed logical identity and rotating endpoints invalidate old ids`() = runBlocking {
        Upstream().use { upstream ->
            val adapter = upstream.adapter()
            try {
                upstream.configs = listOf(config(name = null))
                val original = adapter.profiles(1).single().summary.id
                assertEquals(sha256("endpoint-v2|vless|one.example.test|443|tcp").take(32), original)
                // Empty/whitespace remarks do not invent a name; identical refreshes remain stable.
                upstream.configs = listOf(config(name = "  "))
                assertEquals(original, adapter.profiles(1).single().summary.id)
                for (rotated in listOf(config(host = "two.example.test", name = null),
                    config(port = 8443, name = null))) {
                    upstream.configs = listOf(rotated)
                    assertTrue(adapter.profiles(1).none { it.summary.id == original })
                }
            } finally { adapter.close() }
        }
    }

    @Test fun `name protocol transport and unnamed endpoint changes intentionally change identity`() = runBlocking {
        Upstream().use { upstream ->
            val adapter = upstream.adapter()
            try {
                suspend fun id(value: JsonObject): String {
                    upstream.configs = listOf(value); return adapter.profiles(1).single().summary.id
                }
                val original = id(config())
                assertNotEquals(original, id(config(name = "Renamed server")))
                assertNotEquals(original, id(config(protocol = "vmess")))
                assertNotEquals(original, id(config(transport = "ws")))
                val unnamed = id(config(name = null))
                assertNotEquals(unnamed, id(config(host = "two.example.test", name = null)))
            } finally { adapter.close() }
        }
    }

    @Test fun `duplicate logical names or newly ineligible profiles disappear instead of matching another endpoint`() = runBlocking {
        Upstream().use { upstream ->
            val adapter = upstream.adapter()
            try {
                upstream.configs = listOf(config()); val id = adapter.profiles(1).single().summary.id
                upstream.configs = listOf(config(), config(host = "two.example.test"))
                assertTrue(adapter.profiles(1).none { it.summary.id == id })
                upstream.configs = listOf(JsonObject(config() + ("unsafe" to buildJsonObject { put("allowInsecure", true) })))
                assertTrue(adapter.profiles(1).isEmpty())
            } finally { adapter.close() }
        }
    }

    @Test fun `diagnostics contain bounded opaque ids but no untrusted values`() {
        val id = "a".repeat(32)
        assertTrue(PremiumProfileDiagnostics.lookup(id, false, 3).contains("code=NOT_FOUND serverId=$id"))
        val unsafe = PremiumProfileDiagnostics.lookup("secret\npassword=private", false, 0)
        assertFalse(unsafe.contains("secret")); assertFalse(unsafe.contains("private")); assertFalse(unsafe.contains('\n'))
        val many = PremiumProfileDiagnostics.catalog(List(1000) { id })
        assertTrue(many.contains("idsTruncated=true")); assertTrue(many.length < 4500)
    }
}
