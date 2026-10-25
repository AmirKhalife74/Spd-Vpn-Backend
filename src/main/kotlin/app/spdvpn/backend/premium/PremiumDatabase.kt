package app.spdvpn.backend.premium

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** A single-writer SQLite store. Network I/O is never executed inside a DB transaction. */
class PremiumDatabase(private val path: Path) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    init { Class.forName("org.sqlite.JDBC") }
    suspend fun initialize(defaultQuota: Long) = tx { db ->
        db.createStatement().use { s ->
            val version = s.executeQuery("PRAGMA user_version").use { r -> r.next(); r.getInt(1) }
            require(version <= 1) { "Unsupported SPD database schema" }
            if (version == 0) {
                val migration = requireNotNull(javaClass.getResourceAsStream("/db/migrations/001_premium.sql"))
                    .bufferedReader().use { it.readText() }
                migration.split(';').filter(String::isNotBlank).forEach(s::execute)
                s.execute("PRAGMA user_version=1")
                db.exec("INSERT INTO installation(id) VALUES (?)", UUID.randomUUID().toString())
            }
        }
        listOf(Plan("MONTH_1", "یک‌ماهه", "اعتبار از زمان ساخت حساب؛ حجم کل بدون reset خودکار", 30, quotaBytes = defaultQuota),
            Plan("MONTH_3", "سه‌ماهه", "اعتبار از زمان ساخت حساب؛ حجم کل بدون reset خودکار", 90, quotaBytes = defaultQuota),
            Plan("LIFETIME", "بدون انقضای زمانی", "دائمی زمانی؛ سقف حجم مستقل است", lifetime = true, quotaBytes = defaultQuota))
            .forEach { db.exec("INSERT OR IGNORE INTO plans(id,document) VALUES (?,?)", it.id, json.encodeToString(it)) }
    }
    suspend fun <T> tx(block: (Connection) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { db ->
                try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
                catch (_: UnsupportedOperationException) { /* Windows uses directory ACLs. */ }
                db.createStatement().use { it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA busy_timeout=5000") }
                db.autoCommit = false
                try { block(db).also { db.commit() } } catch (e: Throwable) { db.rollback(); throw e }
            }
        }
    }
    fun plans(db: Connection): List<Plan> = db.rows("SELECT document FROM plans ORDER BY CASE id WHEN 'MONTH_1' THEN 0 WHEN 'MONTH_3' THEN 1 ELSE 2 END") {
        json.decodeFromString<Plan>(it.getString(1))
    }.sortedWith(compareBy<Plan> { it.displayOrder }.thenBy { it.id })
    fun plan(db: Connection, id: String): Plan = plans(db).firstOrNull { it.id == id } ?: bad("پلن معتبر نیست")
    internal fun account(db: Connection, id: String): Account = db.rows("SELECT * FROM accounts WHERE id=?", id, map = ::readAccount)
        .singleOrNull() ?: missing()
    internal fun byUsername(db: Connection, username: String): Account? = db.rows("SELECT * FROM accounts WHERE username=?", username,
        map = ::readAccount).singleOrNull()
    internal fun operation(db: Connection, id: String): Operation = db.rows("SELECT * FROM operations WHERE id=?", id,
        map = ::readOperation).singleOrNull() ?: missing()
    fun latestOperation(db: Connection, accountId: String): String? = db.rows(
        "SELECT id FROM operations WHERE account_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1", accountId) { it.getString(1) }.singleOrNull()
    internal fun view(db: Connection, a: Account) = AccountView(a.id, a.username, a.planId, a.createdAt, a.target,
        a.confirmed?.copy(note = null), a.observedAt, a.syncState, a.lastError, a.pgId, latestOperation(db, a.id))
    fun audit(db: Connection, actor: String, action: String, now: Long, accountId: String? = null, operationId: String? = null) {
        db.exec("INSERT INTO audit_events(actor,action,account_id,operation_id,at) VALUES (?,?,?,?,?)",
            actor, action, accountId, operationId, now)
    }
    fun saveObservation(db: Connection, id: String, remote: RemoteUser, at: Long) {
        db.exec("UPDATE accounts SET confirmed=?,observed_at=? WHERE id=?", json.encodeToString(remote), at, id)
    }
    private fun readAccount(r: ResultSet) = Account(r.getString("id"), r.getString("username"), r.getString("password_hash"),
        r.getString("plan_id"), r.getLong("created_at"),
        SyncTarget(r.nullLong("expires_at"), r.nullLong("quota_bytes"), r.getInt("disabled") == 1),
        r.nullLong("pg_id"), r.getString("pg_username"), r.getString("marker"),
        r.getString("confirmed")?.let { json.decodeFromString<RemoteUser>(it) }, r.nullLong("observed_at"),
        SyncState.valueOf(r.getString("sync_state")), r.getString("last_error"))
    private fun readOperation(r: ResultSet) = Operation(r.getString("id"), r.getString("account_id"), r.getString("kind"),
        json.decodeFromString<SyncTarget>(r.getString("target")), SyncState.valueOf(r.getString("state")),
        r.getInt("attempts"), r.getLong("created_at"), r.getString("error_code"))
}

internal class Account(val id: String, val username: String, val passwordHash: String, val planId: String,
    val createdAt: Long, val target: SyncTarget, val pgId: Long?, val pgUsername: String, val marker: String,
    val confirmed: RemoteUser?, val observedAt: Long?, val syncState: SyncState, val lastError: String?) {
    override fun toString() = "Account(redacted)"
}
internal data class Operation(val id: String, val accountId: String, val kind: String, val target: SyncTarget,
    val state: SyncState, val attempts: Int, val createdAt: Long, val errorCode: String?) {
    fun view() = OperationView(id, accountId, kind, state, attempts, createdAt, errorCode)
}
internal fun Connection.exec(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { s ->
    args.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.executeUpdate()
}
internal fun <T> Connection.rows(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = prepareStatement(sql).use { s ->
    args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
    s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
}
internal fun ResultSet.nullLong(name: String): Long? = (getObject(name) as? Number)?.toLong()
