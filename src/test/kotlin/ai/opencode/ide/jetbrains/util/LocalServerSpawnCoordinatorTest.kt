package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalServerSpawnCoordinatorTest {
    @Test
    fun serializesConcurrentSpawnDecisionsForTheSamePort() {
        val port = 49_991
        val executor = Executors.newFixedThreadPool(2)
        val firstInsideLock = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val concurrentActions = AtomicInteger()
        val maximumConcurrentActions = AtomicInteger()

        fun updateMaximum(value: Int) {
            maximumConcurrentActions.updateAndGet { previous -> maxOf(previous, value) }
        }

        try {
            val first = executor.submit {
                LocalServerSpawnCoordinator.withPortLock(port) {
                    updateMaximum(concurrentActions.incrementAndGet())
                    firstInsideLock.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                    concurrentActions.decrementAndGet()
                }
            }
            assertTrue(firstInsideLock.await(5, TimeUnit.SECONDS))

            val second = executor.submit {
                LocalServerSpawnCoordinator.withPortLock(port) {
                    updateMaximum(concurrentActions.incrementAndGet())
                    concurrentActions.decrementAndGet()
                }
            }

            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)

            assertEquals(1, maximumConcurrentActions.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun aProcessCanOnlyReleaseItsOwnManagedPortReservation() {
        val port = 49_992
        val owner = Any()
        val processToken = Any()
        val otherToken = Any()

        LocalServerSpawnCoordinator.markStarted(port, owner, processToken)
        LocalServerSpawnCoordinator.release(port, owner, otherToken)
        assertTrue(LocalServerSpawnCoordinator.isManaged(port))
        assertFalse(LocalServerSpawnCoordinator.isManagedByAnother(port, owner))

        LocalServerSpawnCoordinator.release(port, owner, processToken)
        assertFalse(LocalServerSpawnCoordinator.isManaged(port))
    }
}
