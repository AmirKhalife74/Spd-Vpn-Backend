package app.spdvpn.backend.premium

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class ProductPlansTest {
    @Test fun `legacy documents retain ids and ordering with nullable unconfigured pricing`() {
        val json = Json { ignoreUnknownKeys = true }
        for ((index, id) in listOf("MONTH_1", "MONTH_3", "LIFETIME").withIndex()) {
            val timed = if (id == "LIFETIME") "\"lifetime\":true" else "\"durationDays\":30"
            val p = json.decodeFromString<Plan>("""{"id":"$id","title":"Legacy",$timed}""")
            assertEquals(id, p.id); assertEquals(index, p.displayOrder)
            assertNull(p.priceMinor); assertNull(p.currency); assertTrue(p.published)
            assertTrue(PlanCatalogPolicy.valid(p))
        }
    }

    @Test fun `public product returns only published enabled valid plans in configured order`() = runBlocking {
        val f = PremiumFixture(); f.open()
        val original = f.service.plans().associateBy { it.id }
        f.service.updatePlan(original.getValue("MONTH_1").copy(displayOrder = 20, priceMinor = 1299,
            currency = "USD", localizedTitles = mapOf("en" to "Monthly", "fa" to "ماهانه")), "test-admin")
        f.service.updatePlan(original.getValue("MONTH_3").copy(displayOrder = 10), "test-admin")
        f.service.updatePlan(original.getValue("LIFETIME").copy(published = false), "test-admin")
        val public = f.service.product()
        assertEquals(listOf("MONTH_3", "MONTH_1"), public.plans.map { it.id })
        assertEquals(1299, public.plans.last().priceMinor)
        assertEquals("Monthly", public.plans.last().localizedTitles["en"])
        assertEquals(3, f.service.plans().size) // Admin still sees drafts/inactive definitions.
        f.service.updatePlan(original.getValue("MONTH_3").copy(enabled = false), "test-admin")
        assertEquals(listOf("MONTH_1"), f.service.product().plans.map { it.id })
        assertEquals(0, f.pg.profileCalls) // Public plans do not fetch private subscriptions.
    }

    @Test fun `empty public list is real empty data and plan metadata survives reopening`() = runBlocking {
        val f = PremiumFixture(); f.open()
        for (p in f.service.plans()) f.service.updatePlan(p.copy(published = false), "test-admin")
        assertTrue(f.service.product().plans.isEmpty())
        f.runtime.close(); f.open()
        assertTrue(f.service.product().plans.isEmpty()) // INSERT OR IGNORE does not republish.
        assertTrue(f.service.plans().all { !it.published })
    }

    @Test fun `admin rejects invalid paired pricing currency duration translations and ordering`() = runBlocking {
        val f = PremiumFixture(); f.open(); val p = f.service.plans().first()
        val invalid = listOf(p.copy(priceMinor = -1, currency = "USD"),
            p.copy(priceMinor = 100), p.copy(currency = "USD"),
            p.copy(priceMinor = 100, currency = "usd"), p.copy(priceMinor = 100, currency = "ZZZ"),
            p.copy(priceMinor = 100, currency = "XXX"),
            p.copy(priceMinor = 9_007_199_254_740_992L, currency = "USD"),
            p.copy(displayOrder = -1), p.copy(displayOrder = 100_001), p.copy(durationDays = 0),
            p.copy(localizedTitles = mapOf("en" to " ")), p.copy(localizedTitles = mapOf("de" to "Name")),
            p.copy(localizedDescriptions = mapOf("fa" to "x".repeat(501))))
        for (badPlan in invalid) {
            assertEquals("BAD_REQUEST", assertFailsWith<PremiumProblem> {
                f.service.updatePlan(badPlan, "test-admin")
            }.code)
        }
        assertTrue(PlanCatalogPolicy.valid(p.copy(priceMinor = 0, currency = "USD")))
        assertTrue(PlanCatalogPolicy.valid(p.copy(priceMinor = 100, currency = "IRR")))
        assertTrue(PlanCatalogPolicy.valid(p.copy(priceMinor = 100, currency = "KWD")))
    }

    @Test fun `unpublishing repricing or disabling definition never changes an existing entitlement`() = runBlocking {
        val f = PremiumFixture(); f.open(); val a = f.create(); f.service.synchronizePending()
        val before = f.service.user(a.id)
        val plan = f.service.plans().first { it.id == a.planId }
        f.service.updatePlan(plan.copy(title = "New title", description = "New description",
            priceMinor = 2500, currency = "USD", displayOrder = 10, published = false, enabled = false), "test-admin")
        val after = f.service.user(a.id)
        assertEquals(before.requested, after.requested)
        assertEquals(before.confirmed, after.confirmed)
        assertEquals(before.planId, after.planId)
        assertTrue(f.service.me(a.id).premiumVpnConfigured)
        assertEquals(a.planId, f.service.me(a.id).planId)
        assertEquals(1, f.service.catalog(a.id).servers.size)
        f.service.updatePlan(plan.copy(priceMinor = 3000, currency = "USD"), "test-admin")
        assertEquals(plan.id, f.service.product().plans.first { it.id == plan.id }.id)
        assertEquals(before.requested, f.service.user(a.id).requested)
    }
}
