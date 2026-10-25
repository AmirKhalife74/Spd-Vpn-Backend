package app.spdvpn.backend.premium

/** No account IDs, upstream names/endpoints, credentials, JSON or exception messages. */
internal object PremiumProfileDiagnostics {
    private val idPattern = Regex("[a-f0-9]{32}")
    private fun safeId(value: String) = value.takeIf(idPattern::matches) ?: "invalid"
    private fun ids(values: List<String>) = "count=${values.size} serverIds=${values.take(128).joinToString(",", transform = ::safeId)} idsTruncated=${values.size > 128}"

    fun catalog(values: List<String>) = "premiumProfile event=CATALOG code=OK ${ids(values)}"
    fun lookup(serverId: String, found: Boolean, available: Int) =
        "premiumProfile event=LOOKUP code=${if (found) "MATCH" else "NOT_FOUND"} serverId=${safeId(serverId)} availableCount=$available"
    fun parsed(upstream: Int, eligible: Int, ambiguous: Int, accepted: List<String>) =
        "premiumProfile event=PARSED identityPolicy=logical-v2 upstreamCount=$upstream eligibleCount=$eligible ambiguousIds=$ambiguous ${ids(accepted)}"
}
