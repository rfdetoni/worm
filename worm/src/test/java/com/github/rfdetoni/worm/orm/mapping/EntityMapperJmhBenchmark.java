package com.github.rfdetoni.worm.orm.mapping;

import com.github.rfdetoni.worm.annotation.mapping.DbId;
import com.github.rfdetoni.worm.annotation.mapping.DbTable;
import com.github.rfdetoni.worm.orm.registry.EntityMetadata;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Minimal JMH harness for mapper phase-2 construction cost.
 *
 * <p>Run manually with: {@code java -cp target/test-classes:target/classes:<deps> org.openjdk.jmh.Main EntityMapperJmhBenchmark}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class EntityMapperJmhBenchmark {

    private EntityMetadata<BenchEntity> metadata;
    private Object[] raw;

    @Setup(Level.Trial)
    public void setup() {
        metadata = EntityMetadata.of(BenchEntity.class, null, null);
        raw = new Object[]{7L, "ana", 31};
    }

    @Benchmark
    public BenchEntity mapFromRawHotPath() throws Throwable {
        return EntityMapper.mapFromRaw(raw, metadata);
    }

    @DbTable("bench_entity")
    public static class BenchEntity {
        @DbId("id")
        private Long id;
        private String name;
        private int age;

        public BenchEntity() {
        }
    }
}

