package app.spdvpn.backend.premium

import app.spdvpn.backend.api.ApiBadRequestException
import app.spdvpn.backend.applicationJson
import app.spdvpn.backend.panel.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PremiumPurchaseTest {
    @Test fun `contact URL policy accepts only approved HTTPS direct contacts`() {
        assertEquals("https://t.me/Example_admin", PremiumPurchaseUrlPolicy.normalize("  HTTPS://T.ME:443/Example_admin  "))
        assertEquals("https://t.me/example_bot", PremiumPurchaseUrlPolicy.normalize("https://t.me/example_bot"))
        for (url in listOf("http://t.me/example_admin", "javascript:alert(1)", "https://other.example/contact",
            "https://t.me.evil.test/example_admin", "https://t.me@evil.test/example_admin", "https://user@t.me/example_admin",
            "https://t.me:8443/example_admin", "https://t.me/+invite", "https://t.me/share", "https://t.me/proxy",
            "https://t.me/example_admin/123", "https://t.me/example_admin?start=secret", "https://t.me/example_admin#x",
            "https://t.me/%65xample_admin", "https://t.me/", "", "https://t.me/a\nbbbb")) {
            assertNull(PremiumPurchaseUrlPolicy.normalize(url))
        }
    }

    @Test fun `legacy content and product default to disabled and absent contact`() = runBlocking {
        val legacy = applicationJson().decodeFromString<PanelContentSnapshot>("""{"schemaVersion":1,"releases":[],"announcements":[]}""")
        assertEquals(PremiumPurchaseSettings(), legacy.premiumPurchase)
        val f = PremiumFixture(); f.open()
        assertFalse(f.service.product().premiumPurchaseEnabled)
        assertNull(f.service.product().premiumPurchaseUrl)
        assertNull(PremiumPurchaseUrlPolicy.publicUrl(PremiumPurchaseSettings("https://evil.test/", true)))
    }

    @Test fun `admin contact persists across runtime restart without changing existing panel content`() = runBlocking {
        val f = PremiumFixture(); f.open()
        val announcement = f.runtime.panel.createAnnouncement(AnnouncementDraft(title = "Notice", message = "Keep me"))
        val settings = PremiumPurchaseSettings("https://t.me/example_admin", true)
        f.runtime.panel.updatePremiumPurchaseSettings(settings)
        assertEquals(settings.premiumPurchaseUrl, f.service.product().premiumPurchaseUrl)
        f.runtime.close(); f.open()
        assertEquals(settings, f.runtime.panel.premiumPurchaseSettings())
        assertTrue(f.service.product().premiumPurchaseEnabled)
        assertEquals(settings.premiumPurchaseUrl, f.service.product().premiumPurchaseUrl)
        assertEquals(announcement, f.runtime.panel.announcements().single())
        f.runtime.panel.updatePremiumPurchaseSettings(settings.copy(premiumPurchaseEnabled = false))
        assertNull(f.service.product().premiumPurchaseUrl)
        assertFalse(f.service.product().premiumPurchaseEnabled)
        assertEquals(settings.premiumPurchaseUrl, f.runtime.panel.premiumPurchaseSettings().premiumPurchaseUrl)
        f.runtime.close(); f.open()
        assertFalse(f.service.product().premiumPurchaseEnabled)
    }

    @Test fun `bad settings never replace last valid settings and enabled requires destination`() = runBlocking {
        val f = PremiumFixture(); f.open()
        val valid = PremiumPurchaseSettings("https://t.me/example_admin", true)
        f.runtime.panel.updatePremiumPurchaseSettings(valid)
        for (bad in listOf(PremiumPurchaseSettings(null, true), PremiumPurchaseSettings(" ", true),
            PremiumPurchaseSettings("https://evil.test/", false), PremiumPurchaseSettings("https://evil.test/", true))) {
            assertFailsWith<ApiBadRequestException> { f.runtime.panel.updatePremiumPurchaseSettings(bad) }
            assertEquals(valid, f.runtime.panel.premiumPurchaseSettings())
        }
        f.runtime.panel.updatePremiumPurchaseSettings(PremiumPurchaseSettings())
        assertNull(f.service.product().premiumPurchaseUrl)
    }

    @Test fun `contact edits and public reads do not change plans subscription or upstream credentials`() = runBlocking {
        val f = PremiumFixture(); f.open(); val user = f.create(); f.service.synchronizePending()
        val before = f.service.user(user.id)
        val plans = f.service.plans()
        val ops = f.service.operations(user.id)
        f.runtime.panel.updatePremiumPurchaseSettings(PremiumPurchaseSettings("https://t.me/example_admin", true))
        repeat(3) { f.service.product() }
        f.runtime.panel.updatePremiumPurchaseSettings(PremiumPurchaseSettings("https://t.me/another_admin", true))
        assertEquals("https://t.me/another_admin", f.service.product().premiumPurchaseUrl)
        assertEquals(before, f.service.user(user.id)); assertEquals(ops, f.service.operations(user.id))
        assertEquals(plans, f.service.plans()); assertEquals(0, f.pg.profileCalls)
        assertTrue(f.service.me(user.id).premiumVpnConfigured)
    }
}
