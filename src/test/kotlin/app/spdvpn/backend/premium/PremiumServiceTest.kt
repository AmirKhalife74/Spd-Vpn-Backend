package app.spdvpn.backend.premium

import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

internal class TestPasarGuard : PasarGuard {
    override var configured = true
    val users = mutableMapOf<Long, RemoteUser>()
    var creates = 0; var updates = 0; var offline = false
    var failAfterCreate = false; var cancelAfterCreate = false; var failAfterUpdate = false
    var profileCalls = 0
    var profileOverride: List<IssuedProfile>? = null
    private fun available() { if (offline) unavailable() }
    override suspend fun byId(id: Long): RemoteUser? { available(); return users[id] }
    override suspend fun byUsername(username: String): RemoteUser? { available(); return users.values.firstOrNull { it.username == username } }
    override suspend fun create(username: String, marker: String, target: SyncTarget): RemoteUser {
        available(); creates++
        val user = RemoteUser((users.keys.maxOrNull() ?: 0) + 1, username, "active", target.expiresAtEpochMillis, target.quotaBytes, 0, marker)
        users[user.id] = user
        if (cancelAfterCreate) { cancelAfterCreate = false; throw CancellationException("test interruption") }
        if (failAfterCreate) { failAfterCreate = false; unavailable() }
        return user
    }
    override suspend fun update(id: Long, target: SyncTarget): RemoteUser {
        available(); updates++
        val user = users.getValue(id).copy(status = if (target.disabled) "disabled" else "active",
            expiresAtEpochMillis = target.expiresAtEpochMillis, quotaBytes = target.quotaBytes)
        users[id] = user
        if (failAfterUpdate) { failAfterUpdate = false; unavailable() }
        return user
    }
    override suspend fun profiles(id: Long): List<IssuedProfile> {
        available()
        profileCalls++
        profileOverride?.let { return it }
        val key = sha256("test-server-for-$id").take(32)
        return listOf(IssuedProfile(PremiumServerSummary(key, "", "نامشخص", "test-only", "VLESS", "tcp"),
            buildJsonObject { put("testOwner", id) }))
    }
}
internal class PremiumFixture {
    val directory = Files.createTempDirectory("spd-premium-test-")
    val config = PremiumConfig(databasePath = directory.resolve("accounts.sqlite"),
        adminOrigin = "http://localhost", insecureLocalCookies = true)
    val pg = TestPasarGuard()
    var now = 1_800_000_000_000L
    lateinit var runtime: PremiumRuntime
    val service get() = runtime.service
    suspend fun open() { runtime = PremiumRuntime.open(config, pg, PasswordHasher(8192, 1)) { now } }
    suspend fun create(name: String = "sample-user", plan: String = "MONTH_1", pgId: Long? = null,
        key: String = UUID.randomUUID().toString()): AccountView = service.create(
        CreateAccountRequest(name, "test-password-12345", plan, pgId, pgId != null), key, "test-admin")
}

