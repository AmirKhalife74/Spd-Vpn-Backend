package app.spdvpn.backend.premium

import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AdminLogin(val token: String, val data: AdminSessionData)
internal class AdminPrincipal(val id: String, val username: String, val csrf: String, val expiresAt: Long)
internal class TokenPair(val access: String, val accessExpiry: Long, val refresh: String, val refreshExpiry: Long)

class PremiumSecurity(private val db: PremiumDatabase, private val config: PremiumConfig,
    private val passwords: PasswordHasher, private val clock: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()
    private var dummyHash = ""
    suspend fun initialize() { dummyHash = passwords.hash(token()) }
    suspend fun bootstrap(username: String, password: String) {
        val normalized = normalizeUsername(username)
        val hash = passwords.hash(password)
        db.tx { c ->
            if (c.rows("SELECT COUNT(*) FROM administrators") { it.getInt(1) }.single() != 0)
                conflict("مدیر اولیه قبلاً ساخته شده؛ bootstrap فقط یک بار مجاز است")
            c.exec("INSERT INTO administrators(id,username,password_hash,created_at) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(), normalized, hash, clock())
            db.audit(c, "bootstrap-cli", "ADMIN_BOOTSTRAPPED", clock())
        }
    }
    private fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private suspend fun throttle(scope: String, username: String, ip: String) {
        val now = clock()
        db.tx { c ->
            c.exec("DELETE FROM login_attempts WHERE at<=?", now - 900_000)
            val keys = listOf("$scope:global" to 100, "$scope:ip:${sha256(ip)}" to 20,
                "$scope:name:${sha256(username)}" to 5)
            keys.forEach { (key, limit) ->
                if (c.rows("SELECT COUNT(*) FROM login_attempts WHERE scope=?", key) { it.getInt(1) }.single() >= limit)
                    throw PremiumProblem(429, "LOGIN_RATE_LIMITED", "تلاش زیاد؛ بعداً دوباره امتحان کنید")
            }
            keys.forEach { (key, _) -> c.exec("INSERT INTO login_attempts(scope,at) VALUES (?,?)", key, now) }
        }
    }
    internal suspend fun loginAdmin(request: CredentialsRequest, ip: String): AdminLogin {
        val username = request.username.trim().lowercase(java.util.Locale.ROOT).take(256)
        throttle("admin", username, ip)
        val record = db.tx { c -> c.rows("SELECT id,username,password_hash FROM administrators WHERE username=?", username) {
            Triple(it.getString(1), it.getString(2), it.getString(3)) }.singleOrNull() }
        val valid = passwords.verify(request.password, record?.third ?: dummyHash)
        if (!valid || record == null) throw PremiumProblem(401, "INVALID_CREDENTIALS", "نام کاربری یا رمز معتبر نیست")
        val raw = token(); val csrf = token(); val expiry = clock() + config.adminSessionMillis
        db.tx { c ->
            c.exec("DELETE FROM admin_sessions WHERE expires_at<=?", clock())
            c.exec("INSERT INTO admin_sessions(token_hash,admin_id,csrf,expires_at) VALUES (?,?,?,?)", sha256(raw), record.first, csrf, expiry)
            c.exec("DELETE FROM login_attempts WHERE scope=?", "admin:name:${sha256(username)}")
            db.audit(c, record.second, "ADMIN_LOGIN", clock())
        }
        return AdminLogin(raw, AdminSessionData(record.second, csrf, expiry))
    }
    internal suspend fun admin(raw: String?, csrf: String? = null, mutation: Boolean = false): AdminPrincipal {
        if (!safeToken(raw)) unauthorized()
        val principal = db.tx { c -> c.rows(
            "SELECT a.id,a.username,s.csrf,s.expires_at FROM admin_sessions s JOIN administrators a ON a.id=s.admin_id WHERE s.token_hash=? AND s.expires_at>?",
            sha256(raw!!), clock()) { AdminPrincipal(it.getString(1), it.getString(2), it.getString(3), it.getLong(4)) }.singleOrNull() }
            ?: unauthorized()
        if (mutation && (csrf == null || !MessageDigest.isEqual(principal.csrf.toByteArray(), csrf.toByteArray())))
            throw PremiumProblem(403, "CSRF_INVALID", "درخواست مدیریتی معتبر نیست")
        return principal
    }
    internal suspend fun logoutAdmin(raw: String?, actor: String) = db.tx { c ->
        c.exec("DELETE FROM admin_sessions WHERE token_hash=?", sha256(raw.orEmpty()))
        db.audit(c, actor, "ADMIN_LOGOUT", clock())
    }
    internal suspend fun loginUser(request: CredentialsRequest, ip: String): Pair<String, TokenPair> {
        val username = request.username.trim().lowercase(java.util.Locale.ROOT).take(256)
        throttle("account", username, ip)
        val account = db.tx { db.byUsername(it, username) }
        val valid = passwords.verify(request.password, account?.passwordHash ?: dummyHash)
        if (!valid || account == null) throw PremiumProblem(401, "INVALID_CREDENTIALS", "نام کاربری یا رمز معتبر نیست")
        db.tx { it.exec("DELETE FROM login_attempts WHERE scope=?", "account:name:${sha256(username)}") }
        // Even expired/disabled accounts can see their status. Authorization is a separate check.
        return account.id to issueUser(account.id, account.passwordHash)
    }
    private suspend fun issueUser(id: String, verifiedHash: String): TokenPair {
        val pair = TokenPair(token(), clock() + config.accessMillis, token(), clock() + config.refreshMillis)
        db.tx { c ->
            if (db.account(c, id).passwordHash != verifiedHash) unauthorized()
            c.exec("DELETE FROM user_sessions WHERE refresh_expires_at<=?", clock())
            c.exec("INSERT INTO user_sessions(id,account_id,access_hash,refresh_hash,access_expires_at,refresh_expires_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), id, sha256(pair.access), sha256(pair.refresh), pair.accessExpiry, pair.refreshExpiry)
        }
        return pair
    }
    internal suspend fun refresh(raw: String): Pair<String, TokenPair> {
        if (!safeToken(raw)) unauthorized()
        val pair = TokenPair(token(), clock() + config.accessMillis, token(), clock() + config.refreshMillis)
        val id = db.tx { c ->
            val found = c.rows("SELECT id,account_id FROM user_sessions WHERE refresh_hash=? AND refresh_expires_at>?",
                sha256(raw), clock()) { it.getString(1) to it.getString(2) }.singleOrNull() ?: unauthorized()
            c.exec("UPDATE user_sessions SET access_hash=?,refresh_hash=?,access_expires_at=?,refresh_expires_at=? WHERE id=?",
                sha256(pair.access), sha256(pair.refresh), pair.accessExpiry, pair.refreshExpiry, found.first)
            found.second
        }
        return id to pair
    }
    internal suspend fun owner(raw: String?): String {
        if (!safeToken(raw)) unauthorized()
        return db.tx { c -> c.rows("SELECT account_id FROM user_sessions WHERE access_hash=? AND access_expires_at>? AND refresh_expires_at>?",
            sha256(raw!!), clock(), clock()) { it.getString(1) }.singleOrNull() } ?: unauthorized()
    }
    internal suspend fun logoutUser(refresh: String) {
        if (!safeToken(refresh)) unauthorized()
        db.tx { it.exec("DELETE FROM user_sessions WHERE refresh_hash=?", sha256(refresh)) }
    }
    internal suspend fun changePassword(id: String, password: String, actor: String) {
        val hash = passwords.hash(password)
        db.tx { c ->
            db.account(c, id)
            c.exec("UPDATE accounts SET password_hash=? WHERE id=?", hash, id)
            c.exec("DELETE FROM user_sessions WHERE account_id=?", id)
            db.audit(c, actor, "APP_PASSWORD_CHANGED", clock(), id)
        }
    }
    private fun safeToken(raw: String?) = raw != null && raw.matches(Regex("[A-Za-z0-9_-]{43}"))
}
