package app.ledgerly.capture

import java.security.MessageDigest

/**
 * De-bounces notification updates. Apps re-post the same notification (progress, re-sorts, Gmail
 * re-syncs); we only want to capture each distinct text once.
 *
 * - Same notification key + same text within [windowMillis] -> drop.
 * - Same key, new text -> accept (the app updated the alert with new content).
 * - Same text under a different key within [crossKeyWindowMillis] -> drop (re-post with a new key).
 *
 * Pure Kotlin and thread-safe; unit-tested in NotificationDebouncerTest.
 */
class NotificationDebouncer(
    private val windowMillis: Long = 10 * 60_000L,
    private val crossKeyWindowMillis: Long = 2 * 60_000L,
    private val maxEntries: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Seen(val hash: String, val at: Long)

    private val byKey = object : LinkedHashMap<String, Seen>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?): Boolean = size > maxEntries
    }
    private val byHash = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun shouldProcess(key: String, text: String): Boolean {
        val now = clock()
        val hash = hashText(text)
        val prev = byKey[key]
        if (prev != null && prev.hash == hash && now - prev.at < windowMillis) return false
        val seenHashAt = byHash[hash]
        if (prev?.hash != hash && seenHashAt != null && now - seenHashAt < crossKeyWindowMillis) {
            byKey[key] = Seen(hash, now)
            return false
        }
        byKey[key] = Seen(hash, now)
        byHash[hash] = now
        return true
    }

    @Synchronized
    fun clear() {
        byKey.clear(); byHash.clear()
    }

    companion object {
        /** Whitespace-insensitive SHA-256 of the text, hex-encoded. */
        fun hashText(text: String): String {
            val normalized = text.trim().replace(Regex("\\s+"), " ")
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
