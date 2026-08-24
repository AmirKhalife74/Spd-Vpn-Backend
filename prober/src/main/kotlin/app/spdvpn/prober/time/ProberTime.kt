package app.spdvpn.prober.time

import kotlinx.coroutines.delay
import kotlin.random.Random

fun interface ProberClock {
    fun nowEpochMillis(): Long
}

fun interface SuspendSleeper {
    suspend fun sleep(millis: Long)
}

fun interface JitterSource {
    fun nextDelayMillis(maxInclusive: Long): Long
}

object SystemProberClock : ProberClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

object CoroutineSleeper : SuspendSleeper {
    override suspend fun sleep(millis: Long) {
        if (millis > 0) delay(millis)
    }
}

class RandomJitterSource(
    private val random: Random = Random.Default,
) : JitterSource {
    override fun nextDelayMillis(maxInclusive: Long): Long = when {
        maxInclusive <= 0 -> 0
        maxInclusive == Long.MAX_VALUE -> random.nextLong().ushr(1)
        else -> random.nextLong(maxInclusive + 1)
    }
}
