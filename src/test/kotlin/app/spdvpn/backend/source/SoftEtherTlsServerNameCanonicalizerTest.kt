package app.spdvpn.backend.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoftEtherTlsServerNameCanonicalizerTest {
    @Test
    fun `canonicalizes supported VPN Gate TLS identities`() {
        assertEquals(
            "public-vpn-39.opengw.net",
            SoftEtherTlsServerNameCanonicalizer.canonicalize("public-vpn-39"),
        )
        assertEquals(
            "vpn459624604.opengw.net",
            SoftEtherTlsServerNameCanonicalizer.canonicalize("vpn459624604"),
        )
        assertEquals(
            "public-vpn-39.opengw.net",
            SoftEtherTlsServerNameCanonicalizer.canonicalize("public-vpn-39.opengw.net"),
        )
        assertEquals(
            "public-vpn-39.opengw.net",
            SoftEtherTlsServerNameCanonicalizer.canonicalize("  PUBLIC-VPN-39  "),
        )
        assertEquals(
            "public-vpn-39.opengw.net",
            SoftEtherTlsServerNameCanonicalizer.canonicalize("public-vpn-39.opengw.net."),
        )
    }

    @Test
    fun `rejects values that are not trusted VPN Gate TLS identities`() {
        assertNull(SoftEtherTlsServerNameCanonicalizer.canonicalize("219.100.37.3"))
        assertNull(SoftEtherTlsServerNameCanonicalizer.canonicalize(" "))
        assertNull(SoftEtherTlsServerNameCanonicalizer.canonicalize("-public-vpn-39"))
        assertNull(SoftEtherTlsServerNameCanonicalizer.canonicalize("public_vpn_39"))
        assertNull(SoftEtherTlsServerNameCanonicalizer.canonicalize("vpn.example.com"))
        assertNull(SoftEtherTlsServerNameCanonicalizer.canonicalize("nested.public-vpn-39.opengw.net"))
        assertNull(SoftEtherTlsServerNameCanonicalizer.canonicalize("public-vpn-39..."))
    }
}
