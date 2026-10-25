package app.spdvpn.backend.premium

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Argon2id v19, 64MiB, 3 passes, p=1. Bound concurrent hashing to avoid memory exhaustion. */
class PasswordHasher(private val memoryKiB: Int = 65536, private val iterations: Int = 3) {
    private val random = SecureRandom()
    private val permits = Semaphore(2)
    suspend fun hash(password: String): String = permits.withPermit { withContext(Dispatchers.Default) {
        validatePassword(password)
        val salt = ByteArray(16).also(random::nextBytes)
        val derived = derive(password, salt, memoryKiB, iterations)
        "argon2id-v19:$memoryKiB:$iterations:${encode(salt)}:${encode(derived)}"
    } }
    suspend fun verify(password: String, encoded: String): Boolean = permits.withPermit { withContext(Dispatchers.Default) {
        if (password.length !in 1..256) return@withContext false
        try {
            val parts = encoded.split(':')
            if (parts.size != 5 || parts[0] != "argon2id-v19") return@withContext false
            val memory = parts[1].toInt().takeIf { it in 8192..65536 } ?: return@withContext false
            val passes = parts[2].toInt().takeIf { it in 1..3 } ?: return@withContext false
            MessageDigest.isEqual(derive(password, Base64.getDecoder().decode(parts[3]), memory, passes),
                Base64.getDecoder().decode(parts[4]))
        } catch (_: Exception) { false }
    } }
    private fun derive(password: String, salt: ByteArray, memory: Int, passes: Int): ByteArray {
        val bytes = password.toByteArray(Charsets.UTF_8)
        return try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(memory).withIterations(passes)
                .withParallelism(1).withSalt(salt).build()
            ByteArray(32).also { result -> Argon2BytesGenerator().apply { init(params) }.generateBytes(bytes, result) }
        } finally { bytes.fill(0) }
    }
    private fun encode(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)
}
internal fun validatePassword(password: String) {
    if (password.length !in 12..256) bad("رمز باید ۱۲ تا ۲۵۶ نویسه باشد")
}
internal fun normalizeUsername(username: String): String = username.trim().lowercase(java.util.Locale.ROOT).also {
    if (!it.matches(Regex("[a-z0-9][a-z0-9_.-]{2,63}"))) bad("نام کاربری باید ۳ تا ۶۴ نویسهٔ لاتین، عدد، نقطه، خط یا زیرخط باشد")
}
