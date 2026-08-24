package app.spdvpn.prober.api

import app.spdvpn.prober.AdvancingSleeper
import app.spdvpn.prober.FakeClock
import app.spdvpn.prober.model.ProbeResultRequest
import app.spdvpn.prober.scheduling.ConfigRequestGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class HttpProbeApiTest {
    @Test
    fun `client fetches catalog and config then submits exact authenticated report`() = runBlocking {
        val requests = mutableListOf<String>()
        var reportBody = ""
        var reportToken: String? = null
        val client = mockClient { request ->
            requests += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/servers" -> respondJson(SERVERS_JSON)
                "/v1/servers/server-1/config" -> respondJson(CONFIG_JSON)
                "/internal/v1/probe-results" -> {
                    reportToken = request.headers[HttpProbeApi.PROBER_TOKEN_HEADER]
                    reportBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respondJson("""{"success":true,"data":{"serverId":"server-1","healthStatus":"HEALTHY","consecutiveFailures":0}}""")
                }
                else -> error("Unexpected request ${request.url}")
            }
        }
        val (api, sleeper) = api(client)

        val servers = api.fetchServers()
        val profile = api.fetchServerConfig("server-1")
        api.submitProbeResult(
            ProbeResultRequest(
                serverId = "server-1",
                success = true,
                stage = "CONNECTED",
                durationMs = 2_450,
                checkedAtEpochMillis = 1_786_870_000_000,
            ),
        )

        assertEquals(listOf("server-1"), servers.map { it.id })
        assertTrue(profile.contains("remote 203.0.113.1"))
        assertEquals("test-secret", reportToken)
        assertEquals(
            """{"serverId":"server-1","success":true,"stage":"CONNECTED","reason":null,"durationMs":2450,"checkedAtEpochMillis":1786870000000}""",
            reportBody,
        )
        assertFalse("latencyMs" in reportBody)
        assertEquals(
            listOf("/v1/servers", "/v1/servers/server-1/config", "/internal/v1/probe-results"),
            requests,
        )
        assertEquals(listOf(0L, 1_100L), sleeper.delays)
    }

    @Test
    fun `server ids are URL path encoded`() = runBlocking {
        var observedPath = ""
        val client = mockClient { request ->
            observedPath = request.url.encodedPath
            respondJson(CONFIG_JSON.replace("server-1", "server/with space"))
        }
        val (api, _) = api(client)

        api.fetchServerConfig("server/with space")

        assertEquals("/v1/servers/server%2Fwith%20space/config", observedPath)
    }

    @Test
    fun `429 and 5xx responses are infrastructure errors without response-body logging`() = runBlocking {
        val limited = api(mockClient { _ -> respond("", HttpStatusCode.TooManyRequests) }).first
        val unavailable = api(mockClient { _ -> respond("secret response", HttpStatusCode.BadGateway) }).first

        val rateFailure = assertFailsWith<ApiInfrastructureException> { limited.fetchServers() }
        val unavailableFailure = assertFailsWith<ApiInfrastructureException> {
            unavailable.fetchServerConfig("server-1")
        }

        assertEquals("API_RATE_LIMITED", rateFailure.reasonCode)
        assertEquals("API_UNAVAILABLE", unavailableFailure.reasonCode)
        assertFalse(unavailableFailure.message.orEmpty().contains("secret response"))
    }

    @Test
    fun `malformed successful JSON is an infrastructure error`() = runBlocking {
        val api = api(mockClient { _ -> respondJson("not-json") }).first

        val failure = assertFailsWith<ApiInfrastructureException> { api.fetchServers() }

        assertEquals("MALFORMED_API_RESPONSE", failure.reasonCode)
    }

    @Test
    fun `probe report serialization uses duration field`() {
        val encoded = HttpProbeApi.defaultJson.encodeToString(
            ProbeResultRequest(
                serverId = "id",
                success = false,
                stage = "TLS_HANDSHAKE",
                reason = "TLS_NEGOTIATION_FAILED",
                durationMs = 300,
                checkedAtEpochMillis = 900,
            ),
        )

        assertTrue("\"durationMs\":300" in encoded)
        assertFalse("latency" in encoded)
    }

    private fun api(client: HttpClient): Pair<HttpProbeApi, AdvancingSleeper> {
        val clock = FakeClock(1_000)
        val sleeper = AdvancingSleeper(clock)
        return HttpProbeApi(
            baseUrl = "http://api:8080",
            sharedSecret = "test-secret",
            configRequestGate = ConfigRequestGate(1_100.milliseconds, clock, sleeper),
            client = client,
        ) to sleeper
    }

    private fun mockClient(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient = HttpClient(MockEngine) {
        engine { addHandler(handler) }
        install(ContentNegotiation) { json(HttpProbeApi.defaultJson) }
        install(HttpTimeout)
        expectSuccess = false
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    companion object {
        private const val SERVERS_JSON = """{"success":true,"data":{"servers":[{"id":"server-1","hostName":"vpn.example.com","ip":"203.0.113.1","score":42,"healthStatus":"UNKNOWN","lastCheckedAtEpochMillis":null}],"page":{"offset":0,"limit":200,"returned":1,"total":1,"hasMore":false},"source":{"ignored":true}}}"""
        private const val CONFIG_JSON = """{"success":true,"data":{"id":"server-1","protocol":"UDP","port":1194,"openVpnConfig":"client\\nremote 203.0.113.1 1194\\n","source":{"ignored":true}}}"""
    }
}
