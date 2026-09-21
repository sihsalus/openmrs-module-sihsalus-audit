package org.openmrs.module.sihsalusaudit.api.db.hibernate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;

import org.hibernate.HibernateException;
import org.junit.Test;

public class ClientAuditInstantTypeTest {

    private final ClientAuditInstantType type = new ClientAuditInstantType();

    @Test
    public void bindsUtcMillisecondsAcrossDateBoundariesAndSupportedRange() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        type.nullSafeSet(statement, Instant.parse("2026-09-21T00:00:00.001Z"), 1, null);
        type.nullSafeSet(statement, Instant.parse("1000-01-01T00:00:00Z"), 2, null);
        type.nullSafeSet(statement, Instant.parse("9999-12-31T23:59:59.999Z"), 3, null);
        type.nullSafeSet(statement, null, 4, null);

        verify(statement).setString(1, "2026-09-21 00:00:00.001");
        verify(statement).setString(2, "1000-01-01 00:00:00.000");
        verify(statement).setString(3, "9999-12-31 23:59:59.999");
        verify(statement).setNull(4, Types.TIMESTAMP);
    }

    @Test
    public void readsLegacySecondsAndVariableFractionalRepresentationsAsUtc() throws Exception {
        assertEquals(Instant.parse("2026-09-21T00:00:00Z"), read("2026-09-21 00:00:00"));
        assertEquals(Instant.parse("2026-09-21T00:00:00.100Z"), read("2026-09-21 00:00:00.1"));
        assertEquals(Instant.parse("2026-09-21T00:00:00.123Z"), read("2026-09-21 00:00:00.123000"));
        assertNull(read(null));
    }

    @Test
    public void invalidStoredDateFailsWithoutExposingItsValue() {
        HibernateException error = assertThrows(HibernateException.class, () -> read("2026-02-30 00:00:00"));
        assertEquals("Invalid stored client audit timestamp", error.getMessage());
        assertNull(error.getCause());
    }

    private Object read(String value) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getString("client_occurred_at")).thenReturn(value);
        return type.nullSafeGet(result, new String[] { "client_occurred_at" }, null, null);
    }
}
