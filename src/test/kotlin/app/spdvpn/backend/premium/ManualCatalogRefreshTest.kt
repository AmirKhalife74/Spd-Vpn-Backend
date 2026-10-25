package app.spdvpn.backend.premium

import app.spdvpn.backend.cache.InMemoryCatalogCacheStore
import app.spdvpn.backend.service.CatalogService
import app.spdvpn.backend.service.RefreshResult
import app.spdvpn.backend.source.VpnGateCsvParser
import app.spdvpn.backend.source.VpnGateSource
import app.spdvpn.backend.vpnGateCsv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class ManualCatalogRefreshTest {
    @Test fun `manual refresh does not queue behind running pipeline and failure preserves valid catalog`() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val service = CatalogService(VpnGateSource {
            calls++
            if (calls == 2) { entered.complete(Unit); release.await() }
            if (calls == 3) error("test upstream outage")
            vpnGateCsv(firstScore = calls.toLong())
        }, VpnGateCsvParser(), InMemoryCatalogCacheStore(), 300.seconds, 86400.seconds)
        service.refresh()
        val scheduled = async { service.refresh() }; entered.await()
        assertNull(service.refreshIfIdle()); assertEquals(2, calls)
        release.complete(Unit); scheduled.await()
        val good = service.currentSnapshot()
        assertIs<RefreshResult.Failure>(service.refreshIfIdle())
        assertEquals(good, service.currentSnapshot())
    }
}
