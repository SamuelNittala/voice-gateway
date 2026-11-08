package dev.nittala.voicegateway.storage

import dev.nittala.voicegateway.core.api.StoredCookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (A7): the login cookie in one secret entry; cleared by put(null) (401 and logout). */
class PersistentCookieStoreTest {
    private val secrets = FakeSecretStore()
    private val store = PersistentCookieStore(secrets)
    private val cookie = StoredCookie("test-cookie-0001", 1_700_000_000_000L)

    @Test
    fun roundTripsThroughOneEntry() {
        store.put(cookie)
        assertEquals(cookie, store.get())
        assertEquals(setOf(PersistentCookieStore.ENTRY), secrets.entries.keys)
        assertEquals("1700000000000|test-cookie-0001", secrets.entries.getValue(PersistentCookieStore.ENTRY))
    }

    @Test
    fun putNullClears() {
        store.put(cookie)
        store.put(null)
        assertNull(store.get())
        assertTrue(secrets.entries.isEmpty())
    }

    @Test
    fun replaces() {
        store.put(cookie)
        store.put(StoredCookie("test-cookie-0002", 1L))
        assertEquals(StoredCookie("test-cookie-0002", 1L), store.get())
    }

    @Test
    fun absentIsNull() = assertNull(store.get())

    @Test
    fun malformedEntriesReadAsAbsent() {
        listOf("", "|", "123|", "abc|value", "123", "|value", "123|a b", "123|a;b", "123|a,b", "123|a\"b", "123|a\\b",
            "123|café", "123|a\nb").forEach { raw ->
            secrets.entries[PersistentCookieStore.ENTRY] = raw
            assertNull(raw, store.get())
        }
    }

    @Test
    fun keepsAValueThatContainsTheSeparator() {
        store.put(StoredCookie("a|b", 5L))
        assertEquals(StoredCookie("a|b", 5L), store.get())
    }
}
