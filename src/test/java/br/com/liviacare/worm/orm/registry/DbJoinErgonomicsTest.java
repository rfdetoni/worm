package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbJoin;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DbJoinErgonomicsTest {

    @DbTable("departments")
    static class Department {
        @DbId("id")
        private Long id;

        private String name;
    }

    @DbTable("orders")
    static class Order {
        @DbId("id")
        private Long id;

        @DbColumn("owner_id")
        private Long ownerId;

        private UserWithInferredCollectionByBackRef user;
    }

    @DbTable("users")
    static class UserWithInferredManyToOne {
        @DbId("id")
        private Long id;

        @DbJoin
        private Department department;
    }

    @DbTable("users")
    static class UserWithMappedByCollection {
        @DbId("id")
        private Long id;

        @DbJoin(mappedBy = "owner_id")
        private List<Order> orders;
    }

    @DbTable("users")
    static class UserWithLocalColumn {
        @DbId("id")
        private Long id;

        @DbColumn("manager_id")
        private Long managerId;

        @DbJoin(localColumn = "manager_id")
        private Department manager;
    }

    @DbTable("users")
    static class UserWithInferredCollectionByBackRef {
        @DbId("id")
        private Long id;

        @DbJoin
        private List<Order> orders;
    }

    @DbTable("users")
    static class UserWithTargetColumn {
        @DbId("id")
        private Long id;

        @DbColumn("dept_code")
        private String deptCode;

        @DbJoin(localColumn = "dept_code", targetColumn = "code")
        private DepartmentByCode department;
    }

    @DbTable("departments")
    static class DepartmentByCode {
        @DbId("id")
        private Long id;

        private String code;
    }

    @DbTable("users")
    static class UserWithInvalidLocalColumn {
        @DbId("id")
        private Long id;

        @DbJoin(localColumn = "does_not_exist")
        private Department department;
    }

    @DbTable("users")
    static class UserWithVoidJoin {
        @DbId("id")
        private Long id;

        @DbJoin
        private Void department;
    }

    @DbTable("users")
    static class UserWithVoidCollectionJoin {
        @DbId("id")
        private Long id;

        @DbJoin
        private List<Void> departments;
    }

    @Test
    void inferJoinTableAliasAndOnForManyToOne() {
        EntityMetadata<UserWithInferredManyToOne> metadata = EntityMetadata.of(UserWithInferredManyToOne.class);
        JoinInfo join = firstJoin(metadata);

        assertEquals("departments", join.getTable());
        assertEquals("department", join.getAlias());
        assertEquals("department.id = userWithInferredManyToOne.department_id", join.getOn());
        assertTrue(metadata.selectSql().contains("JOIN departments department ON department.id = userWithInferredManyToOne.department_id"));
    }

    @Test
    void inferJoinOnFromMappedByForCollectionJoin() {
        EntityMetadata<UserWithMappedByCollection> metadata = EntityMetadata.of(UserWithMappedByCollection.class);
        JoinInfo join = firstJoin(metadata);

        assertEquals("orders", join.getTable());
        assertEquals("orders", join.getAlias());
        assertEquals("orders.owner_id = userWithMappedByCollection.id", join.getOn());
    }

    @Test
    void inferJoinOnFromLocalColumnShortcut() {
        EntityMetadata<UserWithLocalColumn> metadata = EntityMetadata.of(UserWithLocalColumn.class);
        JoinInfo join = firstJoin(metadata);

        assertEquals("manager", join.getAlias());
        assertEquals("manager.id = userWithLocalColumn.manager_id", join.getOn());
    }

    @Test
    void inferCollectionMappedByFromBackReferenceFieldName() {
        EntityMetadata<UserWithInferredCollectionByBackRef> metadata = EntityMetadata.of(UserWithInferredCollectionByBackRef.class);
        JoinInfo join = firstJoin(metadata);

        assertEquals("orders.user_id = userWithInferredCollectionByBackRef.id", join.getOn());
    }

    @Test
    void useTargetColumnAliasForReferencedColumn() {
        EntityMetadata<UserWithTargetColumn> metadata = EntityMetadata.of(UserWithTargetColumn.class);
        JoinInfo join = firstJoin(metadata);

        assertEquals("department.code = userWithTargetColumn.dept_code", join.getOn());
    }

    @Test
    void failFastWhenLocalColumnDoesNotExist() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> EntityMetadata.of(UserWithInvalidLocalColumn.class));

        assertTrue(ex.getMessage().contains("does_not_exist"));
    }

    @Test
    void failFastWhenJoinTypeIsVoidClass() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> EntityMetadata.of(UserWithVoidJoin.class));

        assertTrue(ex.getMessage().contains("cannot use void/Void"));
    }

    @Test
    void failFastWhenJoinCollectionElementTypeIsVoidClass() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> EntityMetadata.of(UserWithVoidCollectionJoin.class));

        assertTrue(ex.getMessage().contains("cannot use void/Void"));
    }

    private static JoinInfo firstJoin(EntityMetadata<?> metadata) {
        for (JoinInfo joinInfo : metadata.joinInfos()) {
            if (joinInfo != null) return joinInfo;
        }
        throw new AssertionError("No join metadata found");
    }
}

