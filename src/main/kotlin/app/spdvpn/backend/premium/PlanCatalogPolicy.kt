package app.spdvpn.backend.premium

import java.util.Currency

/** Shared by admin validation and the public catalog; does not determine account entitlement. */
internal object PlanCatalogPolicy {
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    const val MAX_PUBLIC_PLANS = 3
    private val languages = setOf("en", "fa")

    fun valid(plan: Plan): Boolean {
        fun translations(values: Map<String, String>, max: Int) = values.keys.all { it in languages } &&
            values.values.all { it.isNotBlank() && it.length <= max }
        val pricing = if (plan.priceMinor == null) plan.currency == null else
            plan.priceMinor in 0..MAX_SAFE_INTEGER && plan.currency?.let { code ->
                code.matches(Regex("[A-Z]{3}")) && runCatching {
                    Currency.getInstance(code).defaultFractionDigits >= 0
                }.getOrDefault(false)
            } == true
        return plan.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) &&
            plan.title.isNotBlank() && plan.title.length <= 120 && plan.description.length <= 500 &&
            (if (plan.lifetime) plan.durationDays == null else plan.durationDays != null && plan.durationDays in 1..3650) &&
            (plan.quotaBytes == null || plan.quotaBytes in 1..MAX_SAFE_INTEGER) &&
            plan.displayOrder in 0..100_000 && pricing &&
            translations(plan.localizedTitles, 120) && translations(plan.localizedDescriptions, 500)
    }

    fun visible(plan: Plan) = plan.enabled && plan.published && valid(plan)
    fun assignable(plan: Plan) = plan.enabled && plan.isAssignable && valid(plan)

    fun requirePublicationCapacity(plans: List<Plan>) {
        if (plans.count(::visible) > MAX_PUBLIC_PLANS)
            throw PremiumProblem(400, "PUBLIC_PLAN_LIMIT", "حداکثر سه پلن فعال در فهرست عمومی مجاز است؛ ابتدا یک پلن را از انتشار خارج کنید")
    }

    fun requireAssignable(plan: Plan): Plan = plan.takeIf(::assignable)
        ?: throw PremiumProblem(400, "PLAN_NOT_ASSIGNABLE", "این پلن غیرفعال است یا اجازهٔ تخصیص دستی ندارد")
}
