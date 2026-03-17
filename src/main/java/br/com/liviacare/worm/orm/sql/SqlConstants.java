package br.com.liviacare.worm.orm.sql;

/**
 * Reusable SQL and logging constants to avoid duplicated string literals.
 */
public final class SqlConstants {

    // SQL fragments
    public static final String SELECT = "SELECT ";
    public static final String SELECT_COUNT_STAR_FROM = "SELECT COUNT(*) FROM ";
    public static final String FROM = " FROM ";
    public static final String INSERT_INTO = "INSERT INTO ";
    public static final String VALUES_PREFIX = ") VALUES ("; // used after column list
    public static final String UPDATE = "UPDATE ";
    public static final String SET = " SET ";
    public static final String WHERE = " WHERE ";
    public static final String AND = " AND ";
    public static final String ORDER_BY = " ORDER BY ";
    public static final String DELETE_FROM = "DELETE FROM ";
    public static final String LIMIT_OFFSET = " LIMIT ? OFFSET ?";
    public static final String COMMA_SPACE = ", ";
    public static final String PLACEHOLDER = "?";
    // small fragments
    public static final String EQUAL_TRUE = " = true";
    public static final String IS_NULL = " IS NULL";

    // Logging
    public static final String LOG_PREFIX = "[WORM(Weightless ORM)]";
    public static final String LOG_FORMAT = "[WORM(Weightless ORM)] [{}] [{} ms] {}";
    // Operation labels
    public static final String OP_SELECT = "SELECT";
    public static final String OP_SELECT_BY_ID = "SELECT-BY-ID";
    public static final String OP_SELECT_PAGE = "SELECT-PAGE";
    public static final String OP_INSERT = "INSERT";
    public static final String OP_INSERT_BATCH = "INSERT-BATCH";
    public static final String OP_UPDATE = "UPDATE";
    public static final String OP_DELETE = "DELETE";
    public static final String OP_SOFT_DELETE = "SOFT-DELETE";
    public static final String OP_UPDATE_BATCH = "UPDATE-BATCH";
    public static final String OP_DELETE_BATCH = "DELETE-BATCH";
    public static final String OP_UPSERT_BATCH = "UPSERT-BATCH";

    private SqlConstants() {
    }
}
