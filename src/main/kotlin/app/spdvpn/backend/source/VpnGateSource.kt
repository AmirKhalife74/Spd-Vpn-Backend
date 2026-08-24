package app.spdvpn.backend.source

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

fun interface VpnGateSource {
    suspend fun fetch(): String
}

fun interface VpnGateHtmlSource {
    suspend fun fetch(): String
}

class HttpVpnGateSource(
    private val client: HttpClient,
    private val sourceUrl: String,
) : VpnGateSource {
    override suspend fun fetch(): String {
        val response = client.get(sourceUrl) {
            accept(ContentType.Text.Plain)
            header(HttpHeaders.UserAgent, "SPD-VPN-Backend/0.1")
        }
        check(response.status.isSuccess()) {
            "VPN Gate returned HTTP ${response.status.value}"
        }
        return response.body<String>()
    }
}

class HttpVpnGateHtmlSource(
    private val client: HttpClient,
    private val htmlUrl: String,
) : VpnGateHtmlSource {
    override suspend fun fetch(): String {
        val response = client.get(htmlUrl) {
            accept(ContentType.Text.Html)
            header(HttpHeaders.UserAgent, "SPD-VPN-Backend/0.1")
        }
        check(response.status.isSuccess()) {
            "VPN Gate HTML metadata returned HTTP ${response.status.value}"
        }
        return response.body<String>()
    }
}
