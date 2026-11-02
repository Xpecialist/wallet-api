package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class KnownLoginsTest {
    private static final long DAY = 24L * 3600 * 1_000_000_000L;
    private final AtomicLong now = new AtomicLong(5 * DAY);

    private KnownLogins known(int days, int max) {
        return new KnownLogins(Duration.ofDays(days), max, now::get);
    }

    @Test
    void remembersASuccessUntilItsTtlRunsOut() {
        KnownLogins k = known(7, 100);
        String pair = "alice\u00001.1.1.1";
        assertThat(k.contains(pair)).isFalse();
        k.record(pair);
        assertThat(k.contains(pair)).isTrue();
        assertThat(k.contains("alice\u00001.1.1.2")).as("another address").isFalse();
        assertThat(k.contains("bob\u00001.1.1.1")).as("another user").isFalse();
        now.addAndGet(7 * DAY - 1);
        assertThat(k.contains(pair)).isTrue();
        now.addAndGet(1);
        assertThat(k.contains(pair)).as("seven days after the success").isFalse();
        assertThat(k.size()).as("expired entries are dropped").isZero();
    }

    @Test
    void aNewSuccessRenewsTheEntry() {
        KnownLogins k = known(7, 100);
        k.record("a");
        now.addAndGet(6 * DAY);
        k.record("a");
        now.addAndGet(6 * DAY);
        assertThat(k.contains("a")).isTrue();
        assertThat(k.size()).isEqualTo(1);
    }

    @Test
    void neverHoldsMoreThanTheMaximumAndDropsTheOldestSuccessFirst() {
        KnownLogins k = known(7, 3);
        for (String key : new String[] {"a", "b", "c"}) {
            k.record(key);
            now.addAndGet(1000);
        }
        k.record("a"); // renewed, so "b" is now the oldest
        now.addAndGet(1000);
        k.record("d");
        assertThat(k.size()).isEqualTo(3);
        assertThat(k.contains("b")).as("oldest success evicted").isFalse();
        assertThat(k.contains("a") && k.contains("c") && k.contains("d")).isTrue();
        for (int i = 0; i < 10_000; i++) k.record("flood" + i);
        assertThat(k.size()).as("bounded under a flood").isEqualTo(3);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new KnownLogins(Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnownLogins(Duration.ofDays(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnownLogins(null, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearForgetsEverything() {
        KnownLogins k = known(7, 10);
        k.record("a");
        k.clear();
        assertThat(k.contains("a")).isFalse();
    }
}
