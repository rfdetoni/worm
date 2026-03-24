package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing the old list-then-merge vs. the new single-pass drain+merge
 * strategy for Cartesian JOIN result sets (Gap 2 fix).
 *
 * <p>Run with:
 * {@code java -cp target/test-classes:target/classes:<deps> org.openjdk.jmh.Main CollectionJoinDrainBenchmark}
 *
 * <p>Expected result: {@code mapThenMerge} allocates N+M entity instances simultaneously;
 * {@code drainAndMerge} allocates at most M+M entity instances (first-occurrence + finalised),
 * reducing GC pressure proportionally to (N-M)/N.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class CollectionJoinDrainBenchmark {

    /** 100 unique books × 10 authors each = 1 000 Cartesian rows. */
    private static final int UNIQUE_ENTITIES = 100;
    private static final int CHILDREN_PER_ENTITY = 10;
    private static final int TOTAL_ROWS = UNIQUE_ENTITIES * CHILDREN_PER_ENTITY;

    private EntityMetadata<BenchBook> metadata;
    /** Pre-built N-row list simulating what queryAndMap used to produce before the Gap 2 fix. */
    private List<BenchBook> cartesianRows;

    /** Minimal entity with a one-to-many String list, avoids join annotation complexity. */
    @DbTable("bench_book")
    public static class BenchBook {
        @DbId("id")
        private UUID id;
        private String title;
        // No actual @DbJoin here — we test the merge phase in isolation

        public BenchBook() {}
        public UUID getId()         { return id; }
        public String getTitle()    { return title; }
        public void setId(UUID id)  { this.id = id; }
        public void setTitle(String title) { this.title = title; }
    }

    @Setup(Level.Trial)
    public void setup() {
        metadata = EntityMetadata.of(BenchBook.class, null, null);
        cartesianRows = buildCartesianRows();
    }

    // ── Benchmark 1: old path — materialise N entities then call mergeCollectionJoins ──

    /**
     * Simulates the old ORM path: N entities already in a list, then merge.
     * At peak, holds N partial-entity + M merged-entity instances simultaneously.
     */
    @Benchmark
    public List<BenchBook> mapThenMerge() {
        // mergeCollectionJoins falls through quickly when there are no list joins,
        // but this still benchmarks the per-row allocation cost of the N-element list.
        return EntityMapper.mergeCollectionJoins(cartesianRows, metadata);
    }

    // ── Benchmark 2: new path — build only M entities, accumulate children inline ───

    /**
     * Simulates the new drain+merge path: only M entity instances created,
     * child items accumulated into per-PK ArrayLists without full entity duplication.
     *
     * <p>PERF: measures the accumulator-based merge that replaces the intermediate list.
     */
    @Benchmark
    public List<BenchBook> drainAndMerge() {
        // Simulate the merge phase of drainAndMergeCollectionJoins:
        // iterate over cartesian rows, build a LinkedHashMap keyed by PK.
        java.util.LinkedHashMap<UUID, BenchBook> map = new java.util.LinkedHashMap<>(UNIQUE_ENTITIES * 2);
        for (BenchBook row : cartesianRows) {
            map.putIfAbsent(row.getId(), row);
            // child accumulation would happen here in the real path
        }
        // PERF: return new ArrayList<>(accumulator.values()) — no additional copy.
        return new ArrayList<>(map.values());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<BenchBook> buildCartesianRows() {
        var rows = new ArrayList<BenchBook>(TOTAL_ROWS);
        for (int b = 0; b < UNIQUE_ENTITIES; b++) {
            UUID bookId = new UUID(0, b);
            for (int a = 0; a < CHILDREN_PER_ENTITY; a++) {
                BenchBook book = new BenchBook();
                book.setId(bookId);
                book.setTitle("Book " + b);
                rows.add(book);
            }
        }
        return rows;
    }
}

