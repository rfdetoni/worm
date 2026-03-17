package br.com.liviacare.worm.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilterBuilderTest {

    @Test
    void whereStaticFactory_returnsEmptyBuilder() {
        FilterBuilder f = FilterBuilder.where();
        assertTrue(f.isEmpty());
        assertEquals("", f.build());
    }

    @Test
    void eq_buildsCorrectClause() {
        FilterBuilder f = FilterBuilder.where().eq("status", "active");
        assertEquals("status = ?", f.build());
        assertEquals(List.of("active"), f.getParameters());
    }

    @Test
    void eqIfNotNull_skipsWhenNull() {
        FilterBuilder f = FilterBuilder.where().eqIfNotNull("col", null);
        assertTrue(f.isEmpty());
    }

    @Test
    void multiplePredicates_joinedWithAnd() {
        FilterBuilder f = FilterBuilder.where()
                .eq("status", "active")
                .gt("age", 18);
        assertEquals("status = ? AND age > ?", f.build());
        assertEquals(List.of("active", 18), f.getParameters());
    }

    @Test
    void or_buildCorrectClause() {
        FilterBuilder f = FilterBuilder.where()
                .eq("a", 1)
                .or()
                .eq("b", 2);
        assertEquals("a = ? OR b = ?", f.build());
        assertEquals(2, f.getParameters().size());
    }

    @Test
    void in_withValues_buildsInClause() {
        FilterBuilder f = FilterBuilder.where().in("id", List.of(1, 2, 3));
        assertEquals("id IN (?,?,?)", f.build());
        assertEquals(3, f.getParameters().size());
    }

    @Test
    void in_withEmptyCollection_buildsFalseCondition() {
        FilterBuilder f = FilterBuilder.where().in("id", List.of());
        assertEquals("1=0", f.build());
    }

    @Test
    void isNull_isNotNull() {
        assertEquals("col IS NULL", FilterBuilder.where().isNull("col").build());
        assertEquals("col IS NOT NULL", FilterBuilder.where().isNotNull("col").build());
    }

    @Test
    void like_buildsLikeClause() {
        FilterBuilder f = FilterBuilder.where().like("name", "%john%");
        assertEquals("name LIKE ?", f.build());
        assertEquals("%john%", f.getParameters().get(0));
    }

    @Test
    void likeIfNotBlank_skipsWhenBlank() {
        assertTrue(FilterBuilder.where().likeIfNotBlank("col", "").isEmpty());
        assertTrue(FilterBuilder.where().likeIfNotBlank("col", "  ").isEmpty());
        assertFalse(FilterBuilder.where().likeIfNotBlank("col", "x").isEmpty());
    }

    @Test
    void neq_lte_gte_lt_gt() {
        assertEquals("a <> ?", FilterBuilder.where().neq("a", 1).build());
        assertEquals("a <= ?", FilterBuilder.where().lte("a", 1).build());
        assertEquals("a >= ?", FilterBuilder.where().gte("a", 1).build());
        assertEquals("a < ?",  FilterBuilder.where().lt("a", 1).build());
        assertEquals("a > ?",  FilterBuilder.where().gt("a", 1).build());
    }

    @Test
    void openCloseParen_wrapsExpression() {
        FilterBuilder f = FilterBuilder.where()
                .openParen().eq("a", 1).or().eq("b", 2).closeParen()
                .eq("c", 3);
        assertEquals("(a = ? OR b = ?) AND c = ?", f.build());
    }

    @Test
    void orderBy_buildsOrderByClause() {
        FilterBuilder f = FilterBuilder.where().orderBy("name").orderByDesc("age");
        assertEquals(" ORDER BY name ASC, age DESC", f.buildOrderBy());
        assertTrue(f.hasOrderBy());
    }

    @Test
    void orderByRaw_appendsAsIs() {
        FilterBuilder f = FilterBuilder.where().orderByRaw("RANDOM()");
        assertEquals(" ORDER BY RANDOM()", f.buildOrderBy());
    }

    @Test
    void groupBy_buildsGroupByClause() {
        FilterBuilder f = FilterBuilder.where().groupBy("status", "type");
        assertEquals(" GROUP BY status, type", f.buildGroupBy());
    }

    @Test
    void leftJoin_addsJoin() {
        FilterBuilder f = FilterBuilder.where().leftJoin("orders", "o", "o.user_id = u.id");
        assertEquals(1, f.getJoins().size());
        FilterBuilder.Join j = f.getJoins().get(0);
        assertEquals(FilterBuilder.JoinType.LEFT, j.type());
        assertEquals("orders", j.table());
        assertEquals("o", j.alias());
        assertFalse(f.isNoJoin());
    }

    @Test
    void notJoin_setsFlag() {
        FilterBuilder f = FilterBuilder.where().notJoin();
        assertTrue(f.isNoJoin());
    }

    @Test
    void duplicateJoin_notAddedTwice() {
        FilterBuilder f = FilterBuilder.where()
                .leftJoin("orders", "o", "o.user_id = u.id")
                .leftJoin("orders", "o", "o.user_id = u.id");
        assertEquals(1, f.getJoins().size());
    }

    @Test
    void conflictingAlias_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            FilterBuilder.where()
                .leftJoin("orders", "o", "o.user_id = u.id")
                .leftJoin("items",  "o", "o.order_id = x.id")
        );
    }

    @Test
    void mainAlias_setsAlias() {
        FilterBuilder f = FilterBuilder.where().mainAlias("u");
        assertEquals("u", f.getMainTableAlias());
    }

    @Test
    void alias_isAliasForMainAlias() {
        FilterBuilder f = FilterBuilder.where().alias("p");
        assertEquals("p", f.getMainTableAlias());
    }

    @Test
    void ignoreSoftDelete_setsFlag() {
        FilterBuilder f = FilterBuilder.where().ignoreSoftDelete();
        assertTrue(f.isIgnoreSoftDelete());
    }

    @Test
    void enumParam_convertedToName() {
        enum Color { RED, GREEN }
        FilterBuilder f = FilterBuilder.where().eq("color", Color.RED);
        assertEquals("RED", f.getParameters().get(0));
    }

    @Test
    void withCte_rawSql() {
        FilterBuilder f = FilterBuilder.where().withCte("my_cte", "SELECT 1");
        assertEquals(1, f.getCtes().size());
        assertEquals("my_cte", f.getCtes().get(0).name());
        assertEquals("SELECT 1", f.getCtes().get(0).sql());
    }

    @Test
    void windowFunction_added() {
        FilterBuilder f = FilterBuilder.where().addWindowFunction("ROW_NUMBER() OVER (ORDER BY id)", "rn");
        assertEquals(1, f.getWindowFunctions().size());
        assertEquals("rn", f.getWindowFunctions().get(0).alias());
    }

    @Test
    void buildFromClause_noAlias() {
        FilterBuilder f = FilterBuilder.where();
        assertEquals("FROM users", f.buildFromClause("users", List.of()));
    }

    @Test
    void buildFromClause_withAlias() {
        FilterBuilder f = FilterBuilder.where().alias("u");
        assertTrue(f.buildFromClause("users", List.of()).startsWith("FROM users u"));
    }

    @Test
    void jsonTextEq_buildsCorrectFragment() {
        FilterBuilder f = FilterBuilder.where().jsonTextEq("address", "state", "SP");
        assertEquals("address->>'state' = ?", f.build());
        assertEquals("SP", f.getParameters().get(0));
    }

    @Test
    void jsonHasKey_buildsCorrectFragment() {
        FilterBuilder f = FilterBuilder.where().jsonHasKey("data", "phone");
        assertEquals("jsonb_exists(data, ?)", f.build());
    }

    @Test
    void jsonPathExists_buildsCorrectFragment() {
        FilterBuilder f = FilterBuilder.where().jsonPathExists("meta", "$.active");
        assertEquals("jsonb_path_exists(meta, ?::jsonpath)", f.build());
    }

    @Test
    void isEmpty_trueWhenNothingAdded() {
        assertTrue(new FilterBuilder().isEmpty());
        assertFalse(FilterBuilder.where().eq("a", 1).isEmpty());
    }

    @Test
    void getArgs_aliasForGetParameters() {
        FilterBuilder f = FilterBuilder.where().eq("x", 42);
        assertEquals(f.getParameters(), f.getArgs());
    }

    @Test
    void getWhereClause_aliasForBuild() {
        FilterBuilder f = FilterBuilder.where().eq("x", 42);
        assertEquals(f.build(), f.getWhereClause());
    }
}

