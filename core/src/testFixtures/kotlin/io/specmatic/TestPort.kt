package io.specmatic

import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

private val testPortBases = ConcurrentHashMap<String, Int>()
private val allocatedTestPortBases = mutableSetOf<Int>()

@Synchronized
private fun allocateTestPortBase(): Int {
    while (true) {
        val candidate = ThreadLocalRandom.current().nextInt(20_000, 40_000)
        val overlapsAllocatedRange = allocatedTestPortBases.any { allocated ->
            candidate <= allocated + 5 && allocated <= candidate + 5
        }
        if (!overlapsAllocatedRange && candidate < 65_530 && (candidate..candidate + 5).all { port ->
                runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
            }
        ) {
            allocatedTestPortBases.add(candidate)
            return candidate
        }
    }
}

/** Returns a stable port range for this test thread; offsets allow multiple local servers. */
fun testPort(offset: Int = 0): Int {
    val key = "thread-${Thread.currentThread().id}"
    val base = testPortBases.computeIfAbsent(key) { allocateTestPortBase() }
    return base + offset
}
