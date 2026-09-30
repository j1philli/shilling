package finance.shilling.shared.data.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectBackoffTest {
    @Test
    fun doublesUpToTheCapWhenNobodyWakesIt() = runBlocking {
        val backoff = ReconnectBackoff(initialMs = 10L, maxMs = 50L)
        val delays = mutableListOf<Long>()
        repeat(5) {
            delays += backoff.currentMs
            assertFalse(backoff.await())
        }
        assertEquals(listOf(10L, 20L, 40L, 50L, 50L), delays)
    }

    @Test
    fun wakeEndsTheWaitEarlyAndResetsTheSchedule() = runBlocking {
        val backoff = ReconnectBackoff(initialMs = 10L, maxMs = 400L)
        // Push the delay to the cap so a non-woken await would clearly take longer than the wake.
        repeat(6) { backoff.await() }
        assertEquals(400L, backoff.currentMs)

        launch {
            delay(20L)
            backoff.wake()
        }
        val start = System.currentTimeMillis()
        assertTrue(backoff.await())
        assertTrue(System.currentTimeMillis() - start < 300L, "wake() should end the wait early")
        assertEquals(10L, backoff.currentMs)
    }

    @Test
    fun wakeBeforeAwaitIsNotRemembered() = runBlocking {
        val backoff = ReconnectBackoff(initialMs = 10L, maxMs = 50L)
        backoff.wake()
        assertFalse(backoff.await())
        assertEquals(20L, backoff.currentMs)
    }

    @Test
    fun resetRestartsTheSchedule() = runBlocking {
        val backoff = ReconnectBackoff(initialMs = 10L, maxMs = 50L)
        repeat(3) { backoff.await() }
        backoff.reset()
        assertEquals(10L, backoff.currentMs)
    }
}
