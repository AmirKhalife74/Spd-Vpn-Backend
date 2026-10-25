package app.spdvpn.backend.premium

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class PasarGuardTunNormalizationTest {
    private fun fixture(host: String, alias: String = "Named logical test server") = buildJsonObject {
        put("remarks", alias)
        put("inbounds", buildJsonArray {
            add(buildJsonObject { put("protocol", "socks"); put("tag", "socks-in"); put("port", 1080) })
            add(buildJsonObject { put("protocol", "http"); put("tag", "http-in"); put("port", 8080) })
        })
        put("outbounds", buildJsonArray { add(buildJsonObject {
            put("tag", "proxy"); put("protocol", "vless")
            put("settings", buildJsonObject { put("vnext", buildJsonArray { add(buildJsonObject {
                put("address", host); put("port", 443)
                put("users", buildJsonArray { add(buildJsonObject { put("id", "test-only-not-a-real-credential") }) })
            }) }) })
            put("streamSettings", buildJsonObject {
                put("network", "tcp"); put("security", "reality")
                put("realitySettings", buildJsonObject { put("serverName", "tls.example.test") })
            })
        }) })
        put("dns", buildJsonObject { put("servers", buildJsonArray { add("1.1.1.1") }) })
        put("routing", buildJsonObject { put("rules", buildJsonArray { add(buildJsonObject {
            put("type", "field"); put("inboundTag", buildJsonArray { add("http-in"); add("socks-in") })
            put("outboundTag", "proxy")
        }) }) })
    }

    @Test fun `named rotating endpoints preserve id and current secure config while converting only local inputs`() = runBlocking {
        var configs = listOf(fixture("one.example.test"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            e.requestBody.close()
            val body = if (e.requestURI.path == "/api/admin/token")
                """{"access_token":"test-token-only-fixture-long-enough","token_type":"bearer"}"""
            else JsonArray(configs).toString()
            val bytes = body.toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val adapter = HttpPasarGuard(PremiumConfig(pasarGuardUrl = "http://127.0.0.1:${server.address.port}",
            pasarGuardUsername = "test-admin", pasarGuardPassword = "test-password", pasarGuardGroupIds = listOf(1),
            allowLocalPasarGuardHttp = true))
        try {
            val first = adapter.profiles(1).single()
            val rotated = fixture("two.example.test")
            configs = listOf(rotated)
            val second = adapter.profiles(1).single()
            assertEquals(first.summary.id, second.summary.id)
            for (key in listOf("dns", "outbounds")) assertEquals(rotated[key], second.config[key])
            assertEquals("two.example.test", second.config["outbounds"]!!.jsonArray[0].jsonObject["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content)
            assertEquals("tun", second.config["inbounds"]!!.jsonArray.single().jsonObject["protocol"]!!.jsonPrimitive.content)
            assertEquals(listOf(JsonPrimitive("spd-tun")), second.config["routing"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject["inboundTag"]!!.jsonArray.toList())
            configs = listOf(fixture("one.example.test"), fixture("two.example.test"))
            assertTrue(adapter.profiles(1).isEmpty(), "Ambiguous same-name/protocol/transport must fail closed")
            configs = listOf(fixture("one.example.test", ""))
            val unnamed = adapter.profiles(1).single()
            configs = listOf(fixture("two.example.test", ""))
            assertNotEquals(unnamed.summary.id, adapter.profiles(1).single().summary.id)
            configs = listOf(JsonObject(rotated + ("test" to buildJsonObject { put("allowInsecure", true) })))
            assertTrue(adapter.profiles(1).isEmpty())
        } finally { adapter.close(); server.stop(0) }
    }
}
