package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.annotation.audit.*;
import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.annotation.mapping.DbVersion;
import br.com.liviacare.worm.api.iBaseEntity;
import br.com.liviacare.worm.orm.converter.ConverterRegistry;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.mapping.EntityBinder;
import br.com.liviacare.worm.orm.mapping.EntityPersister;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GeneratedMetadataLookupTest {

    @DbTable("apt_lookup_entity")
    static class AptLookupEntity {
        @DbId("id")
        private Long id;

        @DbColumn("name")
        private String name;
    }

    @DbTable("apt_audited_entity")
    public static class AptAuditedEntity {
        @DbId("id")
        private Long id;
        private String name;
        @CreatedAt
        private Instant createdAt;
        @UpdatedAt
        private Instant updatedAt;
        @CreatedBy
        private String createdBy;
        @Active(defaultValue = false)
        private Boolean active;
        @DeletedAt
        private Instant deletedAt;
        @DbVersion
        private Integer version;

        public AptAuditedEntity() {
        }

        AptAuditedEntity(Long id, String name, Boolean active, Integer version) {
            this.id = id;
            this.name = name;
            this.active = active;
            this.version = version;
        }
    }

    @DbTable("departments")
    public static class AptDepartment {
        @DbId("id")
        private Long id;
        private String code;
    }

    @DbTable("apt_join_users")
    public static class AptUserWithDepartment {
        @DbId("id")
        private Long id;
        @DbColumn("department_id")
        private Long departmentId;
        private AptDepartment department;

        public AptUserWithDepartment() {
        }
    }

    public static final class AptLookupEntityFactory implements GeneratedEntityMetadataFactory<AptLookupEntity> {
        @Override
        public Class<AptLookupEntity> entityClass() {
            return AptLookupEntity.class;
        }

        @Override
        public EntityMetadata<AptLookupEntity> create(SqlDialect dialect, ConverterRegistry converterRegistry) {
            return GeneratedMetadataRuntimeSupport.buildSimpleEntityMetadata(
                    AptLookupEntity.class,
                    "apt_lookup_entity",
                    new GeneratedMetadataRuntimeSupport.PropertyDescriptor[]{
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("id", "id", "id", Long.class, Long.class, true, false, false, false, false, false, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("name", "name", "name", String.class, String.class, false, false, false, false, false, false, false, true)
                    },
                    dialect,
                    converterRegistry,
                    GeneratedMetadataRuntimeSupport.EntityOptions.defaults(null, false)
            );
        }
    }

    public static final class AptAuditedEntityFactory implements GeneratedEntityMetadataFactory<AptAuditedEntity> {
        @Override
        public Class<AptAuditedEntity> entityClass() {
            return AptAuditedEntity.class;
        }

        @Override
        public EntityMetadata<AptAuditedEntity> create(SqlDialect dialect, ConverterRegistry converterRegistry) {
            return GeneratedMetadataRuntimeSupport.buildSimpleEntityMetadata(
                    AptAuditedEntity.class,
                    "apt_audited_entity",
                    new GeneratedMetadataRuntimeSupport.PropertyDescriptor[]{
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("id", "id", "id", Long.class, Long.class, true, false, false, false, false, false, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("name", "name", "name", String.class, String.class, false, false, false, false, false, false, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("createdAt", "created_at", "created_at", Instant.class, Instant.class, false, false, true, false, false, false, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("updatedAt", "updated_at", "updated_at", Instant.class, Instant.class, false, false, false, true, false, false, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("createdBy", "created_by", "created_by", String.class, String.class, false, true, false, false, false, false, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("active", "active", "active", Boolean.class, Boolean.class, false, false, false, false, true, false, false, false),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("deletedAt", "deleted_at", "deleted_at", Instant.class, Instant.class, false, false, false, false, false, true, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("version", "version", "version", Integer.class, Integer.class, false, false, false, false, false, false, true, true)
                    },
                    dialect,
                    converterRegistry,
                    new GeneratedMetadataRuntimeSupport.EntityOptions(
                            null,
                            false,
                            "created_by",
                            "created_at",
                            "updated_at",
                            "active",
                            false,
                            "deleted_at",
                            "version"
                    )
            );
        }
    }

    public static final class AptUserWithDepartmentFactory implements GeneratedEntityMetadataFactory<AptUserWithDepartment> {
        @Override
        public Class<AptUserWithDepartment> entityClass() {
            return AptUserWithDepartment.class;
        }

        @Override
        public EntityMetadata<AptUserWithDepartment> create(SqlDialect dialect, ConverterRegistry converterRegistry) {
            return GeneratedMetadataRuntimeSupport.buildEntityMetadata(
                    AptUserWithDepartment.class,
                    "apt_join_users",
                    new GeneratedMetadataRuntimeSupport.PropertyDescriptor[]{
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("id", "id", "id", Long.class, Long.class, true, false, false, false, false, false, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("departmentId", "department_id", "department_id", Long.class, Long.class, false, false, false, false, false, false, false, true)
                    },
                    new GeneratedMetadataRuntimeSupport.JoinDescriptor[]{
                            new GeneratedMetadataRuntimeSupport.JoinDescriptor(
                                    "department",
                                    AptDepartment.class,
                                    "departments",
                                    "department",
                                    "department.id = aptUserWithDepartment.department_id",
                                    br.com.liviacare.worm.annotation.mapping.DbJoin.Type.INNER
                            )
                    },
                    dialect,
                    converterRegistry,
                    GeneratedMetadataRuntimeSupport.EntityOptions.defaults(null, false)
            );
        }
    }

    @DbTable("apt_bound_entity")
    public static class AptBoundEntity implements iBaseEntity {
        @DbId("id")
        private Long id;
        private String name;
        private int age;
        private final AtomicInteger createdCalls = new AtomicInteger();
        private final AtomicInteger updatedCalls = new AtomicInteger();

        public AptBoundEntity() {
        }

        AptBoundEntity(Long id, String name, int age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public int getAge() { return age; }

        @Override
        public void created() {
            createdCalls.incrementAndGet();
        }

        @Override
        public void deleted() {
        }

        @Override
        public void updated() {
            updatedCalls.incrementAndGet();
        }

        int createdCalls() {
            return createdCalls.get();
        }

        int updatedCalls() {
            return updatedCalls.get();
        }
    }

    public static final class AptBoundEntityFactory implements GeneratedEntityMetadataFactory<AptBoundEntity> {
        @Override
        public Class<AptBoundEntity> entityClass() {
            return AptBoundEntity.class;
        }

        @Override
        public EntityMetadata<AptBoundEntity> create(SqlDialect dialect, ConverterRegistry converterRegistry) {
            return GeneratedMetadataRuntimeSupport.buildSimpleEntityMetadata(
                    AptBoundEntity.class,
                    "apt_bound_entity",
                    new GeneratedMetadataRuntimeSupport.PropertyDescriptor[]{
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("id", "id", "id", Long.class, Long.class, true, false, false, false, false, false, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("name", "name", "name", String.class, String.class, false, false, false, false, false, false, false, true),
                            new GeneratedMetadataRuntimeSupport.PropertyDescriptor("age", "age", "age", int.class, int.class, false, false, false, false, false, false, false, true)
                    },
                    dialect,
                    converterRegistry,
                    GeneratedMetadataRuntimeSupport.EntityOptions.defaults(null, false)
            );
        }
    }

    public static final class AptBoundEntityBinder implements EntityBinder<AptBoundEntity> {
        @Override
        public void bindInsert(JdbcClient.StatementSpec spec, AptBoundEntity entity) {
            entity.created();
            spec = spec.param(entity.getId());
            spec = spec.param(entity.getName());
            spec = spec.param(entity.getAge());
        }

        @Override
        public void bindUpdate(JdbcClient.StatementSpec spec, AptBoundEntity entity) {
            entity.updated();
            spec = spec.param(entity.getName());
            spec = spec.param(entity.getAge());
            spec = spec.param(entity.getId());
        }
    }

    @Test
    void registryResolvesMetadataThroughGeneratedFactoryContract() {
        EntityMetadata<AptLookupEntity> metadata = EntityRegistry.getMetadata(AptLookupEntity.class);
        assertNotNull(metadata);
        assertEquals("apt_lookup_entity", metadata.tableName());
        assertEquals("id", metadata.idColumnName());
        assertEquals("SELECT apt_lookup_entity.id AS id, apt_lookup_entity.name AS name FROM apt_lookup_entity", metadata.selectSql());
    }

    @Test
    void generatedMetadataSupportsAuditAndVersionColumns() {
        EntityMetadata<AptAuditedEntity> metadata = EntityRegistry.getMetadata(AptAuditedEntity.class);

        assertNotNull(metadata);
        assertEquals("created_by", metadata.createdByColumn().orElseThrow());
        assertEquals("created_at", metadata.createdAtColumn().orElseThrow());
        assertEquals("updated_at", metadata.updatedAtColumn().orElseThrow());
        assertTrue(metadata.hasActive());
        assertEquals("active", metadata.activeColumn());
        assertEquals(false, metadata.activeDefaultValue());
        assertTrue(metadata.hasDeletedAt());
        assertEquals("deleted_at", metadata.deletedAtColumn());
        assertTrue(metadata.hasVersion());
        assertEquals("version", metadata.versionColumn());
        assertEquals(
                "UPDATE apt_audited_entity SET name = ?, updated_at = ?, active = ?, deleted_at = ?, version = version + 1 WHERE id = ? AND version = ?",
                metadata.updateSql()
        );
        assertEquals("UPDATE apt_audited_entity SET active = false WHERE id = ?", metadata.softDeleteSql());
        assertEquals(List.of("id", "name", "created_at", "updated_at", "created_by", "active", "deleted_at", "version"), metadata.insertableColumns());
        assertEquals(List.of("name", "updated_at", "active", "deleted_at"), metadata.updatableColumns());
    }

    @Test
    void generatedMetadataFeedsPersisterAuditAndVersionPaths() {
        EntityMetadata<AptAuditedEntity> metadata = EntityRegistry.getMetadata(AptAuditedEntity.class);
        AptAuditedEntity entity = new AptAuditedEntity(7L, "Ana", null, 4);

        List<Object> insertValues = EntityPersister.insertValues(entity, metadata);
        List<Object> updateValues = EntityPersister.updateValues(entity, metadata, entity.id);

        assertEquals(8, insertValues.size());
        assertEquals(7L, insertValues.get(0));
        assertEquals("Ana", insertValues.get(1));
        assertInstanceOf(Instant.class, insertValues.get(2));
        assertInstanceOf(Instant.class, insertValues.get(3));
        assertNull(insertValues.get(4));
        assertEquals(false, insertValues.get(5));
        assertNull(insertValues.get(6));
        assertEquals(4, insertValues.get(7));

        assertEquals(6, updateValues.size());
        assertEquals("Ana", updateValues.get(0));
        assertInstanceOf(Instant.class, updateValues.get(1));
        assertNull(updateValues.get(2));
        assertNull(updateValues.get(3));
        assertEquals(7L, updateValues.get(4));
        assertEquals(4, updateValues.get(5));
    }

    @Test
    void generatedMetadataSupportsToOneJoinMetadata() {
        EntityMetadata<AptUserWithDepartment> metadata = EntityRegistry.getMetadata(AptUserWithDepartment.class);

        assertNotNull(metadata);
        JoinInfo join = null;
        for (JoinInfo candidate : metadata.joinInfos()) {
            if (candidate != null) {
                join = candidate;
                break;
            }
        }
        assertNotNull(join);
        assertEquals("departments", join.getTable());
        assertEquals("departments", join.getAlias());
        assertEquals("departments.id = aptJoinUsers.department_id", join.getOn());
        // debug prints removed
        // main alias is derived from table name (apt_join_users -> aptJoinUsers)
        assertTrue(metadata.selectSql().contains("FROM apt_join_users aptJoinUsers INNER JOIN departments departments ON departments.id = aptJoinUsers.department_id"));
        assertTrue(metadata.selectSql().contains("departments.id AS departments_id"));
        assertTrue(metadata.selectSql().contains("departments.code AS departments_code"));
    }

    @Test
    void generatedRuntimeSupportLinksEntityBinderByServiceLoader() {
        EntityMetadata<AptBoundEntity> metadata = EntityRegistry.getMetadata(AptBoundEntity.class);
        assertNotNull(metadata.binder());

        AptBoundEntity entity = new AptBoundEntity(15L, "Mia", 28);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(spec.param(any())).thenReturn(spec);

        metadata.binder().bindInsert(spec, entity);
        verify(spec).param(15L);
        verify(spec).param("Mia");
        verify(spec).param(28);
        assertEquals(1, entity.createdCalls());

        reset(spec);
        when(spec.param(any())).thenReturn(spec);
        metadata.binder().bindUpdate(spec, entity);
        verify(spec).param("Mia");
        verify(spec).param(28);
        verify(spec).param(15L);
        assertEquals(1, entity.updatedCalls());
    }
}

