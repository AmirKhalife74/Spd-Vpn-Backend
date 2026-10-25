package app.spdvpn.backend.premium

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Only an offline server-side CLI can create the first administrator. Never accepts passwords in argv. */
fun main(args: Array<String>) = runBlocking {
    if (args.size !in 1..2 || args[0].isBlank() || (args.size == 2 && args[1] != "--password-stdin")) {
        System.err.println("Usage: BootstrapAdminKt USERNAME [--password-stdin]")
        exitProcess(2)
    }
    val chars = if (args.getOrNull(1) == "--password-stdin") System.`in`.bufferedReader().readLine()?.toCharArray()
        else System.console()?.readPassword("Administrator password: ")
    if (chars == null) { System.err.println("A console or explicit --password-stdin is required"); exitProcess(2) }
    try {
        val config = PremiumConfig.fromEnvironment()
        val db = PremiumDatabase(config.databasePath)
        db.initialize(config.defaultQuotaBytes)
        PremiumSecurity(db, config, PasswordHasher()).bootstrap(args[0], chars.concatToString())
        println("Initial administrator created. No password was printed or saved in plaintext.")
    } catch (e: PremiumProblem) {
        System.err.println("Bootstrap failed: ${e.code}"); exitProcess(1)
    } catch (_: Exception) {
        System.err.println("Bootstrap failed. Check local configuration and database access."); exitProcess(1)
    } finally { chars.fill('\u0000') }
}
