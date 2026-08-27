package app.spdvpn.backend.cache

import app.spdvpn.backend.applicationJson
import app.spdvpn.backend.model.CatalogSnapshot
import app.spdvpn.backend.source.VpnGateCsvParser
import app.spdvpn.backend.vpnGateCsv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileCatalogCacheStoreTest {
    @Test
    fun `persists and reloads the last-known-good snapshot`() = runBlocking {
        val path = createTempDirectory("spdvpn-cache-test").resolve("catalog.json")
        val store = FileCatalogCacheStore(path, applicationJson())
        val snapshot = VpnGateCsvParser(clock = { 42L }).parse(vpnGateCsv()).let { parsed ->
            parsed.copy(
                servers = parsed.servers.map { server ->
                    if (server.hostName == "vpn-tcp") {
                        server.copy(
                            softEtherTcpPort = 995,
                            softEtherTlsServerName = "vpn-tcp.opengw.net",
                        )
                    } else {
                        server
                    }
                },
            )
        }

        store.save(snapshot)
        val loaded = store.load()

        assertNotNull(loaded)
        assertEquals(snapshot, loaded)
        assertEquals(995, loaded.servers.single { it.hostName == "vpn-tcp" }.softEtherTcpPort)
        assertEquals(
            "vpn-tcp.opengw.net",
            loaded.servers.single { it.hostName == "vpn-tcp" }.softEtherTlsServerName,
        )
        assertEquals(1, Files.list(path.parent).use { it.count() })
    }

    @Test
    fun `deserializes an old snapshot without SoftEther metadata`() {
        val json = applicationJson()
        val oldSnapshot = VpnGateCsvParser(clock = { 42L })
            .parse(vpnGateCsv())
            .copy(schemaVersion = 1)
        val oldJson = json.encodeToString(oldSnapshot)
        assertFalse(oldJson.contains("softEtherTcpPort"))
        assertFalse(oldJson.contains("softEtherTlsServerName"))

        val loaded = json.decodeFromString<CatalogSnapshot>(oldJson)

        assertEquals(1, loaded.schemaVersion)
        loaded.servers.forEach { assertNull(it.softEtherTcpPort) }
        loaded.servers.forEach { assertNull(it.softEtherTlsServerName) }
    }
}
