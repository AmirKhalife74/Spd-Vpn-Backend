package app.spdvpn.backend.premium

import kotlinx.coroutines.*
import app.spdvpn.backend.panel.*
import app.spdvpn.backend.applicationJson
import kotlin.time.Duration.Companion.seconds

class PremiumRuntime private constructor(val config: PremiumConfig, val service: PremiumService,
    private val pg: PasarGuard, val panel: PanelService) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try { service.synchronizePending() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Durable operations remain pending for the next sweep; no secrets logged. */ }
            delay(15_000)
        }
    }
    fun close() = pg.close()
    companion object {
        suspend fun open(config: PremiumConfig, pg: PasarGuard = HttpPasarGuard(config),
            passwords: PasswordHasher = PasswordHasher(), clock: () -> Long = System::currentTimeMillis): PremiumRuntime {
            val database = PremiumDatabase(config.databasePath)
            database.initialize(config.defaultQuotaBytes)
            val security = PremiumSecurity(database, config, passwords, clock)
            security.initialize()
            val panel = PanelService(FilePanelContentStore(config.panelContentPath, applicationJson()),
                ClientPresenceTracker(150.seconds, clock), clock = clock)
            return PremiumRuntime(config, PremiumService(database, security, pg, passwords, clock,
                purchaseSettings = panel::premiumPurchaseSettings), pg, panel)
        }
    }
}
