package com.example.wallet.security;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * In-memory token-bucket rate limiter with one bucket per key (a client IP, a username, ...).
 * <p>
 * Each bucket holds up to {@code capacity} tokens and refills continuously at {@code capacity / window} tokens per
 * unit of time; one request costs one token. So {@code capacity=5, window=1m} allows a burst of 5 and then one request
 * every 12 seconds, and a blocked client is let in again automatically (no permanent lockout).
 * <p>
 * Memory is bounded two ways. A bucket that has been idle for a full window is back at full capacity, so it is
 * identical to a key that was never seen and is dropped. And the map never holds more than {@code maxKeys} entries: the
 * least recently used key is evicted first. Keys come from attackers (usernames, spoofed addresses), so without a
 * bound the map would be a memory-exhaustion vector. Eviction can only make a flooded limiter more lenient for a key
 * that was not used recently; it can never block anybody.
 * <p>
 * Thread-safe (one lock per limiter; the critical section is a few arithmetic operations). The state is local to the
 * JVM: with several application instances each one counts on its own.
 */
public final class TokenBucketLimiter {
    /** The level is kept in integer units (1 token = windowNanos units) so refill arithmetic is exact. */
    private static final class Bucket {
        long level;
        long lastNanos;

        Bucket(long level, long lastNanos) {
            this.level = level;
            this.lastNanos = lastNanos;
        }
    }

    private final int capacity;
    private final long windowNanos;
    private final long maxLevel;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets;

    /**
     * Creates a limiter.
     *
     * @param capacity burst size and number of requests allowed per window (at least 1)
     * @param window   time to refill an empty bucket completely (positive)
     * @param maxKeys  most keys tracked at the same time (at least 1)
     */
    public TokenBucketLimiter(int capacity, Duration window, int maxKeys) {
        this(capacity, window, maxKeys, System::nanoTime);
    }

    /** Same, with an injectable monotonic clock in nanoseconds (for tests). */
    TokenBucketLimiter(int capacity, Duration window, int maxKeys, LongSupplier nanoClock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least 1");
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (maxKeys < 1) throw new IllegalArgumentException("maxKeys must be at least 1");
        this.capacity = capacity;
        this.windowNanos = window.toNanos();
        this.maxLevel = Math.multiplyExact((long) capacity, windowNanos);
        this.nanoClock = nanoClock;
        // access order: the eldest entry is the least recently used one
        this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /**
     * Takes one token for {@code key} if there is one.
     *
     * @param key the bucket key (already normalized by the caller)
     * @return 0 if the request is allowed, otherwise the number of seconds (at least 1) until a token is available
     */
    public synchronized long tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        evictIdle(now);
        Bucket b = buckets.get(key);
        if (b == null) {
            b = new Bucket(maxLevel, now);
            buckets.put(key, b);
        } else {
            // each elapsed nanosecond adds "capacity" units; more than one window refills everything anyway
            long elapsed = Math.min(windowNanos, Math.max(0, now - b.lastNanos));
            b.level = Math.min(maxLevel, b.level + elapsed * capacity);
            b.lastNanos = now;
        }
        if (b.level >= windowNanos) {
            b.level -= windowNanos;
            return 0;
        }
        long nanosToWait = (windowNanos - b.level + capacity - 1) / capacity;
        return Math.max(1, (nanosToWait + 999_999_999L) / 1_000_000_000L);
    }

    /** @return the number of keys currently tracked */
    public synchronized int size() {
        return buckets.size();
    }

    /** Forgets every key (test helper). */
    public synchronized void clear() {
        buckets.clear();
    }

    /** Drops buckets idle for a full window; the map is in LRU order, so stop at the first live one. */
    private void evictIdle(long now) {
        Iterator<Bucket> it = buckets.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().lastNanos < windowNanos) return;
            it.remove();
        }
    }
}
