package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.annotation.audit.Active;
import br.com.liviacare.worm.annotation.audit.CreatedAt;
import br.com.liviacare.worm.annotation.audit.UpdatedAt;
import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.annotation.mapping.DbVersion;
import br.com.liviacare.worm.orm.dialect.MySQLDialect;
import br.com.liviacare.worm.api.iBaseEntity;
import br.com.liviacare.worm.orm.dialect.PostgresDialect;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import org.postgresql.util.PGobject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EntityPersisterFastPathTest {

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
                                             br.com.liviacare.worm.orm.sql.WritePlan.Slot[] slots,
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

    @DbTable("mysql_entities")
    record MySqlEntity(
            @DbId("id") UUID id,
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
        FastPathDecisionCache.clear();
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
    void fastPathDelegatesConsistentlyAndCachesDecision() {
        EntityMetadata<SampleEntity> metadata = EntityMetadata.of(SampleEntity.class);
        SampleEntity entity = new SampleEntity(11L, "Bob", Status.INACTIVE, 7);

        assertTrue(FastPathDecisionCache.canUseFastPath(SampleEntity.class, metadata));
        assertEquals(1, FastPathDecisionCache.size());

        assertArrayEquals(
                EntityPersister.insertValuesArray(entity, metadata),
                EntityPersisterFastPath.insertValuesArrayFast(entity, metadata)
        );
        assertArrayEquals(
                EntityPersister.updateValuesArray(entity, metadata, entity.id()),
                EntityPersisterFastPath.updateValuesArrayFast(entity, metadata, entity.id())
        );
        assertEquals(
                EntityPersister.insertValues(entity, metadata),
                EntityPersisterFastPath.insertValuesFast(entity, metadata)
        );
        assertEquals(
                EntityPersister.updateValues(entity, metadata, entity.id()),
                EntityPersisterFastPath.updateValuesFast(entity, metadata, entity.id())
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

        assertNotNull(metadata.insertWritePlan());
        assertNotNull(metadata.updateWritePlan());

        assertEquals(1, metadata.insertWritePlan().execute(client, entity));
        assertTrue(AuditedEntity.createdCalled);
        assertEquals(6, insertBound.size());
        assertEquals(42L, insertBound.get(0));
        assertEquals("Carol", insertBound.get(1));
        assertInstanceOf(Instant.class, insertBound.get(2));
        assertInstanceOf(Instant.class, insertBound.get(3));
        assertEquals(true, insertBound.get(4));
        assertEquals(5, insertBound.get(5));

        assertEquals(1, metadata.updateWritePlan().execute(client, entity));
        assertTrue(AuditedEntity.updatedCalled);
        assertEquals(5, updateBound.size());
        assertEquals("Carol", updateBound.get(0));
        assertInstanceOf(Instant.class, updateBound.get(1));
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
        when(spec.update()).thenReturn(1);

        List<Object> bound = new ArrayList<>();
        when(spec.param(any())).thenAnswer(inv -> {
            bound.add(inv.getArgument(0));
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
        when(spec.update()).thenReturn(1);

        List<Object> bound = new ArrayList<>();
        when(spec.param(any())).thenAnswer(inv -> {
            bound.add(inv.getArgument(0));
            return spec;
        });

        JsonEntity entity = new JsonEntity(1L, Map.of("vip", true));
        assertEquals(1, metadata.insertWritePlan().execute(client, entity));
        assertEquals(2, bound.size());
        assertEquals(1L, bound.get(0));
        assertInstanceOf(PGobject.class, bound.get(1));
    }

    @Test
    void mysqlDialectNormalizesUuidAndSerializesJsonAsString() {
        MySQLDialect dialect = new MySQLDialect();
        EntityMetadata<MySqlEntity> metadata = EntityMetadata.of(MySqlEntity.class, dialect, null);

        JdbcClient client = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(client.sql(metadata.insertWritePlan().sql())).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);

        List<Object> bound = new ArrayList<>();
        when(spec.param(any())).thenAnswer(inv -> {
            bound.add(inv.getArgument(0));
            return spec;
        });

        UUID id = UUID.fromString("00000000-0000-7000-0000-000000000001");
        MySqlEntity entity = new MySqlEntity(id, Map.of("vip", true));

        assertEquals(1, metadata.insertWritePlan().execute(client, entity));
        assertEquals(2, bound.size());
        assertEquals(id.toString(), bound.get(0));
        assertEquals("{\"vip\":true}", bound.get(1));
    }
}
