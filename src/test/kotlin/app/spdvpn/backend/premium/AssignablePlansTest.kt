package app.spdvpn.backend.premium

import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class AssignablePlansTest {
    private fun trial(id: String = "TRIAL_10GB") = Plan(id, "Trial 10 GB", durationDays = 7,
        quotaBytes = 10_000_000_000L, published = false, isAssignable = true,
        localizedTitles = mapOf("fa" to "آزمایشی ۱۰ گیگابایت", "en" to "Trial 10 GB"))

    @Test fun `legacy plan documents remain assignable without rewriting schema or existing definitions`() = runBlocking {
        val legacy = Json.decodeFromString<Plan>("""{"id":"MONTH_1","title":"Legacy","durationDays":30}""")
        assertTrue(legacy.enabled); assertTrue(legacy.published); assertTrue(legacy.isAssignable)
        val f = PremiumFixture(); f.open()
        assertEquals(3, f.service.plans().size)
        val private = f.service.createPlan(trial(), "test-admin")
        f.runtime.close(); f.open()
        assertEquals(private, f.service.plans().single { it.id == private.id })
        assertEquals(3, f.service.product().plans.size)
        assertEquals(1, f.service.database.tx { it.rows("PRAGMA user_version") { r -> r.getInt(1) }.single() })
    }

    @Test fun `private trial can be assigned and synchronized but never appears in public product`() = runBlocking {
        val f = PremiumFixture(); f.open(); val plan = f.service.createPlan(trial(), "test-admin")
        assertFalse(f.service.product().plans.any { it.id == plan.id })
        val account = f.create(plan = plan.id)
        assertEquals(f.now + 7 * 86_400_000L, account.requested.expiresAtEpochMillis)
        assertEquals(10_000_000_000L, account.requested.quotaBytes)
        f.service.synchronizePending()
        val confirmed = f.service.user(account.id)
        assertEquals(SyncState.SYNCED, confirmed.syncState)
        val remote = assertNotNull(confirmed.confirmed)
        assertEquals(plan.quotaBytes, remote.quotaBytes)
        assertTrue(f.service.me(account.id).premiumVpnConfigured)
        // Even an upstream status still marked active cannot bypass the observed traffic ceiling.
        f.pg.users[remote.id] = remote.copy(usedBytes = plan.quotaBytes!!, status = "active")
        assertFalse(f.service.me(account.id).premiumVpnConfigured)
        assertEquals("PREMIUM_NOT_ACTIVE", assertFailsWith<PremiumProblem> { f.service.catalog(account.id) }.code)
    }

    @Test fun `private renewal and plan change retain existing usage and require explicit admin action`() = runBlocking {
        val f = PremiumFixture(); f.open(); val account = f.create(); f.service.synchronizePending()
        val trial = f.service.createPlan(trial(), "test-admin")
        val id = f.service.user(account.id).pasarGuardUserId!!
        f.pg.users[id] = f.pg.users.getValue(id).copy(usedBytes = 4_000_000_000L)
        f.service.change(account.id, ChangeSubscriptionRequest("CHANGE_PLAN", trial.id), UUID.randomUUID().toString(), "test-admin")
        f.service.synchronizePending()
        assertEquals(trial.quotaBytes, f.pg.users.getValue(id).quotaBytes)
        assertEquals(4_000_000_000L, f.pg.users.getValue(id).usedBytes)
        f.service.change(account.id, ChangeSubscriptionRequest("RENEW", trial.id), UUID.randomUUID().toString(), "test-admin")
        f.service.synchronizePending()
        assertEquals(f.now + 14 * 86_400_000L, f.service.user(account.id).requested.expiresAtEpochMillis)
        assertEquals(4_000_000_000L, f.pg.users.getValue(id).usedBytes)
    }

    @Test fun `publication limit is enforced on create publish and reactivation without partial writes`() = runBlocking {
        val f = PremiumFixture(); f.open()
        val tooMany = trial().copy(published = true)
        assertEquals("PUBLIC_PLAN_LIMIT", assertFailsWith<PremiumProblem> { f.service.createPlan(tooMany, "test-admin") }.code)
        assertEquals(3, f.service.plans().size)
        val hidden = f.service.createPlan(trial(), "test-admin")
        assertEquals("PUBLIC_PLAN_LIMIT", assertFailsWith<PremiumProblem> { f.service.updatePlan(hidden.copy(published = true), "test-admin") }.code)
        assertFalse(f.service.plans().single { it.id == hidden.id }.published)
        val inactive = f.service.createPlan(trial("INACTIVE").copy(published = true, enabled = false), "test-admin")
        assertEquals("PUBLIC_PLAN_LIMIT", assertFailsWith<PremiumProblem> { f.service.updatePlan(inactive.copy(enabled = true), "test-admin") }.code)
        val former = f.service.plans().first { it.id == "MONTH_1" }
        f.service.updatePlan(former.copy(published = false), "test-admin")
        f.service.updatePlan(hidden.copy(published = true, isAssignable = false), "test-admin")
        assertEquals(3, f.service.product().plans.size) // Public availability does not imply manual eligibility.
    }

    @Test fun `concurrent publications cannot exceed three visible plans`() = runBlocking {
        val f = PremiumFixture(); f.open()
        val former = f.service.plans().first()
        f.service.updatePlan(former.copy(published = false), "test-admin")
        val hidden = (1..2).map { f.service.createPlan(trial("PRIVATE_$it"), "test-admin") }
        val outcomes = coroutineScope { hidden.map { p -> async {
            try { f.service.updatePlan(p.copy(published = true), "test-admin"); true }
            catch (error: PremiumProblem) { assertEquals("PUBLIC_PLAN_LIMIT", error.code); false }
        } }.awaitAll() }
        assertEquals(1, outcomes.count { it }); assertEquals(3, f.service.product().plans.size)
    }

    @Test fun `inactive and nonassignable plans are rejected for create change renew and repair link`() = runBlocking {
        val f = PremiumFixture(); f.open(); val account = f.create(); f.service.synchronizePending()
        val link = f.create("link-test", pgId = 404); f.service.synchronizePending()
        for (plan in listOf(trial("DISABLED").copy(enabled = false), trial("NO_ASSIGN").copy(isAssignable = false))) {
            f.service.createPlan(plan, "test-admin")
            assertEquals("PLAN_NOT_ASSIGNABLE", assertFailsWith<PremiumProblem> { f.create("new-${plan.id.lowercase()}", plan.id) }.code)
            for (action in listOf("CHANGE_PLAN", "RENEW")) {
                assertEquals("PLAN_NOT_ASSIGNABLE", assertFailsWith<PremiumProblem> {
                    f.service.change(account.id, ChangeSubscriptionRequest(action, plan.id), UUID.randomUUID().toString(), "test-admin")
                }.code)
            }
            assertEquals("PLAN_NOT_ASSIGNABLE", assertFailsWith<PremiumProblem> {
                f.service.repairLink(link.id, LinkRequest(77, plan.id, true), UUID.randomUUID().toString(), "test-admin")
            }.code)
        }
        assertEquals("MONTH_1", f.service.user(account.id).planId)
    }

    @Test fun `existing account remains entitled after its plan is unpublished deactivated and unassignable`() = runBlocking {
        val f = PremiumFixture(); f.open(); val plan = f.service.createPlan(trial(), "test-admin")
        val account = f.create(plan = plan.id); f.service.synchronizePending()
        val before = f.service.user(account.id)
        f.service.updatePlan(plan.copy(published = false, enabled = false, isAssignable = false), "test-admin")
        assertEquals(before, f.service.user(account.id))
        assertTrue(f.service.me(account.id).premiumVpnConfigured)
        assertEquals(1, f.service.catalog(account.id).servers.size)
    }

    @Test fun `stable custom ids pricing localization and public sorting are retained`() = runBlocking {
        val f = PremiumFixture(); f.open()
        for (p in f.service.plans()) f.service.updatePlan(p.copy(published = false), "test-admin")
        val first = f.service.createPlan(trial("CUSTOM_A").copy(published = true, displayOrder = 4, priceMinor = 100, currency = "USD"), "test-admin")
        f.service.createPlan(trial("CUSTOM_B").copy(published = true, displayOrder = 2, lifetime = true, durationDays = null), "test-admin")
        f.service.updatePlan(first.copy(title = "Renamed", priceMinor = 200), "test-admin")
        val public = f.service.product().plans
        assertEquals(listOf("CUSTOM_B", "CUSTOM_A"), public.map { it.id })
        assertEquals("Trial 10 GB", public.last().localizedTitles["en"])
        assertEquals(200, public.last().priceMinor)
        assertEquals(409, assertFailsWith<PremiumProblem> { f.service.createPlan(first, "test-admin") }.status)
        assertEquals(404, assertFailsWith<PremiumProblem> { f.service.updatePlan(trial("MISSING"), "test-admin") }.status)
        for (bad in listOf(trial("../bad"), trial().copy(durationDays = 0), trial().copy(quotaBytes = 0),
            trial().copy(lifetime = true), trial().copy(priceMinor = 1, currency = null))) {
            assertEquals("BAD_REQUEST", assertFailsWith<PremiumProblem> { f.service.createPlan(bad, "test-admin") }.code)
        }
    }

    @Test fun `externally corrupted overcapacity catalog fails closed rather than silently truncating`() = runBlocking {
        val f = PremiumFixture(); f.open()
        val p = trial().copy(published = true)
        f.service.database.tx { it.exec("INSERT INTO plans(id,document) VALUES (?,?)", p.id, Json.encodeToString(p)) }
        assertEquals("PLAN_CATALOG_INVALID", assertFailsWith<PremiumProblem> { f.service.product() }.code)
        f.service.updatePlan(p.copy(published = false), "test-admin")
        assertEquals(3, f.service.product().plans.size)
    }
}
