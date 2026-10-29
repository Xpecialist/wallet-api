package com.example.wallet.security;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A small bounded memory of (username, client IP) pairs that recently logged in successfully. {@link RateLimitFilter}
 * uses it so the per-username failure limit (the defence against guessing spread over many addresses) never blocks an
 * address the real user has already logged in from; only addresses never seen succeeding for that name can be held back.
 * <p>
 * Entries are ordered by the time of their last success. An entry expires {@code ttl} after that success, and when more
 * than {@code maxKeys} are stored the one with the oldest success is dropped (a forgotten pair is only treated as a new
 * address: it costs the user a possible wait during an attack, never security). Pairs are added only after a
 * successful authentication, so an attacker cannot fill the memory without owning accounts. Thread-safe; local to the
 * JVM like the limiters.
 */
public final class KnownLogins {
    private final long ttlNanos;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    /** Insertion order = order of last success: the eldest entry is the oldest success. */
    private final Map<String, Long> lastSuccess = new LinkedHashMap<>();

    /**
     * Creates the memory.
     *
     * @param ttl     how long a success keeps the pair known (positive)
     * @param maxKeys most pairs kept at the same time (at least 1)
     */
    public KnownLogins(Duration ttl, int maxKeys) {
        this(ttl, maxKeys, System::nanoTime);
    }

    /** Same, with an injectable monotonic clock in nanoseconds (for tests). */
    KnownLogins(Duration ttl, int maxKeys, LongSupplier nanoClock) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be positive");
        if (maxKeys < 1) throw new IllegalArgumentException("maxKeys must be at least 1");
        this.ttlNanos = ttl.toNanos();
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
    }

    /**
     * @param key the pair key
     * @return true if the pair authenticated successfully within the last {@code ttl}
     */
    public synchronized boolean contains(String key) {
        long now = nanoClock.getAsLong();
        evictExpired(now);
        return lastSuccess.containsKey(key);
    }

    /**
     * Remembers a successful login (or renews the entry).
     *
     * @param key the pair key
     */
    public synchronized void record(String key) {
        long now = nanoClock.getAsLong();
        evictExpired(now);
        lastSuccess.remove(key);
        lastSuccess.put(key, now);
        Iterator<String> it = lastSuccess.keySet().iterator();
        while (lastSuccess.size() > maxKeys && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /** @return the number of pairs currently remembered */
    public synchronized int size() { return lastSuccess.size(); }

    /** Forgets everything (test helper). */
    public synchronized void clear() { lastSuccess.clear(); }

    private void evictExpired(long now) {
        Iterator<Long> it = lastSuccess.values().iterator();
        while (it.hasNext()) {
            if (now - it.next() < ttlNanos) return;
            it.remove();
        }
    }
}
