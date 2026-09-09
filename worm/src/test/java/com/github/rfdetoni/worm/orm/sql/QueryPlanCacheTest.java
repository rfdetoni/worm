package com.github.rfdetoni.worm.orm.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryPlanCacheTest {

    @BeforeEach
    void setUp() {
        QueryPlanCache.clear();
        QueryPlanCache.configureMaxEntries(3);
    }

    @AfterEach
    void tearDown() {
        QueryPlanCache.clear();
        QueryPlanCache.configureMaxEntries(4096);
    }

    @Test
    void shouldBuildSamePlanOnlyOnce() {
        QueryPlanKey key = keyForOffset(0);
        AtomicInteger builds = new AtomicInteger();

        String first = QueryPlanCache.get(key, () -> {
            builds.incrementAndGet();
            return "select 1";
        });
        String second = QueryPlanCache.get(key, () -> {
            builds.incrementAndGet();
            return "select 2";
        });

        assertEquals("select 1", first);
        assertEquals("select 1", second);
        assertEquals(1, builds.get());
        assertEquals(1, QueryPlanCache.size());
    }

    @Test
    void shouldEvictOldestPlansWhenLimitIsExceeded() {
        for (int offset = 0; offset < 4; offset++) {
            int currentOffset = offset;
            QueryPlanCache.get(keyForOffset(offset), () -> "select offset " + currentOffset);
        }

        assertEquals(3, QueryPlanCache.maxEntries());
        assertTrue(QueryPlanCache.size() <= QueryPlanCache.maxEntries());

        AtomicInteger rebuilt = new AtomicInteger();
        QueryPlanCache.get(keyForOffset(0), () -> {
            rebuilt.incrementAndGet();
            return "rebuilt";
        });
        assertEquals(1, rebuilt.get(), "the oldest offset plan should have been evicted");
        assertTrue(QueryPlanCache.size() <= QueryPlanCache.maxEntries());
    }

    private QueryPlanKey keyForOffset(int offset) {
        return new QueryPlanKey(
                String.class,
                "id = ?",
                0,
                "id ASC",
                20,
                offset,
                true,
                false,
                false,
                "",
                "select"
        );
    }
}
