package com.github.rfdetoni.worm.orm.mapping;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GeneratedBinderSupportTest {

    private enum Status {
        ACTIVE
    }

    @Test
    void prepareSerializesJsonCollectionsAsJsonbPgObject() {
        Object prepared = GeneratedBinderSupport.prepare(List.of("morning", "afternoon"), true, false);

        assertInstanceOf(PGobject.class, prepared);
        PGobject pg = (PGobject) prepared;
        assertEquals("jsonb", pg.getType());
        assertEquals("[\"morning\",\"afternoon\"]", pg.getValue());
    }

    @Test
    void prepareConvertsEnumToNameForPlainAndJsonBindings() {
        Object plain = GeneratedBinderSupport.prepare(Status.ACTIVE, false, true);
        Object json = GeneratedBinderSupport.prepare(Status.ACTIVE, true, true);

        assertEquals("ACTIVE", plain);
        assertInstanceOf(PGobject.class, json);
        assertEquals("\"ACTIVE\"", ((PGobject) json).getValue());
    }

    @Test
    void prepareReturnsNullWhenValueIsNull() {
        assertNull(GeneratedBinderSupport.prepare(null, true, true));
    }

    @Test
    void normalizeJdbcValueConvertsInstantToUtcOffsetDateTime() {
        Instant instant = Instant.parse("2026-06-12T10:15:30Z");

        Object prepared = GeneratedBinderSupport.normalizeJdbcValue(instant);

        assertEquals(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC), prepared);
    }

    @Test
    void bindPositionalUsesExplicitTimestampWithTimezoneTypeForInstant() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        Instant instant = Instant.parse("2026-06-12T10:15:30Z");
        OffsetDateTime expected = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);

        when(spec.param(eq(1), any(), eq(java.sql.Types.TIMESTAMP_WITH_TIMEZONE))).thenReturn(spec);

        JdbcClient.StatementSpec bound = GeneratedBinderSupport.bindPositional(spec, 1, instant);

        assertSame(spec, bound);
        verify(spec).param(1, expected, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        verify(spec, never()).param(eq(1), any());
    }
}
