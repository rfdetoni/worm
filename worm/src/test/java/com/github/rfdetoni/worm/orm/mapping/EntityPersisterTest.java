package com.github.rfdetoni.worm.orm.mapping;

import com.github.rfdetoni.worm.annotation.audit.Active;
import com.github.rfdetoni.worm.annotation.audit.CreatedAt;
import com.github.rfdetoni.worm.annotation.audit.UpdatedAt;
import com.github.rfdetoni.worm.annotation.mapping.DbColumn;
import com.github.rfdetoni.worm.annotation.mapping.DbId;
import com.github.rfdetoni.worm.annotation.mapping.DbTable;
import com.github.rfdetoni.worm.annotation.mapping.DbVersion;
import com.github.rfdetoni.worm.api.iBaseEntity;
import com.github.rfdetoni.worm.orm.dialect.PostgresDialect;
import com.github.rfdetoni.worm.orm.dialect.SqlDialect;
import com.github.rfdetoni.worm.orm.registry.EntityMetadata;
import com.github.rfdetoni.worm.orm.sql.WritePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class EntityPersisterTest {

    static class DialectBinderDialect implements SqlDialect {
        static int binderInvocations;

        @Override
        public String applyPagination(String sql, int limit, int offset) { return sql; }
        @Override
        public String buildUpsertSql(EntityMetadata meta) { return null; }
        @Override
        public String ilikeExpression(String column) { return column + " LIKE ?"; }
        @Override
        public String castToJson(String expression) { return expression; }
        @Override
        public String generateUuidExpression() { return "gen_random_uuid()"; }
        @Override
        public boolean supportsReturning() { return false; }
        @Override
        public String returningClause(String... columns) { return ""; }
        @Override
        public String currentTimestampExpression() { return "CURRENT_TIMESTAMP"; }

        @Override
        public ParamBinder createParamBinder(Class<?> entityClass, String sql,
                                             WritePlan.Slot[] slots,
                                             boolean hasVersion) {
            return (spec, entity) -> {
                binderInvocations++;
                return spec.param("dialect-binder");
            };
        }
    }

    enum Status { ACTIVE, INACTIVE }

    @DbTable("sample_entities")
    record SampleEntity(
            @DbId("id") Long id,
            String name,
            @DbColumn("status") Status status,
            @DbVersion Integer version
    ) {
    }

    @DbTable("json_entities")
    record JsonEntity(
            @DbId("id") Long id,
            @DbColumn(value = "payload", json = true) Map<String, Object> payload
    ) {
    }

    @DbTable("audited_entities")
    static class AuditedEntity implements iBaseEntity {
        private static boolean createdCalled;
        private static boolean updatedCalled;

        @DbId("id")
        private Long id;

        @DbColumn("name")
        private String name;

        @CreatedAt
        private Instant createdAt;

        @UpdatedAt
        private Instant updatedAt;

        @Active(defaultValue = true)
        private Boolean active;

        @DbVersion
        private Integer version;

        AuditedEntity() {
        }

        AuditedEntity(Long id, String name, Boolean active, Integer version) {
            this.id = id;
            this.name = name;
            this.active = active;
            this.version = version;
        }

        @Override
        public void created() {
            createdCalled = true;
        }

        @Override
        public void deleted() {
        }

        @Override
        public void updated() {
            updatedCalled = true;
        }
    }

    @BeforeEach
    void clearCache() {
        DialectBinderDialect.binderInvocations = 0;
    }

    @Test
    void arrayVariantsMatchListVariants() {
        EntityMetadata<SampleEntity> metadata = EntityMetadata.of(SampleEntity.class);
        SampleEntity entity = new SampleEntity(10L, "Alice", Status.ACTIVE, 3);

        assertArrayEquals(
                EntityPersister.insertValues(entity, metadata).toArray(),
                EntityPersister.insertValuesArray(entity, metadata)
        );
        assertArrayEquals(
                EntityPersister.updateValues(entity, metadata, entity.id()).toArray(),
                EntityPersister.updateValuesArray(entity, metadata, entity.id())
        );
    }

    @Test
    void compiledWritePlansBindExpectedValuesAndInvokeLifecycleHooks() {
        EntityMetadata<AuditedEntity> metadata = EntityMetadata.of(AuditedEntity.class);
        AuditedEntity entity = new AuditedEntity(42L, "Carol", null, 5);
        AuditedEntity.createdCalled = false;
        AuditedEntity.updatedCalled = false;

        JdbcClient client = mock(JdbcClient.class);
        JdbcClient.StatementSpec insertSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.StatementSpec updateSpec = mock(JdbcClient.StatementSpec.class);

        when(client.sql(metadata.insertWritePlan().sql())).thenReturn(insertSpec);
        when(client.sql(metadata.updateWritePlan().sql())).thenReturn(updateSpec);
        when(insertSpec.param(any())).thenReturn(insertSpec);
        when(updateSpec.param(any())).thenReturn(updateSpec);
        when(insertSpec.param(anyInt(), any())).thenReturn(insertSpec);
        when(updateSpec.param(anyInt(), any())).thenReturn(updateSpec);
        when(insertSpec.param(anyInt(), any(), anyInt())).thenReturn(insertSpec);
        when(updateSpec.param(anyInt(), any(), anyInt())).thenReturn(updateSpec);
        when(insertSpec.update()).thenReturn(1);
        when(updateSpec.update()).thenReturn(1);

        List<Object> insertBound = new ArrayList<>();
        List<Object> updateBound = new ArrayList<>();
        when(insertSpec.param(any())).thenAnswer(inv -> {
            insertBound.add(inv.getArgument(0));
            return insertSpec;
        });
        when(updateSpec.param(any())).thenAnswer(inv -> {
            updateBound.add(inv.getArgument(0));
            return updateSpec;
        });
        when(insertSpec.param(anyInt(), any())).thenAnswer(inv -> {
            insertBound.add(inv.getArgument(1));
            return insertSpec;
        });
        when(updateSpec.param(anyInt(), any())).thenAnswer(inv -> {
            updateBound.add(inv.getArgument(1));
            return updateSpec;
        });
        when(insertSpec.param(anyInt(), any(), anyInt())).thenAnswer(inv -> {
            insertBound.add(inv.getArgument(1));
            return insertSpec;
        });
        when(updateSpec.param(anyInt(), any(), anyInt())).thenAnswer(inv -> {
            updateBound.add(inv.getArgument(1));
            return updateSpec;
        });

        assertNotNull(metadata.insertWritePlan());
        assertNotNull(metadata.updateWritePlan());

        assertEquals(1, metadata.insertWritePlan().execute(client, entity));
        assertTrue(AuditedEntity.createdCalled);
        assertEquals(6, insertBound.size());
        assertEquals(42L, insertBound.get(0));
        assertEquals("Carol", insertBound.get(1));
        assertInstanceOf(OffsetDateTime.class, insertBound.get(2));
        assertInstanceOf(OffsetDateTime.class, insertBound.get(3));
        assertEquals(true, insertBound.get(4));
        assertEquals(5, insertBound.get(5));

        assertEquals(1, metadata.updateWritePlan().execute(client, entity));
        assertTrue(AuditedEntity.updatedCalled);
        assertEquals(5, updateBound.size());
        assertEquals("Carol", updateBound.get(0));
        assertInstanceOf(OffsetDateTime.class, updateBound.get(1));
        assertNull(updateBound.get(2));
        assertEquals(42L, updateBound.get(3));
        assertEquals(5, updateBound.get(4));

        verify(insertSpec).update();
        verify(updateSpec).update();
    }

    @Test
    void dialectProvidedParamBinderOverridesDefaultCompiledBinder() {
        EntityMetadata<SampleEntity> metadata = EntityMetadata.of(SampleEntity.class, new DialectBinderDialect(), null);
        SampleEntity entity = new SampleEntity(55L, "Dave", Status.ACTIVE, 9);

        JdbcClient client = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(client.sql(metadata.insertWritePlan().sql())).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.param(anyInt(), any())).thenReturn(spec);
        when(spec.param(anyInt(), any(), anyInt())).thenReturn(spec);
        when(spec.update()).thenReturn(1);

        List<Object> bound = new ArrayList<>();
        when(spec.param(any())).thenAnswer(inv -> {
            bound.add(inv.getArgument(0));
            return spec;
        });
        when(spec.param(anyInt(), any())).thenAnswer(inv -> {
            bound.add(inv.getArgument(1));
            return spec;
        });
        when(spec.param(anyInt(), any(), anyInt())).thenAnswer(inv -> {
            bound.add(inv.getArgument(1));
            return spec;
        });

        assertEquals(1, metadata.insertWritePlan().execute(client, entity));
        assertEquals(1, DialectBinderDialect.binderInvocations);
        assertEquals(List.of("dialect-binder"), bound);
    }

    @Test
    void postgresDialectProvidesSpecializedBinderAndPreservesJsonbConversion() {
        PostgresDialect dialect = new PostgresDialect();
        EntityMetadata<JsonEntity> metadata = EntityMetadata.of(JsonEntity.class, dialect, null);

        ParamBinder binder = dialect.createParamBinder(
                JsonEntity.class,
                metadata.insertWritePlan().sql(),
                metadata.insertWritePlan().slots(),
                metadata.insertWritePlan().hasVersion()
        );
        assertNotNull(binder);

        JdbcClient client = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(client.sql(metadata.insertWritePlan().sql())).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.param(anyInt(), any())).thenReturn(spec);
        when(spec.param(anyInt(), any(), anyInt())).thenReturn(spec);
        when(spec.update()).thenReturn(1);

        List<Object> bound = new ArrayList<>();
        when(spec.param(any())).thenAnswer(inv -> {
            bound.add(inv.getArgument(0));
            return spec;
        });
        when(spec.param(anyInt(), any())).thenAnswer(inv -> {
            bound.add(inv.getArgument(1));
            return spec;
        });
        when(spec.param(anyInt(), any(), anyInt())).thenAnswer(inv -> {
            bound.add(inv.getArgument(1));
            return spec;
        });

        JsonEntity entity = new JsonEntity(1L, Map.of("vip", true));
        assertEquals(1, metadata.insertWritePlan().execute(client, entity));
        assertEquals(2, bound.size());
        assertEquals(1L, bound.get(0));
        assertInstanceOf(PGobject.class, bound.get(1));
    }
}
