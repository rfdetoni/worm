package com.github.rfdetoni.worm.dsl;

import com.github.rfdetoni.worm.Worm;
import org.openjdk.jmh.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JMH micro-bench for DSL query-shape compilation, SQL rendering and bind collection.
 *
 * <p>Run manually with:
 * {@code java -cp target/test-classes:target/classes:<deps> org.openjdk.jmh.Main WormDslQueryShapeJmhBenchmark}
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class WormDslQueryShapeJmhBenchmark {

    private static final WUserPath U = WUserPath.user;
    private static final WOrderPath O = WOrderPath.order;

    @Setup(Level.Iteration)
    public void setup() {
        QueryPlanCache.clear();
    }

    @Benchmark
    public String selectById_coldShape() {
        return Worm.selectFrom(U)
                .where(U.id.eq(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .sql();
    }

    @Benchmark
    public String filteredSelect_hotShape() {
        var q1 = Worm.selectFrom(U)
                .where(U.name.eq("Alice").and(U.email.endsWith("@company.com")))
                .orderBy(U.createdAt.desc())
                .limit(20);
        q1.sql();
        q1.parameters();

        return Worm.selectFrom(U)
                .where(U.name.eq("Bob").and(U.email.endsWith("@company.com")))
                .orderBy(U.createdAt.desc())
                .limit(20)
                .sql();
    }

    @Benchmark
    public String paginatedSelect_hotShape() {
        var q = Worm.selectFrom(U)
                .where(U.active.isTrue())
                .orderBy(U.createdAt.desc())
                .limit(50)
                .offset(150);
        q.sql();
        q.parameters();
        return q.sql();
    }

    @Benchmark
    public String joinQuery_hotShape() {
        var q = Worm.select(U.id, U.name, O.total)
                .from(U)
                .join(O).on(O.userId.eq(U.id))
                .where(U.active.isTrue())
                .orderBy(O.total.desc())
                .limit(100);
        q.sql();
        q.parameters();
        return q.sql();
    }

    @Benchmark
    public String projectionQuery_hotShape() {
        var q = Worm.select(U.id, U.name, U.email)
                .from(U)
                .where(U.active.isTrue().and(U.name.in(List.of("Alice", "Bob", "Carol"))))
                .orderBy(U.name.asc())
                .limit(30);
        q.sql();
        q.parameters();
        return q.sql();
    }

    @Benchmark
    public double mixedRepeatedQueryShape_hitRatio() {
        Worm.selectFrom(U).where(U.id.eq(UUID.fromString("00000000-0000-0000-0000-000000000001"))).sql();
        Worm.selectFrom(U).where(U.id.eq(UUID.fromString("00000000-0000-0000-0000-000000000002"))).sql();
        Worm.select(U.id, O.total).from(U).join(O).on(O.userId.eq(U.id)).where(U.active.isTrue()).sql();
        Worm.select(U.id, O.total).from(U).join(O).on(O.userId.eq(U.id)).where(U.active.isFalse()).sql();
        return QueryPlanCache.hitRatio();
    }

    static final class User {
        UUID id;
        String name;
        String email;
        Instant createdAt;
        Boolean active;
    }

    static final class Order {
        UUID id;
        UUID userId;
        Long total;
    }

    static final class WUserPath extends EntityPath<User> {
        static final WUserPath user = new WUserPath("u");
        final UuidPath id = uuid("id");
        final StringPath name = string("name");
        final StringPath email = string("email");
        final DateTimePath<Instant> createdAt = dateTime("created_at", Instant.class);
        final BooleanPath active = bool("active");

        WUserPath(String alias) {
            super(User.class, "users", alias);
        }
    }

    static final class WOrderPath extends EntityPath<Order> {
        static final WOrderPath order = new WOrderPath("o");
        final UuidPath id = uuid("id");
        final UuidPath userId = uuid("user_id");
        final NumberPath<Long> total = number("total", Long.class);

        WOrderPath(String alias) {
            super(Order.class, "orders", alias);
        }
    }
}

