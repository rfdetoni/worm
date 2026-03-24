package br.com.liviacare.worm.orm.sql;

import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbJoin;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.query.FilterBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryBuilderDbJoinIntegrationTest {

    @DbTable("departments")
    static class Department {
        @DbId("id")
        private Long id;

        @DbColumn("code")
        private String code;
    }

    @DbTable("users")
    static class UserWithDepartment {
        @DbId("id")
        private Long id;

        @DbColumn("department_id")
        private Long departmentId;

        @DbJoin(localColumn = "department_id", targetColumn = "id")
        private Department department;
    }

    @DbTable("orders")
    static class OrderWithBackRef {
        @DbId("id")
        private Long id;

        private UserWithOrders user;
    }

    @DbTable("users")
    static class UserWithOrders {
        @DbId("id")
        private Long id;

        @DbJoin
        private List<OrderWithBackRef> orders;
    }

    @Test
    void selectCountAndExistsContainInferredJoinWithAlias() {
        EntityMetadata<UserWithDepartment> metadata = EntityMetadata.of(UserWithDepartment.class);
        FilterBuilder filter = new FilterBuilder().eq("id", 10L);
        QueryBuilder<UserWithDepartment> qb = new QueryBuilder<>(metadata, filter, null);

        String select = qb.buildSelectSql(null, false);
        String count = qb.buildCountSql();
        String exists = qb.buildExistsSql();

        assertTrue(select.matches("(?s).*JOIN\\s+departments\\s+department\\s+ON\\s+department\\.id\\s*=\\s*userWithDepartment\\d*\\.department_id.*"));
        assertTrue(select.contains("WHERE"), select);
        assertTrue(select.contains("?"), select);

        assertTrue(count.contains("SELECT COUNT(*)"));
        assertTrue(count.matches("(?s).*JOIN\\s+departments\\s+department\\s+ON\\s+department\\.id\\s*=\\s*userWithDepartment\\d*\\.department_id.*"));
        assertTrue(count.contains("WHERE"), count);
        assertTrue(count.contains("?"), count);

        assertTrue(exists.contains("SELECT 1"));
        assertTrue(exists.matches("(?s).*JOIN\\s+departments\\s+department\\s+ON\\s+department\\.id\\s*=\\s*userWithDepartment\\d*\\.department_id.*"));
        assertTrue(exists.contains("LIMIT 1"));

        assertEquals(List.of(10L), qb.getParameters());
    }

    @Test
    void selectContainsCollectionJoinInferredFromBackReference() {
        EntityMetadata<UserWithOrders> metadata = EntityMetadata.of(UserWithOrders.class);
        QueryBuilder<UserWithOrders> qb = new QueryBuilder<>(metadata, new FilterBuilder(), null);

        String select = qb.buildSelectSql(null, false);

        assertTrue(select.matches("(?s).*JOIN\\s+orders\\s+orders\\s+ON\\s+orders\\.user_id\\s*=\\s*userWithOrders\\d*\\.id.*"));
    }
}

