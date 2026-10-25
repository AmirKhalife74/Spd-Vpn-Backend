package app.spdvpn.backend.premium

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/** Contract pinned to PasarGuard v5.4.1, b56ffe369f542152c52c69733205baeaf3f6e4cd.
 * No subscription URLs, proxy credentials or upstream errors leave this adapter.
 */
interface PasarGuard {
    val configured: Boolean
    suspend fun byId(id: Long): RemoteUser?
    suspend fun byUsername(username: String): RemoteUser?
    suspend fun create(username: String, marker: String, target: SyncTarget): RemoteUser
    suspend fun update(id: Long, target: SyncTarget): RemoteUser
    suspend fun profiles(id: Long): List<IssuedProfile>
    fun close() {}
}

class HttpPasarGuard(private val config: PremiumConfig) : PasarGuard {
    private val logger = LoggerFactory.getLogger(HttpPasarGuard::class.java)
    override val configured = config.pasarGuardUrl != null
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.pasarGuardTimeoutMillis
            connectTimeoutMillis = config.pasarGuardTimeoutMillis
            socketTimeoutMillis = config.pasarGuardTimeoutMillis
        }
    }
    private val tokenMutex = Mutex()
    private var token: String? = null
    override fun close() = client.close()
    private fun url(path: String): String = (config.pasarGuardUrl ?: unavailable("PASARGUARD_NOT_CONFIGURED"))
        .trimEnd('/') + path
    private suspend fun bounded(response: HttpResponse): String {
        val channel = response.bodyAsChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count == -1) break
            if (output.size() + count > 2_097_152) { channel.cancel(null); unavailable("PASARGUARD_RESPONSE_INVALID") }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8)
    }
    private suspend fun access(): String = tokenMutex.withLock {
        token ?: run {
            val response = client.submitForm(url("/api/admin/token"), Parameters.build {
                append("username", config.pasarGuardUsername ?: unavailable())
                append("password", config.pasarGuardPassword ?: unavailable())
            })
            if (response.status.value != 200) { response.bodyAsChannel().cancel(null); unavailable("PASARGUARD_AUTH_FAILED") }
            val value = json.parseToJsonElement(bounded(response)).jsonObject["access_token"]?.jsonPrimitive?.content
                ?.takeIf { it.length in 20..8192 && it.all { c -> c.isLetterOrDigit() || c in "._-" } }
                ?: unavailable("PASARGUARD_RESPONSE_INVALID")
            token = value
            value
        }
    }
    private suspend fun request(path: String, method: HttpMethod = HttpMethod.Get, body: JsonObject? = null): String? = try {
        if (!configured) unavailable("PASARGUARD_NOT_CONFIGURED")
        suspend fun send(): HttpResponse = client.request(url(path)) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer ${access()}")
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        }
        var response = send()
        if (response.status.value == 401) {
            response.bodyAsChannel().cancel(null)
            tokenMutex.withLock { token = null }
            response = send()
        }
        when (response.status.value) {
            404 -> { response.bodyAsChannel().cancel(null); null }
            200, 201 -> bounded(response)
            409 -> { response.bodyAsChannel().cancel(null); throw PremiumProblem(409, "PASARGUARD_CONFLICT", "هویت کاربر VPN نیاز به بررسی مدیر دارد") }
            else -> { response.bodyAsChannel().cancel(null); unavailable("PASARGUARD_REQUEST_FAILED") }
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (problem: PremiumProblem) { throw problem }
    catch (_: Exception) { unavailable() }
    override suspend fun byId(id: Long) = user(request("/api/user/by-id/$id"))?.also {
        if (it.id != id) unavailable("PASARGUARD_ID_MISMATCH")
    }
    override suspend fun byUsername(username: String): RemoteUser? {
        if (!username.matches(Regex("[a-z0-9_]{3,32}"))) unavailable("PASARGUARD_ID_INVALID")
        return user(request("/api/user/by-username/$username"))?.also {
            if (it.username != username) unavailable("PASARGUARD_ID_MISMATCH")
        }
    }
    private fun targetJson(target: SyncTarget) = buildJsonObject {
        // PasarGuard: 0 clears expiry/quota; null in a PUT means leave unchanged.
        put("expire", target.expiresAtEpochMillis?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonPrimitive(0))
        put("data_limit", target.quotaBytes ?: 0)
        put("status", if (target.disabled) "disabled" else "active")
    }
    override suspend fun create(username: String, marker: String, target: SyncTarget): RemoteUser {
        // v5.4.1 does not accept disabled on create. Create disabled accounts only after initial sync.
        val body = JsonObject(targetJson(target).toMutableMap().apply {
            put("username", JsonPrimitive(username)); put("note", JsonPrimitive(marker))
            put("group_ids", JsonArray(config.pasarGuardGroupIds.map(::JsonPrimitive)))
            put("data_limit_reset_strategy", JsonPrimitive("no_reset"))
        })
        return user(request("/api/user", HttpMethod.Post, body)) ?: unavailable()
    }
    override suspend fun update(id: Long, target: SyncTarget): RemoteUser =
        user(request("/api/user/by-id/$id", HttpMethod.Put, targetJson(target))) ?: unavailable("PASARGUARD_USER_MISSING")
    private fun user(raw: String?): RemoteUser? {
        if (raw == null) return null
        try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val expiry = obj["expire"].let { value ->
                if (value == null || value is JsonNull || value.jsonPrimitive.content == "0") null
                else value.jsonPrimitive.longOrNull?.let { Math.multiplyExact(it, 1000) }
                    ?: OffsetDateTime.parse(value.jsonPrimitive.content).toInstant().toEpochMilli()
            }
            val quota = obj["data_limit"].let { value ->
                if (value == null || value is JsonNull) null else {
                    val number = value.jsonPrimitive.long
                    require(number >= 0)
                    number.takeIf { it > 0 }
                }
            }
            return RemoteUser(obj.getValue("id").jsonPrimitive.long.also { require(it > 0) },
                obj.getValue("username").jsonPrimitive.content.also { require(it.matches(Regex("[a-z0-9_]{3,32}"))) },
                obj.getValue("status").jsonPrimitive.content.also { require(it in setOf("active", "disabled", "expired", "limited", "on_hold")) },
                expiry, quota,
                obj.getValue("used_traffic").jsonPrimitive.long.also { require(it >= 0) },
                (obj["note"] as? JsonPrimitive)?.contentOrNull)
        } catch (_: Exception) { unavailable("PASARGUARD_RESPONSE_INVALID") }
    }
    override suspend fun profiles(id: Long): List<IssuedProfile> {
        val raw = request("/api/user/$id/subscription/xray") ?: unavailable("PASARGUARD_USER_MISSING")
        return try {
            val element = json.parseToJsonElement(raw)
            val configs = if (element is JsonArray) element else JsonArray(listOf(element))
            val eligible = configs.mapNotNull { candidate ->
                val upstream = candidate.jsonObject

// Check the original profile before changing local inbounds.
                if (unsafeTls(upstream)) return@mapNotNull null

                val obj = normalizeSpdTunProfile(upstream)
                    ?: return@mapNotNull null
                val outbounds = (obj["outbounds"] as? JsonArray).orEmpty().map { it.jsonObject }
                    .filter { it["protocol"]?.jsonPrimitive?.content in setOf("vless", "vmess", "trojan", "shadowsocks") }
                val proxy = outbounds.singleOrNull() ?: return@mapNotNull null
                val protocol = proxy.getValue("protocol").jsonPrimitive.content
                val network = proxy["streamSettings"]?.jsonObject?.get("network")?.jsonPrimitive?.content ?: "tcp"
                val settings = proxy["settings"]?.jsonObject ?: return@mapNotNull null
                val peer = ((settings["vnext"] ?: settings["servers"]) as? JsonArray)?.singleOrNull()?.jsonObject ?: return@mapNotNull null
                val host = peer["address"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val port = peer["port"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                val alias = (obj["remarks"] as? JsonPrimitive)?.contentOrNull?.filterNot(Char::isISOControl)?.trim()?.take(120)
                    ?.takeIf(String::isNotBlank)
                // Subscription endpoints can rotate between two GETs. A named logical server
                // must retain identity; resolve its current endpoint/config afresh at profile GET.
                // Ambiguous aliases are still rejected by groupBy/singleOrNull below.
                val identity = alias?.let { "logical-v2|$protocol|$network|$it" }
                    ?: "endpoint-v2|$protocol|$host|$port|$network"
                val key = sha256(identity).take(32)
                val name = alias ?: host
                IssuedProfile(PremiumServerSummary(key, "", "نامشخص", name, protocol.uppercase(), network), obj)
            }
            val grouped = eligible.groupBy { it.summary.id }
            val accepted = grouped.values.mapNotNull { it.singleOrNull() }
            logger.info(PremiumProfileDiagnostics.parsed(configs.size, eligible.size,
                grouped.values.count { it.size > 1 }, accepted.map { it.summary.id }))
            accepted
        } catch (problem: PremiumProblem) { throw problem }
        catch (_: Exception) { unavailable("PASARGUARD_PROFILE_INVALID") }
    }
    private fun unsafeTls(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.any { (k, v) -> (k == "allowInsecure" && (v as? JsonPrimitive)?.booleanOrNull == true) || unsafeTls(v) }
        is JsonArray -> value.any(::unsafeTls)
        else -> false
    }

    private fun normalizeSpdTunProfile(source: JsonObject): JsonObject? {
        val inbounds = source["inbounds"] as? JsonArray ?: return null
        val entries = inbounds.map { it as? JsonObject ?: return null }

        fun protocol(entry: JsonObject): String? =
            (entry["protocol"] as? JsonPrimitive)?.contentOrNull

        // Preserve profiles already accepted by the native-TUN contract.
        if (entries.any { protocol(it) == "tun" }) return source

        // Only convert the known local proxy inputs.
        if (entries.isEmpty() ||
            entries.any { protocol(it) !in setOf("http", "socks") }
        ) return null

        val tunTag = "spd-tun"
        val originalTags = entries.mapNotNull {
            (it["tag"] as? JsonPrimitive)?.contentOrNull
        }.toSet()

        val tun = buildJsonObject {
            put("tag", JsonPrimitive(tunTag))
            put("protocol", JsonPrimitive("tun"))
            put("settings", buildJsonObject {
                put("mtu", JsonPrimitive(1500))
            })
        }

        val result = source.toMutableMap()
        result["inbounds"] = JsonArray(listOf(tun))

        // Keep routing rules that referred to the replaced inputs working.
        val routing = source["routing"] as? JsonObject
        val rules = routing?.get("rules") as? JsonArray

        if (routing != null && rules != null) {
            val updatedRules = rules.map { element ->
                val rule = element as? JsonObject ?: return null
                val tags = rule["inboundTag"] as? JsonArray
                    ?: return@map rule

                val updatedTags = tags.map { tag ->
                    val name = (tag as? JsonPrimitive)?.contentOrNull
                    if (name in originalTags) JsonPrimitive(tunTag) else tag
                }.distinct()

                JsonObject(
                    rule + ("inboundTag" to JsonArray(updatedTags))
                )
            }

            result["routing"] = JsonObject(
                routing + ("rules" to JsonArray(updatedRules))
            )
        }

        return JsonObject(result)
    }
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 255) }
