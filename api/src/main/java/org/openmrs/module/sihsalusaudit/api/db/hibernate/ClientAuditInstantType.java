package org.openmrs.module.sihsalusaudit.api.db.hibernate;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Objects;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/** Keeps UTC milliseconds in the existing DATETIME(3) without JDBC timestamp coercion. */
public class ClientAuditInstantType implements UserType {

    private static final DateTimeFormatter WRITE_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter READ_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    @Override
    public int[] sqlTypes() {
        return new int[] { Types.TIMESTAMP };
    }

    @Override
    public Class<Instant> returnedClass() {
        return Instant.class;
    }

    @Override
    public Object nullSafeGet(ResultSet result, String[] names, SharedSessionContractImplementor session,
            Object owner) throws SQLException {
        String value = result.getString(names[0]);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, READ_FORMAT).toInstant(ZoneOffset.UTC);
        }
        catch (DateTimeParseException error) {
            throw new HibernateException("Invalid stored client audit timestamp");
        }
    }

    @Override
    public void nullSafeSet(PreparedStatement statement, Object value, int index,
            SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        }
        else {
            // Connector/J 8.0.30 sees MariaDB's 5.5.5 compatibility prefix and truncates
            // setTimestamp values. A bound UTC string preserves the same DATETIME(3) column.
            statement.setString(index, WRITE_FORMAT.format((Instant) value));
        }
    }

    @Override
    public boolean equals(Object first, Object second) {
        return Objects.equals(first, second);
    }

    @Override
    public int hashCode(Object value) {
        return Objects.hashCode(value);
    }

    @Override
    public Object deepCopy(Object value) {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(Object value) {
        return (Instant) value;
    }

    @Override
    public Object assemble(Serializable cached, Object owner) {
        return cached;
    }

    @Override
    public Object replace(Object original, Object target, Object owner) {
        return original;
    }
}
