package com.github.rfdetoni.worm.dsl;

import com.github.rfdetoni.worm.Worm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WormDslSqlTest {

    @BeforeEach
    void resetCache() {
        QueryPlanCache.clear();
    }

    @Test
    void selectFrom_where_order_limit_rendersDeterministicSqlAndParams() {
        WUserPath u = WUserPath.user;

        var query = Worm.selectFrom(u)
                .where(u.name.eq("Alice").and(u.email.endsWith("@company.com")))
                .orderBy(u.createdAt.desc())
                .limit(20);

        assertEquals(
                "SELECT u.* FROM users u WHERE (u.name = ? AND u.email LIKE ?) ORDER BY u.created_at DESC LIMIT ?",
                query.sql()
        );
        assertEquals(List.of("Alice", "%@company.com", 20), query.parameters());
    }

    @Test
    void projection_withExplicitJoin_rendersSqlAndBindOrder() {
        WUserPath u = WUserPath.user;
        WOrderPath o = WOrderPath.order;

        var query = Worm.select(u.id, u.name, o.total)
                .from(u)
                .join(o).on(o.userId.eq(u.id))
                .where(u.active.isTrue())
                .orderBy(o.total.desc())
                .limit(50)
                .offset(100);

        assertEquals(
                "SELECT u.id AS c0, u.name AS c1, o.total AS c2 FROM users u INNER JOIN orders o ON o.user_id = u.id WHERE u.active = ? ORDER BY o.total DESC LIMIT ? OFFSET ?",
                query.sql()
        );
        assertEquals(List.of(true, 50, 100L), query.parameters());
    }

    @Test
    void sameShape_reusesPlanCache() {
        WUserPath u = WUserPath.user;

        Worm.selectFrom(u).where(u.name.eq("Alice")).limit(10).sql();
        Worm.selectFrom(u).where(u.name.eq("Bob")).limit(10).sql();

        assertEquals(1, QueryPlanCache.size());
        assertEquals(1, QueryPlanCache.missCount());
        assertTrue(QueryPlanCache.hitCount() >= 1);
    }

    @Test
    void queryShape_differsWhenAliasDiffers() {
        WUserPath u1 = new WUserPath("u1");
        WUserPath u2 = new WUserPath("u2");

        var q1 = Worm.selectFrom(u1).where(u1.id.eq(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        var q2 = Worm.selectFrom(u2).where(u2.id.eq(UUID.fromString("00000000-0000-0000-0000-000000000001")));

        QueryShape s1 = SqlRenderer.shapeOf(q1);
        QueryShape s2 = SqlRenderer.shapeOf(q2);

        assertNotEquals(s1, s2);
        assertNotEquals(s1.hashCode(), s2.hashCode());
    }

    @Test
    void queryShape_matchesForSameStructure() {
        WUserPath u = WUserPath.user;
        var q1 = Worm.selectFrom(u).where(u.name.eq("Alice")).orderBy(u.createdAt.desc()).limit(10);
        var q2 = Worm.selectFrom(u).where(u.name.eq("Bob")).orderBy(u.createdAt.desc()).limit(10);

        QueryShape s1 = SqlRenderer.shapeOf(q1);
        QueryShape s2 = SqlRenderer.shapeOf(q2);

        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
    }

    @Test
    void emptyIn_rendersFalsePredicate() {
        WUserPath u = WUserPath.user;
        var query = Worm.selectFrom(u).where(u.id.in(List.of()));
        assertEquals("SELECT u.* FROM users u WHERE 1 = 0", query.sql());
        assertEquals(List.of(), query.parameters());
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
