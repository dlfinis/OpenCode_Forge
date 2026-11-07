package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ServiceDiscoveryDeadlineTest {
    @Test
    fun retriesAnIncompleteRegistrationAfterTheServiceFlushesItsFile() {
        val file = Files.createTempDirectory("service-discovery-retry").resolve("service.json").toFile()
        val clock = AtomicLong(0L)
        var sleeps = 0
        var healthBudget = 0

        val result = ServiceDiscovery.discoverSharedServerWithDeadline(
            candidates = listOf(file),
            deadlineMillis = 1000L,
            maxAttempts = 5,
            retryIntervalMillis = 200L,
            nowNanos = clock::get,
            sleep = { delay ->
                sleeps++
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(delay))
                file.writeText("""{"url":"http://127.0.0.1:49374","pid":17}""")
            },
            healthCheck = { host, port, _, timeoutMs ->
                healthBudget = timeoutMs
                assertEquals("127.0.0.1", host)
                assertEquals(49374, port)
                OpenCodeHealthResult.Healthy(OpenCodeServerInfo("2.2.0"))
            }
        )

        assertTrue(result is ServiceDiscovery.DiscoveryResult.Registered)
        assertEquals(1, sleeps)
        assertEquals(800, healthBudget)
        file.delete()
    }

    @Test
    fun retriesAndHealthChecksShareOneGlobalDeadline() {
        val missingFile = File("/path/that/does/not/exist/service.json")
        val clock = AtomicLong(0L)
        val delays = mutableListOf<Long>()
        val result = ServiceDiscovery.discoverSharedServerWithDeadline(
            candidates = listOf(missingFile),
            deadlineMillis = 250L,
            maxAttempts = 5,
            retryIntervalMillis = 200L,
            nowNanos = clock::get,
            sleep = { delay ->
                delays.add(delay)
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(delay))
            },
            healthCheck = { _, _, _, _ -> error("Health check must not run without a registration") }
        )

        assertEquals(ServiceDiscovery.DiscoveryResult.Missing, result)
        assertEquals(listOf(200L, 50L), delays)
        assertEquals(TimeUnit.MILLISECONDS.toNanos(250L), clock.get())
    }

    @Test
    fun passesOnlyTheRemainingDeadlineToEachRegistrationHealthCheck() {
        val directory = Files.createTempDirectory("service-discovery-health-budget")
        val first = directory.resolve("a.json").toFile().apply {
            writeText("""{"url":"http://127.0.0.1:4101","pid":1}""")
        }
        val second = directory.resolve("b.json").toFile().apply {
            writeText("""{"url":"http://127.0.0.1:4102","pid":2}""")
        }
        val clock = AtomicLong(0L)
        val budgets = mutableListOf<Int>()

        ServiceDiscovery.discoverSharedServerWithDeadline(
            candidates = listOf(first, second),
            deadlineMillis = 1000L,
            nowNanos = clock::get,
            sleep = {},
            healthCheck = { _, _, _, timeoutMs ->
                budgets.add(timeoutMs)
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(700L))
                OpenCodeHealthResult.NotResponding("test timeout")
            }
        )

        assertEquals(listOf(1000, 300), budgets)
        listOf(first, second).forEach(File::delete)
    }
}
