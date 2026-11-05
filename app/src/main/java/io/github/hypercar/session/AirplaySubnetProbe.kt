package io.github.hypercar.session

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object AirplaySubnetProbe {
    suspend fun find(
        hosts: List<String>, ports: List<Int>, chunkSize: Int,
        shouldStop: () -> Boolean, probe: (String, Int) -> Boolean,
    ): Pair<String, Int>? = coroutineScope {
        require(chunkSize > 0)
        val hit = AtomicReference<Pair<String, Int>?>(null)
        val validPorts = ports.filter { it in 1..65535 }.distinct()
        for (chunk in hosts.chunked(chunkSize)) {
            ensureActive()
            if (hit.get() != null || shouldStop()) break
            chunk.map { host -> async(Dispatchers.IO) {
                for (port in validPorts) {
                    currentCoroutineContext().ensureActive()
                    if (hit.get() != null || shouldStop()) return@async
                    if (probe(host, port)) {
                        hit.compareAndSet(null, host to port)
                        return@async
                    }
                }
            } }.awaitAll()
        }
        hit.get().takeUnless { shouldStop() }
    }
}
