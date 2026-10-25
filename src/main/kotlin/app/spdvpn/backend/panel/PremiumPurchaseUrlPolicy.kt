package app.spdvpn.backend.panel

import java.net.URI

/** Server-owned destination policy. Add explicitly approved providers here, not in Android. */
internal object PremiumPurchaseUrlPolicy {
    fun normalize(value: String?): String? {
        val url = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals("t.me", ignoreCase = true) ||
            uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        // A direct contact/bot username, never invite, proxy, share, post or executable links.
        val username = uri.rawPath.removePrefix("/")
        if (!username.matches(Regex("[A-Za-z][A-Za-z0-9_]{4,31}")) ||
            username.lowercase(java.util.Locale.ROOT) in setOf("share", "proxy", "socks", "login", "joinchat", "addstickers", "addemoji", "confirmphone")) return null
        return "https://t.me/$username"
    }

    fun publicUrl(settings: PremiumPurchaseSettings): String? =
        if (settings.premiumPurchaseEnabled) normalize(settings.premiumPurchaseUrl) else null
}
