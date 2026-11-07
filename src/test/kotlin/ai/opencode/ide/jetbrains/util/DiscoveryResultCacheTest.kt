package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DiscoveryResultCacheTest {
    @Test
    fun reusesHealthyDiscoveryUntilTheThreeSecondTtlExpires() {
        val clock = AtomicLong(10_000L)
        val cache = DiscoveryResultCache(clockMillis = clock::get)
        var discoveries = 0

        val first = cache.discover {
            discoveries++
            healthyServer(4096)
        }
        clock.addAndGet(2999L)
        val cached = cache.discover {
            discoveries++
            healthyServer(4097)
        }
        clock.addAndGet(1L)
        val refreshed = cache.discover {
            discoveries++
            healthyServer(4097)
        }

        assertSame(first, cached)
        assertEquals(4097, (refreshed as ServiceDiscovery.DiscoveryResult.Registered).server.port)
        assertEquals(2, discoveries)
    }

    @Test
    fun concurrentCallersShareTheInFlightHealthCheck() {
        val cache = DiscoveryResultCache()
        val callers = 6
        val executor = Executors.newFixedThreadPool(callers)
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        val releaseDiscovery = CountDownLatch(1)
        val enteredDiscovery = CountDownLatch(1)
        val discoveries = AtomicInteger()

        try {
            val results = (1..callers).map {
                executor.submit<ServiceDiscovery.DiscoveryResult> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    cache.discover {
                        discoveries.incrementAndGet()
                        enteredDiscovery.countDown()
                        assertTrue(releaseDiscovery.await(5, TimeUnit.SECONDS))
                        healthyServer(4096)
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(enteredDiscovery.await(5, TimeUnit.SECONDS))
            releaseDiscovery.countDown()

            results.forEach { assertTrue(it.get(5, TimeUnit.SECONDS) is ServiceDiscovery.DiscoveryResult.Registered) }
            assertEquals(1, discoveries.get())
        } finally {
            releaseDiscovery.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun doesNotCacheUnhealthyOrUnauthorizedRegistrations() {
        val cache = DiscoveryResultCache()
        var discoveries = 0

        repeat(2) {
            cache.discover {
                discoveries++
                ServiceDiscovery.DiscoveryResult.Registered(
                    ServiceDiscovery.SharedServer("127.0.0.1", 4096, null),
                    OpenCodeHealthResult.AuthRequired(401)
                )
            }
        }

        assertEquals(2, discoveries)
    }

    @Test
    fun expiresMissingRegistrationQuicklyInsteadOfHoldingANegativeResultForThreeSeconds() {
        val clock = AtomicLong(50_000L)
        val cache = DiscoveryResultCache(negativeTtlMillis = 100L, clockMillis = clock::get)
        var discoveries = 0

        val first = cache.discover {
            discoveries++
            ServiceDiscovery.DiscoveryResult.Missing
        }
        clock.addAndGet(99L)
        val cached = cache.discover {
            discoveries++
            ServiceDiscovery.DiscoveryResult.Missing
        }
        clock.addAndGet(1L)
        cache.discover {
            discoveries++
            ServiceDiscovery.DiscoveryResult.Missing
        }

        assertSame(first, cached)
        assertEquals(2, discoveries)
    }

    @Test
    fun invalidationForcesAHealthyRegistrationToBeCheckedAgain() {
        val cache = DiscoveryResultCache()
        var discoveries = 0
        val first = cache.discover {
            discoveries++
            healthyServer(4096)
        }

        cache.invalidate()
        val second = cache.discover {
            discoveries++
            healthyServer(4097)
        }

        assertTrue(first !== second)
        assertEquals(2, discoveries)
    }

    private fun healthyServer(port: Int): ServiceDiscovery.DiscoveryResult.Registered =
        ServiceDiscovery.DiscoveryResult.Registered(
            ServiceDiscovery.SharedServer("127.0.0.1", port, null),
            OpenCodeHealthResult.Healthy(OpenCodeServerInfo("2.2.0"))
        )
}
