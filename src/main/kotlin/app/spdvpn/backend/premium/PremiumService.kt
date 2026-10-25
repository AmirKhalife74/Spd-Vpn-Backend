package app.spdvpn.backend.premium

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import app.spdvpn.backend.panel.PremiumPurchaseSettings
import app.spdvpn.backend.panel.PremiumPurchaseUrlPolicy

class PremiumService(val database: PremiumDatabase, val security: PremiumSecurity, private val pg: PasarGuard,
    private val passwords: PasswordHasher, private val clock: () -> Long = System::currentTimeMillis,
    private val purchaseSettings: suspend () -> PremiumPurchaseSettings = { PremiumPurchaseSettings() }) {
    private val json = Json { encodeDefaults = true }
    private val syncMutex = Mutex()
    private val logger = LoggerFactory.getLogger(PremiumService::class.java)
    val configured: Boolean get() = pg.configured
    suspend fun product(): ProductResponse {
        val url = PremiumPurchaseUrlPolicy.publicUrl(purchaseSettings())
        val plans = database.tx(database::plans).filter(PlanCatalogPolicy::visible)
        // Never silently pick arbitrary plans if externally edited/corrupt storage violates the limit.
        if (plans.size > PlanCatalogPolicy.MAX_PUBLIC_PLANS) unavailable("PLAN_CATALOG_INVALID")
        return ProductResponse(clock(), plans, pg.configured,
            premiumPurchaseUrl = url, premiumPurchaseEnabled = url != null)
    }
    suspend fun plans() = database.tx(database::plans)
    suspend fun createPlan(plan: Plan, actor: String): Plan = savePlan(plan, actor, create = true)
    suspend fun updatePlan(plan: Plan, actor: String, preserveAssignable: Boolean = false): Plan =
        savePlan(plan, actor, create = false, preserveAssignable = preserveAssignable)
    private suspend fun savePlan(plan: Plan, actor: String, create: Boolean, preserveAssignable: Boolean = false): Plan {
        if (!PlanCatalogPolicy.valid(plan)) bad("تنظیمات پلن معتبر نیست")
        return database.tx { c ->
            val current = database.plans(c)
            val existing = current.firstOrNull { it.id == plan.id }
            if (create && existing != null) conflict("شناسهٔ پلن قبلاً استفاده شده است")
            if (!create && existing == null) missing()
            val saved = if (!create && preserveAssignable) plan.copy(isAssignable = existing!!.isAssignable) else plan
            PlanCatalogPolicy.requirePublicationCapacity(current.filterNot { it.id == plan.id } + saved)
            if (create) c.exec("INSERT INTO plans(id,document) VALUES (?,?)", saved.id, json.encodeToString(saved))
            else c.exec("UPDATE plans SET document=? WHERE id=?", json.encodeToString(saved), saved.id)
            database.audit(c, actor, "${if (create) "PLAN_CREATED" else "PLAN_UPDATED"}:${plan.id}", clock())
            saved
        }
    }
    suspend fun create(request: CreateAccountRequest, requestKey: String, actor: String): AccountView {
        val username = normalizeUsername(request.username)
        validatePassword(request.password)
        validateKey(requestKey)
        if (request.existingPasarGuardUserId != null && (request.existingPasarGuardUserId <= 0 || !request.confirmExistingUser))
            bad("اتصال به حساب موجود نیاز به شناسه و تأیید صریح دارد")
        val fingerprint = sha256("CREATE|$username|${request.planId}|${request.existingPasarGuardUserId}")
        val key = "$actor:$requestKey"
        existing(key, fingerprint)?.let { return it }
        val hash = passwords.hash(request.password)
        try {
            return database.tx { c ->
                existing(c, key, fingerprint)?.let { return@tx it }
                val plan = PlanCatalogPolicy.requireAssignable(database.plan(c, request.planId))
                val id = UUID.randomUUID().toString(); val at = clock()
                val target = SyncTarget(if (plan.lifetime) null else Math.addExact(at, plan.durationDays!!.toLong() * 86_400_000), plan.quotaBytes)
                val instance = c.rows("SELECT id FROM installation") { it.getString(1) }.single()
                c.exec("INSERT INTO accounts(id,username,password_hash,plan_id,created_at,expires_at,quota_bytes,pg_id,pg_username,marker) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id, username, hash, plan.id, at, target.expiresAtEpochMillis, target.quotaBytes, request.existingPasarGuardUserId,
                    "spd_${id.replace("-", "").take(28)}", "spd:$instance:$id")
                enqueue(c, id, if (request.existingPasarGuardUserId == null) "CREATE" else "LINK", target, key, fingerprint, actor)
                database.view(c, database.account(c, id))
            }
        } catch (error: SQLException) {
            if (error.errorCode == 19) conflict("نام کاربری یا حساب VPN قبلاً استفاده شده است")
            unavailable("SPD_STORAGE_UNAVAILABLE")
        }
    }
    private fun validateKey(key: String) { if (!key.matches(Regex("[A-Za-z0-9_-]{8,128}"))) bad("Idempotency-Key معتبر لازم است") }
    private suspend fun existing(key: String, fingerprint: String): AccountView? = database.tx { existing(it, key, fingerprint) }
    private fun existing(c: Connection, key: String, fingerprint: String): AccountView? {
        val found = c.rows("SELECT account_id,fingerprint FROM operations WHERE request_key=?", key) {
            it.getString(1) to it.getString(2) }.singleOrNull() ?: return null
        if (found.second != fingerprint) conflict("این کلید قبلاً برای درخواست دیگری استفاده شده است")
        return database.view(c, database.account(c, found.first))
    }
    private fun enqueue(c: Connection, id: String, kind: String, target: SyncTarget, key: String, fingerprint: String, actor: String) {
        val operation = UUID.randomUUID().toString()
        c.exec("INSERT INTO operations(id,request_key,fingerprint,account_id,kind,target,created_at) VALUES (?,?,?,?,?,?,?)",
            operation, key, fingerprint, id, kind, json.encodeToString(target), clock())
        c.exec("UPDATE accounts SET sync_state='PENDING',last_error=NULL WHERE id=?", id)
        database.audit(c, actor, "${kind}_REQUESTED", clock(), id, operation)
    }
    suspend fun change(id: String, request: ChangeSubscriptionRequest, key: String, actor: String): AccountView {
        validateKey(key)
        val fingerprint = sha256("$id|${json.encodeToString(request)}")
        existing("$actor:$key", fingerprint)?.let { return it }
        val before = database.tx { database.account(it, id) }
        if (before.syncState == SyncState.SYNCED) observe(id)
        return database.tx { c ->
            existing(c, "$actor:$key", fingerprint)?.let { return@tx it }
            val account = database.account(c, id)
            if (account.syncState != SyncState.SYNCED) conflict("ابتدا عملیات قبلی را تطبیق یا بازیابی کنید")
            var target = account.target
            var planId = account.planId
            when (request.action) {
                "SET_DISABLED" -> target = target.copy(disabled = request.disabled ?: bad("وضعیت لازم است"))
                "RENEW", "CHANGE_PLAN" -> {
                    val plan = PlanCatalogPolicy.requireAssignable(database.plan(c, request.planId ?: account.planId))
                    if (request.action == "RENEW" && account.target.expiresAtEpochMillis == null)
                        bad("حساب دائمی تمدید زمانی ندارد؛ تبدیل صریح لازم است")
                    if (account.target.expiresAtEpochMillis == null && !plan.lifetime && !request.confirmLifetimeToTimed)
                        bad("تبدیل دائمی به زمانی نیاز به تأیید صریح دارد")
                    val base = if (request.action == "RENEW") maxOf(clock(), target.expiresAtEpochMillis ?: clock()) else clock()
                    target = target.copy(expiresAtEpochMillis = if (plan.lifetime) null else Math.addExact(base, plan.durationDays!!.toLong() * 86_400_000),
                        quotaBytes = plan.quotaBytes)
                    planId = plan.id
                }
                else -> bad("عملیات معتبر نیست")
            }
            c.exec("UPDATE accounts SET plan_id=?,expires_at=?,quota_bytes=?,disabled=? WHERE id=?", planId,
                target.expiresAtEpochMillis, target.quotaBytes, if (target.disabled) 1 else 0, id)
            enqueue(c, id, request.action, target, "$actor:$key", fingerprint, actor)
            database.view(c, database.account(c, id))
        }
    }
    suspend fun users(search: String, offset: Int, limit: Int): Page<AccountView> = database.tx { c ->
        val q = "%${search.take(128).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        val total = c.rows("SELECT COUNT(*) FROM accounts WHERE username LIKE ? ESCAPE '\\'", q) { it.getInt(1) }.single()
        val ids = c.rows("SELECT id FROM accounts WHERE username LIKE ? ESCAPE '\\' ORDER BY created_at DESC,id LIMIT ? OFFSET ?", q, limit, offset) { it.getString(1) }
        Page(ids.map { database.view(c, database.account(c, it)) }, total, offset, limit)
    }
    suspend fun repairLink(id: String, request: LinkRequest, key: String, actor: String): AccountView {
        validateKey(key)
        if (!request.confirm || request.pasarGuardUserId <= 0) bad("اتصال به حساب موجود نیاز به تأیید صریح دارد")
        val fingerprint = sha256("LINK|$id|${request.pasarGuardUserId}|${request.planId}")
        try {
            return database.tx { c ->
                existing(c, "$actor:$key", fingerprint)?.let { return@tx it }
                val account = database.account(c, id)
                val previous = database.latestOperation(c, id)?.let { database.operation(c, it) } ?: missing()
                if (account.syncState != SyncState.FAILED || account.confirmed != null || previous.kind != "LINK")
                    conflict("فقط اتصال تأییدنشده و ناموفق را می‌توان با تأیید مدیر اصلاح کرد")
                val plan = PlanCatalogPolicy.requireAssignable(database.plan(c, request.planId))
                val target = SyncTarget(if (plan.lifetime) null else clock() + plan.durationDays!!.toLong() * 86_400_000,
                    plan.quotaBytes, account.target.disabled)
                c.exec("UPDATE accounts SET pg_id=?,plan_id=?,expires_at=?,quota_bytes=? WHERE id=?", request.pasarGuardUserId,
                    plan.id, target.expiresAtEpochMillis, target.quotaBytes, id)
                enqueue(c, id, "LINK", target, "$actor:$key", fingerprint, actor)
                database.view(c, database.account(c, id))
            }
        } catch (error: SQLException) {
            if (error.errorCode == 19) conflict("حساب VPN قبلاً مرتبط شده است")
            unavailable("SPD_STORAGE_UNAVAILABLE")
        }
    }
    suspend fun user(id: String): AccountView = database.tx { database.view(it, database.account(it, id)) }
    suspend fun operations(id: String): List<OperationView> = database.tx { c ->
        database.account(c, id)
        c.rows("SELECT id FROM operations WHERE account_id=? ORDER BY created_at DESC,rowid DESC LIMIT 50", id) { it.getString(1) }
            .map { database.operation(c, it).view() }
    }
    suspend fun audit(offset: Int, limit: Int): Page<AuditEvent> = database.tx { c ->
        val total = c.rows("SELECT COUNT(*) FROM audit_events") { it.getInt(1) }.single()
        val events = c.rows("SELECT * FROM audit_events ORDER BY id DESC LIMIT ? OFFSET ?", limit, offset) {
            AuditEvent(it.getLong("id"), it.getString("actor"), it.getString("action"), it.getString("account_id"), it.getString("operation_id"), it.getLong("at")) }
        Page(events, total, offset, limit)
    }
    suspend fun dashboard(): Map<String, Int> = database.tx { c ->
        mapOf("users" to c.rows("SELECT COUNT(*) FROM accounts") { it.getInt(1) }.single(),
            "disabled" to c.rows("SELECT COUNT(*) FROM accounts WHERE disabled=1") { it.getInt(1) }.single(),
            "pending" to c.rows("SELECT COUNT(*) FROM accounts WHERE sync_state='PENDING'") { it.getInt(1) }.single(),
            "failed" to c.rows("SELECT COUNT(*) FROM accounts WHERE sync_state='FAILED'") { it.getInt(1) }.single())
    }
    suspend fun retry(id: String, actor: String): AccountView = database.tx { c ->
        val a = database.account(c, id)
        val op = database.latestOperation(c, id) ?: missing()
        if (a.syncState == SyncState.SYNCED) return@tx database.view(c, a)
        c.exec("UPDATE operations SET state='PENDING',error_code=NULL WHERE id=?", op)
        c.exec("UPDATE accounts SET sync_state='PENDING',last_error=NULL WHERE id=?", id)
        database.audit(c, actor, "RETRY_REQUESTED", clock(), id, op)
        database.view(c, database.account(c, id))
    }
    suspend fun synchronizePending() {
        if (!syncMutex.tryLock()) return
        try {
            val ids = database.tx { c -> c.rows("SELECT id FROM operations WHERE state='PENDING' ORDER BY created_at,rowid LIMIT 20") { it.getString(1) } }
            for (id in ids) sync(id)
        } finally { syncMutex.unlock() }
    }
    private suspend fun sync(id: String) {
        val (op, account) = database.tx { c ->
            val op = database.operation(c, id)
            c.exec("UPDATE operations SET attempts=attempts+1 WHERE id=?", id)
            op to database.account(c, op.accountId)
        }
        try {
            var remote = if (account.pgId != null) pg.byId(account.pgId)
                else if (op.kind == "CREATE") pg.byUsername(account.pgUsername) else null
            if (op.kind == "LINK") {
                remote ?: throw PremiumProblem(409, "LINK_USER_MISSING", "حساب VPN برای اتصال پیدا نشد")
                if (remote.status == "on_hold") conflict("حساب on_hold در این نسخه قابل اتصال نیست")
                if ((remote.expiresAtEpochMillis == null) != (account.target.expiresAtEpochMillis == null))
                    conflict("پلن انتخابی با دائمی/زمانی بودن حساب VPN سازگار نیست")
                // Explicit linking adopts the existing remote entitlement without overwriting it.
                val target = SyncTarget(remote.expiresAtEpochMillis, remote.quotaBytes, remote.status == "disabled")
                database.tx { c ->
                    c.exec("UPDATE accounts SET pg_username=?,expires_at=?,quota_bytes=?,disabled=? WHERE id=?",
                        remote.username, target.expiresAtEpochMillis, target.quotaBytes, if (target.disabled) 1 else 0, account.id)
                    c.exec("UPDATE operations SET target=? WHERE id=?", json.encodeToString(target), op.id)
                }
            } else {
                if (remote == null && op.kind == "CREATE" && account.pgId == null) {
                    try { pg.create(account.pgUsername, account.marker, op.target) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: PremiumProblem) {
                        // A timeout/409 may follow an applied create. Reconcile deterministic identity first.
                        remote = pg.byUsername(account.pgUsername)
                        if (remote == null) throw failure
                    }
                    remote = remote ?: pg.byUsername(account.pgUsername)
                }
                remote ?: unavailable("PASARGUARD_USER_MISSING")
                if (remote.username != account.pgUsername || (account.pgId != null && remote.id != account.pgId) ||
                    (op.kind == "CREATE" && remote.note != account.marker))
                    throw PremiumProblem(409, "PASARGUARD_IDENTITY_CONFLICT", "هویت کاربر VPN تأیید نشد")
                if (!matches(remote, op.target)) {
                    // Absolute target, never add days or reset traffic on the remote side.
                    pg.update(remote.id, op.target)
                }
                remote = pg.byId(remote.id) ?: unavailable("PASARGUARD_USER_MISSING")
                if (!matches(remote, op.target)) {
                    database.tx { database.saveObservation(it, account.id, remote, clock()) }
                    throw PremiumProblem(409, "PASARGUARD_STATE_CONFLICT", "وضعیت تأییدشده با درخواست متفاوت است")
                }
            }
            val verified = remote ?: unavailable()
            database.tx { c ->
                c.exec("UPDATE accounts SET pg_id=?,sync_state='SYNCED',last_error=NULL WHERE id=?", verified.id, account.id)
                database.saveObservation(c, account.id, verified, clock())
                c.exec("UPDATE operations SET state='SYNCED',error_code=NULL WHERE id=?", id)
                database.audit(c, "sync-worker", "SYNCED", clock(), account.id, id)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            val code = if (failure is PremiumProblem) failure.code else "SYNC_FAILED"
            database.tx { c ->
                c.exec("UPDATE accounts SET sync_state='FAILED',last_error=? WHERE id=?", code, account.id)
                c.exec("UPDATE operations SET state='FAILED',error_code=? WHERE id=?", code, id)
                database.audit(c, "sync-worker", "SYNC_FAILED:$code", clock(), account.id, id)
            }
        }
    }
    private fun matches(remote: RemoteUser, target: SyncTarget): Boolean =
        remote.expiresAtEpochMillis == target.expiresAtEpochMillis && remote.quotaBytes == target.quotaBytes &&
            (if (target.disabled) remote.status == "disabled" else remote.status in setOf("active", "expired", "limited"))
    private suspend fun observe(id: String): Account {
        val a = database.tx { database.account(it, id) }
        val pgId = a.pgId ?: return a
        val remote = pg.byId(pgId) ?: unavailable("PASARGUARD_USER_MISSING")
        if (remote.username != a.pgUsername) unavailable("PASARGUARD_IDENTITY_CONFLICT")
        database.tx { c ->
            database.saveObservation(c, id, remote, clock())
            val current = database.account(c, id)
            if (current.target == a.target && current.syncState == SyncState.SYNCED && !matches(remote, a.target)) {
                c.exec("UPDATE accounts SET sync_state='FAILED',last_error='PASARGUARD_STATE_CONFLICT' WHERE id=?", id)
                database.latestOperation(c, id)?.let { op -> c.exec("UPDATE operations SET state='FAILED',error_code='PASARGUARD_STATE_CONFLICT' WHERE id=?", op) }
            }
        }
        return database.tx { database.account(it, id) }
    }
    suspend fun refreshUser(id: String, actor: String): AccountView {
        if (database.tx { database.account(it, id).pgId } == null)
            conflict("ابتدا همگام‌سازی و تأیید شناسهٔ VPN را تکمیل کنید")
        observe(id)
        database.tx { database.audit(it, actor, "REMOTE_STATUS_READ", clock(), id) }
        return user(id)
    }
    private fun status(a: Account): AccountStatus = when {
        a.target.disabled || a.confirmed?.status == "disabled" -> AccountStatus.DISABLED
        a.target.expiresAtEpochMillis?.let { it <= clock() } == true || a.confirmed?.expiresAtEpochMillis?.let { it <= clock() } == true -> AccountStatus.EXPIRED
        else -> AccountStatus.ACTIVE
    }
    private fun canConnect(a: Account) = pg.configured && status(a) == AccountStatus.ACTIVE && a.syncState == SyncState.SYNCED &&
        a.confirmed?.status == "active" && a.confirmed.quotaBytes?.let { a.confirmed.usedBytes < it } != false &&
        a.observedAt?.let { clock() - it in 0..60_000 } == true
    private suspend fun profile(id: String): AccountProfile = database.tx { c ->
        val a = database.account(c, id); val plan = database.plan(c, a.planId)
        AccountProfile(a.username, status(a), a.planId, plan.title, a.target.expiresAtEpochMillis,
            a.target.expiresAtEpochMillis == null, canConnect(a), clock(), a.syncState, a.target.quotaBytes, a.confirmed?.usedBytes)
    }
    suspend fun login(request: CredentialsRequest, ip: String): SessionTokens {
        val (id, tokens) = security.loginUser(request, ip)
        return SessionTokens(tokens.access, tokens.accessExpiry, tokens.refresh, tokens.refreshExpiry, profile(id), clock())
    }
    suspend fun refreshSession(token: String): SessionTokens {
        val (id, tokens) = security.refresh(token)
        return SessionTokens(tokens.access, tokens.accessExpiry, tokens.refresh, tokens.refreshExpiry, profile(id), clock())
    }
    suspend fun me(owner: String): AccountProfile {
        val account = database.tx { database.account(it, owner) }
        if (account.pgId != null && account.syncState == SyncState.SYNCED) observe(owner)
        return profile(owner)
    }
    private suspend fun authorized(owner: String): Account {
        val a = observe(owner)
        if (a.syncState != SyncState.SYNCED) unavailable("PREMIUM_SYNC_REQUIRED")
        if (!canConnect(a)) throw PremiumProblem(403, "PREMIUM_NOT_ACTIVE", "اشتراک مجوز اتصال ندارد؛ وضعیت و سقف حجم را بررسی کنید")
        return a
    }
    suspend fun catalog(owner: String): PremiumCatalogResponse {
        val a = authorized(owner)
        val profiles = pg.profiles(a.pgId!!)
        authorized(owner) // Fence expiry/admin changes that happened while fetching private data.
        logger.info(PremiumProfileDiagnostics.catalog(profiles.map { it.summary.id }))
        return PremiumCatalogResponse(profiles.map { it.summary }, clock())
    }
    suspend fun xray(owner: String, serverId: String): XrayProfileResponse {
        val a = authorized(owner)
        val profiles = pg.profiles(a.pgId!!)
        val profile = profiles.firstOrNull { it.summary.id == serverId }
        logger.info(PremiumProfileDiagnostics.lookup(serverId, profile != null, profiles.size))
        if (profile == null) missing()
        authorized(owner)
        return XrayProfileResponse(profile.summary, profile.config.toString(), a.target.expiresAtEpochMillis)
    }
}