class PremiumServiceTest {
    @Test fun `creation is pending until confirmed and retry after applied create does not duplicate`() = runBlocking {
        val f = PremiumFixture(); f.open()
        val key = UUID.randomUUID().toString()
        val a = f.create(key = key)
        assertEquals(SyncState.PENDING, a.syncState)
        assertEquals(409, assertFailsWith<PremiumProblem> { f.service.refreshUser(a.id, "test-admin") }.status)
        assertEquals(f.now + 30 * 86_400_000L, a.requested.expiresAtEpochMillis)
        assertEquals(a.id, f.create(key = key).id)
        f.pg.failAfterCreate = true
        f.service.synchronizePending()
        val synced = f.service.user(a.id)
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals(1, f.pg.creates)
        assertEquals(synced.requested.expiresAtEpochMillis, synced.confirmed?.expiresAtEpochMillis)
        assertFalse(f.service.audit(0, 100).items.toString().contains("test-password"))
    }
    @Test fun `renewal is absolute and timeout after applied update never adds time twice`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create(); f.service.synchronizePending()
        val key = UUID.randomUUID().toString()
        val renewed = f.service.change(a.id, ChangeSubscriptionRequest("RENEW"), key, "test-admin")
        assertEquals(f.now + 60 * 86_400_000L, renewed.requested.expiresAtEpochMillis)
        assertEquals(renewed, f.service.change(a.id, ChangeSubscriptionRequest("RENEW"), key, "test-admin"))
        f.pg.failAfterUpdate = true; f.service.synchronizePending()
        assertEquals(SyncState.FAILED, f.service.user(a.id).syncState)
        f.service.retry(a.id, "test-admin"); f.service.synchronizePending()
        assertEquals(1, f.pg.updates)
        assertEquals(renewed.requested.expiresAtEpochMillis, f.service.user(a.id).confirmed?.expiresAtEpochMillis)
        assertEquals(SyncState.SYNCED, f.service.user(a.id).syncState)
    }
    @Test fun `process interruption after remote create resumes from persisted operation`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create()
        f.pg.cancelAfterCreate = true
        assertFailsWith<CancellationException> { f.service.synchronizePending() }
        assertEquals(SyncState.PENDING, f.service.user(a.id).syncState)
        f.open(); f.service.synchronizePending()
        assertEquals(1, f.pg.creates)
        assertEquals(SyncState.SYNCED, f.service.user(a.id).syncState)
    }
    @Test fun `lifetime has finite independent quota and converting to timed requires confirmation`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create(plan = "LIFETIME"); f.service.synchronizePending()
        assertNull(a.requested.expiresAtEpochMillis); assertNotNull(a.requested.quotaBytes)
        assertFailsWith<PremiumProblem> { f.service.change(a.id, ChangeSubscriptionRequest("RENEW"), UUID.randomUUID().toString(), "test-admin") }
        assertFailsWith<PremiumProblem> { f.service.change(a.id, ChangeSubscriptionRequest("CHANGE_PLAN", "MONTH_1"), UUID.randomUUID().toString(), "test-admin") }
        val changed = f.service.change(a.id, ChangeSubscriptionRequest("CHANGE_PLAN", "MONTH_1", confirmLifetimeToTimed = true), UUID.randomUUID().toString(), "test-admin")
        assertEquals(f.now + 30 * 86_400_000L, changed.requested.expiresAtEpochMillis)
    }
    @Test fun `disabled priority reenable preserves expiry and expired renewal starts at current server time`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create(); f.service.synchronizePending()
        suspend fun disabled(value: Boolean) {
            f.service.change(a.id, ChangeSubscriptionRequest("SET_DISABLED", disabled = value), UUID.randomUUID().toString(), "test-admin")
            f.service.synchronizePending()
        }
        disabled(true); f.now = a.requested.expiresAtEpochMillis!!
        val tokens = f.service.login(CredentialsRequest(a.username, "test-password-12345"), "test-ip")
        assertEquals(AccountStatus.DISABLED, tokens.profile.status)
        assertFailsWith<PremiumProblem> { f.service.catalog(a.id) }
        disabled(false)
        assertEquals(a.requested.expiresAtEpochMillis, f.service.user(a.id).requested.expiresAtEpochMillis)
        assertEquals(AccountStatus.EXPIRED, f.service.me(a.id).status)
        f.now += 5_000
        val renewed = f.service.change(a.id, ChangeSubscriptionRequest("RENEW", "MONTH_3"), UUID.randomUUID().toString(), "test-admin")
        assertEquals(f.now + 90 * 86_400_000L, renewed.requested.expiresAtEpochMillis)
    }
    @Test fun `plans are configurable without silently changing existing accounts`() = runBlocking {
        val f = PremiumFixture(); f.open(); val old = f.create()
        val plan = f.service.plans().first { it.id == "MONTH_1" }.copy(durationDays = 31, quotaBytes = null)
        f.service.updatePlan(plan, "test-admin")
        val fresh = f.create("another-user")
        assertEquals(f.now + 31 * 86_400_000L, fresh.requested.expiresAtEpochMillis)
        assertNull(fresh.requested.quotaBytes)
        assertEquals(old.requested, f.service.user(old.id).requested)
    }
    @Test fun `link verifies existing identity adopts actual entitlement and disallows double ownership`() = runBlocking {
        val f = PremiumFixture(); f.open()
        f.pg.users[42] = RemoteUser(42, "existing_vpn", "active", f.now + 9_000_000, 123_456, 100)
        val a = f.create(pgId = 42); f.service.synchronizePending()
        val linked = f.service.user(a.id)
        assertEquals(SyncState.SYNCED, linked.syncState)
        assertEquals(f.pg.users[42]?.expiresAtEpochMillis, linked.requested.expiresAtEpochMillis)
        assertEquals(0, f.pg.creates); assertEquals(0, f.pg.updates)
        assertFailsWith<PremiumProblem> { f.create("different-user", pgId = 42) }
        Unit
    }
    @Test fun `temporary outage preserves entitlement and never authorizes cached private profiles`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create(); f.service.synchronizePending()
        val before = f.service.user(a.id)
        f.pg.offline = true
        assertEquals(503, assertFailsWith<PremiumProblem> { f.service.me(a.id) }.status)
        assertEquals(503, assertFailsWith<PremiumProblem> { f.service.catalog(a.id) }.status)
        assertEquals(before, f.service.user(a.id))
    }
    @Test fun `failed unconfirmed link can be repaired explicitly without changing remote users`() = runBlocking {
        val f = PremiumFixture(); f.open()
        val a = f.create(pgId = 404)
        f.service.synchronizePending()
        assertEquals(SyncState.FAILED, f.service.user(a.id).syncState)
        f.pg.users[77] = RemoteUser(77, "existing_lifetime", "active", null, 987654, 12)
        f.service.repairLink(a.id, LinkRequest(77, "LIFETIME", true), "repair-link-123", "test-admin")
        f.service.synchronizePending()
        assertEquals(77L, f.service.user(a.id).pasarGuardUserId)
        assertEquals(SyncState.SYNCED, f.service.user(a.id).syncState)
        assertEquals(0, f.pg.creates); assertEquals(0, f.pg.updates)
        assertFailsWith<PremiumProblem> { f.service.repairLink(a.id, LinkRequest(78, "LIFETIME", true), "repair-link-456", "test-admin") }
        Unit
    }
    @Test fun `quota exhaustion is denied without mislabeling it as time expiration`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create(plan = "LIFETIME"); f.service.synchronizePending()
        val pgId = f.service.user(a.id).pasarGuardUserId!!
        f.pg.users[pgId] = f.pg.users.getValue(pgId).let { it.copy(status = "limited", usedBytes = it.quotaBytes!!) }
        val profile = f.service.me(a.id)
        assertEquals(AccountStatus.ACTIVE, profile.status); assertFalse(profile.premiumVpnConfigured)
        assertEquals(403, assertFailsWith<PremiumProblem> { f.service.catalog(a.id) }.status)
    }
    @Test fun `remote drift remains separate until administrator requests reconciliation`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create(); f.service.synchronizePending()
        val pgId = f.service.user(a.id).pasarGuardUserId!!
        f.pg.users[pgId] = f.pg.users.getValue(pgId).copy(quotaBytes = 99_999)
        f.service.refreshUser(a.id, "test-admin")
        val drift = f.service.user(a.id)
        assertEquals(SyncState.FAILED, drift.syncState)
        assertNotEquals(drift.requested.quotaBytes, drift.confirmed?.quotaBytes)
        assertEquals(0, f.pg.updates)
        f.service.retry(a.id, "test-admin"); f.service.synchronizePending()
        assertEquals(SyncState.SYNCED, f.service.user(a.id).syncState)
        assertEquals(drift.requested.quotaBytes, f.pg.users[pgId]?.quotaBytes)
    }
    @Test fun `renewal checks fresh remote state and never overwrites an unseen external extension`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create(); f.service.synchronizePending()
        val pgId = f.service.user(a.id).pasarGuardUserId!!
        val extended = f.pg.users.getValue(pgId).let { it.copy(expiresAtEpochMillis = it.expiresAtEpochMillis!! + 86_400_000) }
        f.pg.users[pgId] = extended
        assertEquals(409, assertFailsWith<PremiumProblem> {
            f.service.change(a.id, ChangeSubscriptionRequest("RENEW"), "fresh-renew-123", "test-admin")
        }.status)
        assertEquals(0, f.pg.updates)
        assertEquals(a.requested.expiresAtEpochMillis, f.service.user(a.id).requested.expiresAtEpochMillis)
        assertEquals(extended.expiresAtEpochMillis, f.service.user(a.id).confirmed?.expiresAtEpochMillis)
    }
    @Test fun `passwords and tokens are hashed with separate roles and user refresh rotates`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create()
        val adminPassword = "test-admin-password-123"
        f.service.security.bootstrap("admin-user", adminPassword)
        assertFailsWith<PremiumProblem> { f.service.security.bootstrap("second-admin", adminPassword) }
        val admin = f.service.security.loginAdmin(CredentialsRequest("admin-user", adminPassword), "local-test")
        val session = f.service.login(CredentialsRequest(a.username, "test-password-12345"), "other-test")
        assertFailsWith<PremiumProblem> { f.service.security.owner(admin.token) }
        assertFailsWith<PremiumProblem> { f.service.security.admin(session.accessToken) }
        val rotated = f.service.refreshSession(session.refreshToken)
        assertFailsWith<PremiumProblem> { f.service.refreshSession(session.refreshToken) }
        assertFailsWith<PremiumProblem> { f.service.security.owner(session.accessToken) }
        f.service.security.changePassword(a.id, "new-test-password-123", "admin-user")
        assertFailsWith<PremiumProblem> { f.service.security.owner(rotated.accessToken) }
        f.service.database.tx { c ->
            val hash = c.rows("SELECT password_hash FROM accounts WHERE id=?", a.id) { it.getString(1) }.single()
            assertTrue(hash.startsWith("argon2id-v19:")); assertFalse(hash.contains("test-password"))
            val stored = c.rows("SELECT token_hash FROM admin_sessions") { it.getString(1) }.single()
            assertNotEquals(admin.token, stored)
        }
        assertTrue(f.service.audit(0, 100).items.any { it.action == "APP_PASSWORD_CHANGED" })
    }
}
