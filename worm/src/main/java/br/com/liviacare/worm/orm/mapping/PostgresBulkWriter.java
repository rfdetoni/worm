package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.api.iBaseEntity;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Bulk write strategies for PostgreSQL using COPY and unnest.
 *
 * <p>Implements {@link BulkWriter} — obtained via
 * {@link br.com.liviacare.worm.orm.dialect.PostgresDialect#createBulkWriter}.
 */
public final class PostgresBulkWriter implements BulkWriter {

    private static final Logger log = LoggerFactory.getLogger(PostgresBulkWriter.class);

    public static final int DEFAULT_COPY_THRESHOLD = 100;
    public static final int DEFAULT_UNNEST_THRESHOLD = 50;

    private final DataSource dataSource;
    private final int copyThreshold;
    private final int unnestThreshold;

    public PostgresBulkWriter(DataSource dataSource) {
        this(dataSource, DEFAULT_COPY_THRESHOLD, DEFAULT_UNNEST_THRESHOLD);
    }

    public PostgresBulkWriter(DataSource dataSource, int copyThreshold, int unnestThreshold) {
        this.dataSource = dataSource;
        this.copyThreshold = copyThreshold;
        this.unnestThreshold = unnestThreshold;
    }

    @Override
    public <T> int[] bulkInsert(List<T> entities, EntityMetadata<T> meta) {
        return copyInsert(entities, meta);
    }

    @Override
    public <T> int[] bulkUpdate(List<T> entities, EntityMetadata<T> meta) {
        return unnestUpdate(entities, meta);
    }

    @Override
    public <T> int[] bulkDelete(List<T> entities, EntityMetadata<T> meta) {
        return unnestDelete(entities, meta);
    }

    /**
     * Bulk-upserts via an unnest-based {@code INSERT … ON CONFLICT … DO UPDATE SET} statement.
     * Returns {@code null} when the entity count is below {@link #unnestThreshold},
     * when no updatable columns exist, or when the ID type is not supported.
     */
    @Override
    public <T> int[] bulkUpsert(List<T> entities, EntityMetadata<T> meta) {
        if (entities == null || meta == null || entities.size() < unnestThreshold) return null;
        if (meta.hasVersion()) return null;

        final String idCol = meta.idColumnName();
        final int idIdx = meta.columnIndex(idCol);
        if (idIdx < 0) return null;
        final Class<?> idType = meta.selectTypes()[idIdx];
        final String idPgType = pgType(idType);
        if (idPgType == null) return null;

        final List<String> insertCols = meta.insertableColumns();
        if (insertCols.isEmpty()) return null;

        // Build pg-type arrays for each insertable column
        final String[] pgTypes = new String[insertCols.size()];
        final int[]    colIdxs = new int[insertCols.size()];
        for (int c = 0; c < insertCols.size(); c++) {
            int idx = meta.columnIndex(insertCols.get(c));
            if (idx < 0) return null;
            if (meta.isJsonColumn(insertCols.get(c))) return null;
            String pt = pgType(meta.selectTypes()[idx]);
            if (pt == null) return null;
            pgTypes[c]  = pt;
            colIdxs[c]  = idx;
        }

        // Gather column arrays
        final Object[][] colValues = new Object[insertCols.size()][entities.size()];
        for (int row = 0; row < entities.size(); row++) {
            T e = entities.get(row);
            if (e instanceof iBaseEntity base) {
                try {
                    Object id = meta.idGetter().invoke(e);
                    if (id == null) base.created(); else base.updated();
                } catch (Throwable ex) { base.created(); }
            }
            final Object[] vals;
            try {
                vals = EntityPersister.insertValuesArray(e, meta);
            } catch (Exception ex) {
                return null;
            }
            for (int c = 0; c < insertCols.size(); c++) {
                colValues[c][row] = normalizeForArray(vals[c], meta.selectTypes()[colIdxs[c]]);
            }
        }

        // BUILD SQL: INSERT INTO t (cols) SELECT cols FROM unnest(…) AS v(cols) ON CONFLICT (id) DO UPDATE SET …
        final StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(meta.tableName()).append(" (");
        for (int c = 0; c < insertCols.size(); c++) {
            if (c > 0) sql.append(", ");
            sql.append(insertCols.get(c));
        }
        sql.append(") SELECT ");
        for (int c = 0; c < insertCols.size(); c++) {
            if (c > 0) sql.append(", ");
            sql.append("v.").append(insertCols.get(c));
        }
        sql.append(" FROM unnest(");
        for (int c = 0; c < insertCols.size(); c++) {
            if (c > 0) sql.append(", ");
            sql.append("?::").append(pgTypes[c]).append("[]");
        }
        sql.append(") AS v(");
        for (int c = 0; c < insertCols.size(); c++) {
            if (c > 0) sql.append(", ");
            sql.append(insertCols.get(c));
        }
        sql.append(") ON CONFLICT (").append(idCol).append(") DO UPDATE SET ");
        boolean firstUpdate = true;
        for (String col : meta.updatableColumns()) {
            if (col.equals(idCol)) continue;
            if (!firstUpdate) sql.append(", ");
            sql.append(col).append(" = EXCLUDED.").append(col);
            firstUpdate = false;
        }
        if (firstUpdate) return null; // nothing to update

        Connection conn = DataSourceUtils.getConnection(dataSource);
        java.sql.Array[] arrays = new java.sql.Array[insertCols.size()];
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int bind = 1;
            for (int c = 0; c < insertCols.size(); c++) {
                arrays[c] = conn.createArrayOf(pgTypes[c], colValues[c]);
                ps.setArray(bind++, arrays[c]);
            }
            int affected = ps.executeUpdate();
            int[] out = new int[entities.size()];
            if (affected > 0) Arrays.fill(out, 1);
            return out;
        } catch (Exception e) {
            log.debug("unnest UPSERT failed, will fall back to batch upsert: {}", e.getMessage());
            return null;
        } finally {
            for (java.sql.Array array : arrays) {
                if (array != null) try { array.free(); } catch (Exception ignored) {}
            }
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    public <T> int[] copyInsert(List<T> entities, EntityMetadata<T> meta) {
        if (entities == null || meta == null || entities.size() < copyThreshold) return null;

        final List<String> cols = meta.insertableColumns();
        final String copySql = "COPY " + meta.tableName()
                + " (" + String.join(",", cols) + ") FROM STDIN WITH (FORMAT text)";

        final StringBuilder payload = new StringBuilder(Math.max(256, entities.size() * cols.size() * 12));
        for (T entity : entities) {
            if (entity instanceof iBaseEntity base) base.created();
            final Object[] vals;
            try {
                vals = EntityPersister.insertValuesArray(entity, meta);
            } catch (Exception e) {
                log.debug("COPY insert aborted — could not extract values: {}", e.getMessage());
                return null;
            }
            if (vals.length != cols.size()) return null;
            for (int i = 0; i < vals.length; i++) {
                if (i > 0) payload.append('\t');
                if (!appendCopyValue(payload, vals[i])) return null;
            }
            payload.append('\n');
        }

        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            PGConnection pg = conn.unwrap(PGConnection.class);
            CopyManager copy = pg.getCopyAPI();
            long inserted = copy.copyIn(copySql, new ByteArrayInputStream(bytes));
            if (inserted != entities.size()) {
                log.debug("COPY insert affected {} rows for {} entities; keeping optimistic fallback semantics", inserted, entities.size());
            }
            int[] out = new int[(int) inserted];
            Arrays.fill(out, 1);
            return out;
        } catch (Exception e) {
            log.debug("COPY insert failed, will fall back to batchUpdate: {}", e.getMessage());
            return null;
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    public <T> int[] unnestUpdate(List<T> entities, EntityMetadata<T> meta) {
        if (entities == null || meta == null || entities.size() < unnestThreshold) return null;
        if (meta.hasVersion()) return null;
        if (meta.updatableColumns().isEmpty()) return null;

        final List<String> dataCols = meta.updatableColumns();
        final String idCol = meta.idColumnName();
        final int idIdx = meta.columnIndex(idCol);
        if (idIdx < 0) return null;

        final Class<?> idType = meta.selectTypes()[idIdx];
        final String idPgType = pgType(idType);
        if (idPgType == null) return null;

        final String[] pgTypes = new String[dataCols.size()];
        final int[] colIndexes = new int[dataCols.size()];
        for (int c = 0; c < dataCols.size(); c++) {
            int idx = meta.columnIndex(dataCols.get(c));
            if (idx < 0) return null;
            if (meta.isJsonColumn(dataCols.get(c))) return null;
            String pt = pgType(meta.selectTypes()[idx]);
            if (pt == null) return null;
            pgTypes[c] = pt;
            colIndexes[c] = idx;
        }

        final Object[][] colValues = new Object[dataCols.size()][entities.size()];
        final Object[] idValues = new Object[entities.size()];

        for (int row = 0; row < entities.size(); row++) {
            T e = entities.get(row);
            if (e instanceof iBaseEntity base) base.updated();
            final Object[] vals;
            try {
                Object id = meta.idGetter().invoke(e);
                if (id == null) return null;
                vals = EntityPersister.updateValuesArray(e, meta, id);
                idValues[row] = normalizeForArray(id, idType);
            } catch (Throwable ex) {
                return null;
            }
            for (int c = 0; c < dataCols.size(); c++) {
                colValues[c][row] = normalizeForArray(vals[c], meta.selectTypes()[colIndexes[c]]);
            }
        }

        final StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(meta.tableName()).append(" t SET ");
        for (int c = 0; c < dataCols.size(); c++) {
            if (c > 0) sql.append(", ");
            sql.append(dataCols.get(c)).append(" = v.").append(dataCols.get(c));
        }
        sql.append(" FROM unnest(");
        for (int c = 0; c < dataCols.size(); c++) {
            if (c > 0) sql.append(", ");
            sql.append("?::").append(pgTypes[c]).append("[]");
        }
        sql.append(", ?::").append(idPgType).append("[]) AS v(");
        for (int c = 0; c < dataCols.size(); c++) {
            if (c > 0) sql.append(", ");
            sql.append(dataCols.get(c));
        }
        sql.append(", ").append(idCol).append(") WHERE t.").append(idCol).append(" = v.").append(idCol);

        Connection conn = DataSourceUtils.getConnection(dataSource);
        java.sql.Array[] arrays = new java.sql.Array[dataCols.size() + 1];
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int bind = 1;
            for (int c = 0; c < dataCols.size(); c++) {
                arrays[c] = conn.createArrayOf(pgTypes[c], colValues[c]);
                ps.setArray(bind++, arrays[c]);
            }
            arrays[dataCols.size()] = conn.createArrayOf(idPgType, idValues);
            ps.setArray(bind, arrays[dataCols.size()]);
            int updated = ps.executeUpdate();
            int[] out = new int[entities.size()];
            if (updated > 0) Arrays.fill(out, 1);
            return out;
        } catch (Exception e) {
            log.debug("unnest UPDATE failed, will fall back to batchUpdate: {}", e.getMessage());
            return null;
        } finally {
            for (java.sql.Array array : arrays) {
                if (array != null) {
                    try {
                        array.free();
                    } catch (Exception ignored) {
                    }
                }
            }
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    public <T> int[] unnestDelete(List<T> entities, EntityMetadata<T> meta) {
        if (entities == null || meta == null || entities.size() < unnestThreshold) return null;
        if (meta.softDeleteSql() != null) return null;

        final String idCol = meta.idColumnName();
        final int idIdx = meta.columnIndex(idCol);
        if (idIdx < 0) return null;
        final Class<?> idType = meta.selectTypes()[idIdx];
        final String idPgType = pgType(idType);
        if (idPgType == null) return null;

        final Object[] ids = new Object[entities.size()];
        for (int i = 0; i < entities.size(); i++) {
            try {
                Object id = meta.idGetter().invoke(entities.get(i));
                if (id == null) return null;
                ids[i] = normalizeForArray(id, idType);
            } catch (Throwable e) {
                return null;
            }
        }

        final String sql = "DELETE FROM " + meta.tableName()
                + " t USING unnest(?::" + idPgType + "[]) u(id) WHERE t." + idCol + " = u.id";

        Connection conn = DataSourceUtils.getConnection(dataSource);
        java.sql.Array idArray = null;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            idArray = conn.createArrayOf(idPgType, ids);
            ps.setArray(1, idArray);
            int deleted = ps.executeUpdate();
            int[] out = new int[entities.size()];
            if (deleted > 0) Arrays.fill(out, 1);
            return out;
        } catch (Exception e) {
            log.debug("unnest DELETE failed, will fall back to batchUpdate: {}", e.getMessage());
            return null;
        } finally {
            if (idArray != null) {
                try {
                    idArray.free();
                } catch (Exception ignored) {
                }
            }
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private static boolean appendCopyValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("\\N");
            return true;
        }
        if (value instanceof Boolean b) {
            out.append(b ? 't' : 'f');
            return true;
        }
        if (value instanceof Number || value instanceof java.util.UUID
                || value instanceof java.time.temporal.TemporalAccessor) {
            out.append(value);
            return true;
        }
        if (value instanceof Enum<?> e) {
            appendEscaped(out, e.name());
            return true;
        }
        if (value instanceof PGobject pg) {
            appendEscaped(out, pg.getValue());
            return true;
        }
        if (value instanceof byte[]) return false;
        appendEscaped(out, String.valueOf(value));
        return true;
    }

    private static void appendEscaped(StringBuilder out, String raw) {
        if (raw == null) {
            out.append("\\N");
            return;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
    }

    private static String pgType(Class<?> t) {
        if (t == null) return null;
        if (t == Integer.class || t == int.class
                || t == Short.class || t == short.class
                || t == Byte.class || t == byte.class) return "int4";
        if (t == Long.class || t == long.class) return "int8";
        if (t == Boolean.class || t == boolean.class) return "bool";
        if (t == Double.class || t == double.class) return "float8";
        if (t == Float.class || t == float.class) return "float4";
        if (t == java.math.BigDecimal.class) return "numeric";
        if (t == String.class || CharSequence.class.isAssignableFrom(t)) return "text";
        if (t == java.util.UUID.class) return "uuid";
        if (t == Instant.class
                || java.time.OffsetDateTime.class.isAssignableFrom(t)
                || java.time.ZonedDateTime.class.isAssignableFrom(t)) return "timestamptz";
        if (t == java.time.LocalDateTime.class) return "timestamp";
        if (t == java.time.LocalDate.class) return "date";
        if (t == java.time.LocalTime.class) return "time";
        if (t.isEnum()) return "text";
        return null;
    }

    private static Object normalizeForArray(Object val, Class<?> declaredType) {
        if (val == null) return null;
        if (val instanceof Enum<?> e) return e.name();
        if (val instanceof Instant i) {
            return java.time.OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
        }
        if (val instanceof PGobject pg) return pg.getValue();
        return val;
    }
}

