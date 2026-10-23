package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketLimiterTest {
    private static final long SEC = 1_000_000_000L;
    private final AtomicLong now = new AtomicLong(1_000 * SEC);

    private TokenBucketLimiter limiter(int capacity, Duration window, int maxKeys) {
        return new TokenBucketLimiter(capacity, window, maxKeys, now::get);
    }

    @Test
    void allowsTheBurstThenRejectsWithRetryAfter() {
        TokenBucketLimiter l = limiter(5, Duration.ofMinutes(1), 100);
        for (int i = 0; i < 5; i++) assertThat(l.tryAcquire("a")).isZero();
        assertThat(l.tryAcquire("a")).isEqualTo(12); // one token every 12 s
    }

    @Test
    void retryAfterShrinksAsTimePasses() {
        TokenBucketLimiter l = limiter(5, Duration.ofMinutes(1), 100);
        for (int i = 0; i < 5; i++) l.tryAcquire("a");
        now.addAndGet(5 * SEC);
        assertThat(l.tryAcquire("a")).isEqualTo(7);
    }

    @Test
    void refillsContinuouslyAndNeverLocksOutPermanently() {
        TokenBucketLimiter l = limiter(5, Duration.ofMinutes(1), 100);
        for (int i = 0; i < 5; i++) l.tryAcquire("a");
        assertThat(l.tryAcquire("a")).isPositive();
        now.addAndGet(12 * SEC);
        assertThat(l.tryAcquire("a")).as("one token after 12 s").isZero();
        assertThat(l.tryAcquire("a")).isPositive();
        now.addAndGet(60 * SEC);
        for (int i = 0; i < 5; i++) assertThat(l.tryAcquire("a")).as("full again after a window").isZero();
        assertThat(l.tryAcquire("a")).isPositive();
    }

    @Test
    void rejectedAttemptsDoNotPushTheRefillAway() {
        TokenBucketLimiter l = limiter(1, Duration.ofSeconds(10), 100);
        assertThat(l.tryAcquire("a")).isZero();
        for (int i = 0; i < 9; i++) {
            now.addAndGet(SEC);
            assertThat(l.tryAcquire("a")).isPositive();
        }
        now.addAndGet(SEC);
        assertThat(l.tryAcquire("a")).as("10 s after the first request").isZero();
    }

    @Test
    void keysAreIndependent() {
        TokenBucketLimiter l = limiter(1, Duration.ofMinutes(1), 100);
        assertThat(l.tryAcquire("a")).isZero();
        assertThat(l.tryAcquire("a")).isPositive();
        assertThat(l.tryAcquire("b")).isZero();
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        TokenBucketLimiter l = limiter(1000, Duration.ofSeconds(1), 100);
        for (int i = 0; i < 1000; i++) l.tryAcquire("a");
        assertThat(l.tryAcquire("a")).isEqualTo(1);
    }

    @Test
    void idleKeysExpireAndAreForgotten() {
        TokenBucketLimiter l = limiter(5, Duration.ofMinutes(1), 100);
        l.tryAcquire("a");
        l.tryAcquire("b");
        assertThat(l.size()).isEqualTo(2);
        now.addAndGet(61 * SEC);
        l.tryAcquire("c");
        assertThat(l.size()).as("a and b were idle for a full window").isEqualTo(1);
    }

    @Test
    void sizeIsBoundedAndTheLeastRecentlyUsedKeyIsEvicted() {
        TokenBucketLimiter l = limiter(1, Duration.ofHours(1), 3);
        l.tryAcquire("a");
        l.tryAcquire("b");
        l.tryAcquire("c");
        l.tryAcquire("a"); // a is now the most recently used (and rejected: capacity 1)
        l.tryAcquire("d"); // evicts b, the least recently used
        assertThat(l.size()).isEqualTo(3);
        assertThat(l.tryAcquire("a")).as("a kept its state").isPositive();
        assertThat(l.tryAcquire("b")).as("b was evicted, so it starts fresh").isZero();
        for (int i = 0; i < 1000; i++) l.tryAcquire("flood" + i);
        assertThat(l.size()).isEqualTo(3);
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> limiter(0, Duration.ofMinutes(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, Duration.ofSeconds(-1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, Duration.ofMinutes(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverAllowsMoreThanCapacityUnderConcurrency() throws Exception {
        TokenBucketLimiter l = new TokenBucketLimiter(50, Duration.ofHours(1), 10);
        AtomicLong allowed = new AtomicLong();
        Thread[] ts = new Thread[8];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) if (l.tryAcquire("k") == 0) allowed.incrementAndGet();
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        assertThat(allowed.get()).isEqualTo(50);
    }
}
