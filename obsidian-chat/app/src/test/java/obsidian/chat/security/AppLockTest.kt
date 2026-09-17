package obsidian.chat.security

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import obsidian.chat.data.KeyValueStore
import obsidian.chat.data.SecureStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PIN lock had no tests, which is how a 120,000-round PBKDF2 hash came to run on the main
 * thread: it froze the app for ~33 seconds and Android killed it with "isn't responding". These
 * cover the behaviour and, most importantly, the thread the hashing happens on.
 */
class AppLockTest {

    /** Stands in for the Keystore-sealed store, which needs Android and cannot run on the JVM. */
    private class FakeStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun contains(name: String) = values.containsKey(name)
        override fun getString(name: String) = values[name]
        override fun putString(name: String, value: String) { values[name] = value }
        override fun remove(name: String) { values.remove(name) }
    }

    @Test
    fun `correct pin verifies and wrong pin does not`() = runBlocking {
        val store = FakeStore()
        AppLock.setPin(store, "2580")
        assertTrue(AppLock.verify(store, "2580"))
        assertFalse(AppLock.verify(store, "2581"))
    }

    @Test
    fun `no pin set means nothing verifies`() = runBlocking {
        val store = FakeStore()
        assertFalse(AppLock.isSet(store))
        assertFalse(AppLock.verify(store, "2580"))
    }

    @Test
    fun `clearing the pin removes both hash and salt`() = runBlocking {
        val store = FakeStore()
        AppLock.setPin(store, "2580")
        assertTrue(AppLock.isSet(store))
        AppLock.clear(store)
        assertFalse(AppLock.isSet(store))
        assertFalse(AppLock.verify(store, "2580"))
    }

    @Test
    fun `the same pin hashes differently each time, so the salt is doing its job`() = runBlocking {
        val a = FakeStore().also { AppLock.setPin(it, "2580") }
        val b = FakeStore().also { AppLock.setPin(it, "2580") }
        assertNotEquals(a.getString(SecureStore.LOCK_HASH), b.getString(SecureStore.LOCK_HASH))
    }

    @Test
    fun `a pin must be four to sixteen digits`() = runBlocking {
        val store = FakeStore()
        for (bad in listOf("123", "12345678901234567", "abcd", "12a4", "")) {
            runCatching { AppLock.setPin(store, bad) }
                .onSuccess { throw AssertionError("accepted a bad PIN: '$bad'") }
        }
        AppLock.setPin(store, "1234")
        assertTrue(AppLock.isSet(store))
    }

    /**
     * The regression guard for the ANR: the stretching must not run on the thread that asked for
     * it. Two earlier attempts at this test were wrong in instructive ways. Counting how much work
     * the calling thread got through measured clock granularity as much as thread behaviour. Then
     * recording which thread wrote the hash measured nothing at all, because withContext resumes on
     * the caller's thread, so the write happens there however the hashing was scheduled. The only
     * honest way to see where the work ran is to hand AppLock the dispatcher and watch it.
     */
    @Test
    fun `hashing runs off the calling thread`() {
        val mainLike = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hash-worker") }
        val ranOn = AtomicReference<String>()
        val original = AppLock.hashingDispatcher
        // A dispatcher that notes the thread it actually executes the hashing on.
        AppLock.hashingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                worker.execute {
                    ranOn.compareAndSet(null, Thread.currentThread().name)
                    block.run()
                }
            }
        }
        try {
            runBlocking {
                val store = FakeStore()
                withContext(mainLike.asCoroutineDispatcher()) {
                    assertTrue("test setup: should start on the fake main thread", onFakeMain())
                    AppLock.setPin(store, "2580")
                    assertTrue("should resume on the calling thread", onFakeMain())
                    assertTrue(AppLock.verify(store, "2580"))
                }
            }
            val hashedOn = ranOn.get()
            assertNotNull("the hashing dispatcher was never used - it ran inline", hashedOn)
            assertFalse(
                "PBKDF2 ran on the caller's thread ($hashedOn) - this is the ANR",
                hashedOn!!.startsWith("fake-main"),
            )
        } finally {
            AppLock.hashingDispatcher = original
            mainLike.shutdown()
            worker.shutdown()
        }
    }

    private fun onFakeMain() = Thread.currentThread().name.startsWith("fake-main")

    @Test
    fun `a wrong pin costs about the same as a right one, so timing leaks nothing`() = runBlocking {
        val store = FakeStore()
        AppLock.setPin(store, "2580")
        AppLock.verify(store, "2580") // warm up, so JIT does not skew the first measurement
        val right = measureTimeMillis { AppLock.verify(store, "2580") }
        val wrong = measureTimeMillis { AppLock.verify(store, "9999") }
        val slower = maxOf(right, wrong).toDouble()
        val faster = minOf(right, wrong).coerceAtLeast(1).toDouble()
        assertTrue("right=${right}ms wrong=${wrong}ms differ too much", slower / faster < 3.0)
    }
}
