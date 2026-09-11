package com.github.rfdetoni.worm.orm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedConcurrentCacheTest {

    @Test
    void keepsDynamicKeySpaceBounded() {
        BoundedConcurrentCache<Integer, String> cache = new BoundedConcurrentCache<>(8);

        for (int index = 0; index < 100; index++) {
            int key = index;
            cache.computeIfAbsent(key, value -> "sql-" + value);
        }

        assertTrue(cache.size() <= 8);
    }

    @Test
    void reusesExistingValueWithoutRecomputing() {
        BoundedConcurrentCache<String, String> cache = new BoundedConcurrentCache<>(8);
        AtomicInteger builds = new AtomicInteger();

        assertEquals("sql", cache.computeIfAbsent("shape", ignored -> {
            builds.incrementAndGet();
            return "sql";
        }));
        assertEquals("sql", cache.computeIfAbsent("shape", ignored -> {
            builds.incrementAndGet();
            return "other";
        }));

        assertEquals(1, builds.get());
    }
}
