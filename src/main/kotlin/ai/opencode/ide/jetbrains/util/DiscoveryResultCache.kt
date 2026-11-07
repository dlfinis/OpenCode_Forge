package ai.opencode.ide.jetbrains.util

import java.util.concurrent.CompletableFuture

/** Thread-safe TTL cache that shares one in-progress discovery among concurrent callers. */
internal class DiscoveryResultCache(
    private val healthyTtlMillis: Long = 3000L,
    private val negativeTtlMillis: Long = 250L,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private data class CacheEntry(
        val result: ServiceDiscovery.DiscoveryResult,
        val createdAtMillis: Long,
        val ttlMillis: Long,
        val generation: Long
    )

    private data class InFlight(
        val future: CompletableFuture<ServiceDiscovery.DiscoveryResult>,
        val generation: Long
    )

    private val lock = Any()
    private var generation = 0L
    private var cached: CacheEntry? = null
    private var inFlight: InFlight? = null

    init {
        require(healthyTtlMillis > 0L) { "Healthy discovery TTL must be positive" }
        require(negativeTtlMillis >= 0L) { "Negative discovery TTL cannot be negative" }
    }

    fun discover(discovery: () -> ServiceDiscovery.DiscoveryResult): ServiceDiscovery.DiscoveryResult {
        var leader = false
        var flight: CompletableFuture<ServiceDiscovery.DiscoveryResult>? = null
        var flightGeneration = 0L

        synchronized(lock) {
            val now = clockMillis()
            val entry = cached
            if (entry != null) {
                if (entry.generation == generation && now - entry.createdAtMillis < entry.ttlMillis) {
                    return entry.result
                }
                cached = null
            }

            val active = inFlight
            if (active != null && active.generation == generation) {
                flight = active.future
            } else {
                flightGeneration = generation
                val newFlight = CompletableFuture<ServiceDiscovery.DiscoveryResult>()
                flight = newFlight
                inFlight = InFlight(newFlight, flightGeneration)
                leader = true
            }
        }

        val sharedFlight = checkNotNull(flight)
        if (!leader) return sharedFlight.join()

        try {
            val result = discovery()
            synchronized(lock) {
                if (inFlight?.future === sharedFlight) {
                    val ttl = cacheTtlMillis(result)
                    if (generation == flightGeneration && ttl > 0L) {
                        cached = CacheEntry(result, clockMillis(), ttl, generation)
                    }
                    inFlight = null
                }
            }
            sharedFlight.complete(result)
            return result
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (inFlight?.future === sharedFlight) inFlight = null
            }
            sharedFlight.completeExceptionally(failure)
            throw failure
        }
    }

    fun invalidate() {
        synchronized(lock) {
            generation++
            cached = null
            inFlight = null
        }
    }

    private fun cacheTtlMillis(result: ServiceDiscovery.DiscoveryResult): Long = when (result) {
        is ServiceDiscovery.DiscoveryResult.Registered ->
            if (result.health is OpenCodeHealthResult.Healthy) healthyTtlMillis else 0L
        ServiceDiscovery.DiscoveryResult.Missing,
        is ServiceDiscovery.DiscoveryResult.Unavailable -> negativeTtlMillis
    }
}
