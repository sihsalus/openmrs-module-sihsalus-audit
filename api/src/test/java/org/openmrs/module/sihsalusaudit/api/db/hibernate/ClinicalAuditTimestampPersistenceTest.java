package org.openmrs.module.sihsalusaudit.api.db.hibernate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.hibernate.context.internal.ManagedSessionContext;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openmrs.User;
import org.openmrs.api.ValidationException;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.api.db.hibernate.DropMillisecondsHibernateInterceptor;
import org.openmrs.module.sihsalusaudit.api.ClinicalAuditSubmission;
import org.openmrs.module.sihsalusaudit.api.impl.ClinicalAuditServiceImpl;
import org.openmrs.module.sihsalusaudit.model.ClinicalAuditEvent;

/** Exercises the production mapping and DAO with OpenMRS' real timestamp interceptor. */
public class ClinicalAuditTimestampPersistenceTest {

    private static final long CLIENT_TIME = Instant.parse("2026-09-14T22:27:12.345Z").toEpochMilli();

    private static final long SERVER_TIME = Instant.parse("2026-09-14T22:28:00.987Z").toEpochMilli();

    private static SessionFactory sessionFactory;

    private static final boolean MARIADB = Boolean.getBoolean("audit.test.mariadb");

    private static User actor;

    private ClinicalAuditServiceImpl service;

    @BeforeClass
    public static void createDatabase() {
        Configuration configuration = new Configuration();
        if (MARIADB) {
            // Destructive schema setup is restricted to the disposable local CI database.
            String port = System.getenv("AUDIT_TEST_DB_PORT");
            assertTrue("Explicit disposable database port required", port != null && port.matches("[0-9]{4,5}"));
            assertTrue(Integer.parseInt(port) >= 1024 && Integer.parseInt(port) <= 65535);
            String password = System.getenv("AUDIT_TEST_DB_PASSWORD");
            assertTrue("Disposable test credential required", password != null && !password.isEmpty());
            configuration.setProperty("hibernate.connection.driver_class", "com.mysql.cj.jdbc.Driver")
                    .setProperty("hibernate.connection.url", "jdbc:mysql://127.0.0.1:" + port
                            + "/audit_timestamp_test?useSSL=false&connectionTimeZone=UTC"
                            + "&forceConnectionTimeZoneToSession=true&useServerPrepStmts="
                            + Boolean.getBoolean("audit.test.serverPrepared"))
                    .setProperty("hibernate.connection.username", "audit_test")
                    .setProperty("hibernate.connection.password", password)
                    .setProperty("hibernate.dialect", "org.hibernate.dialect.MySQL5InnoDBDialect");
        }
        else {
            configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                    .setProperty("hibernate.connection.url", "jdbc:h2:mem:audit-timestamps;DB_CLOSE_DELAY=-1")
                    .setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        }
        sessionFactory = configuration
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.current_session_context_class", "managed")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .setProperty("hibernate.cache.use_second_level_cache", "false")
                .setProperty("hibernate.cache.use_query_cache", "false")
                .setProperty("hibernate.cache.region.factory_class", "org.hibernate.cache.internal.NoCachingRegionFactory")
                .setProperty("hibernate.search.enabled", "false")
                .addResource("ClinicalAuditEvent.hbm.xml")
                .addResource("AuditTestUser.hbm.xml")
                .setInterceptor(new DropMillisecondsHibernateInterceptor())
                .buildSessionFactory();
        actor = new User();
        actor.setUserId(42);
        actor.setUuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        inTransaction(() -> sessionFactory.getCurrentSession().save(actor));
    }

    @AfterClass
    public static void closeDatabase() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @Before
    public void createService() {
        HibernateClinicalAuditDao dao = new HibernateClinicalAuditDao();
        dao.setSessionFactory(new DbSessionFactory(sessionFactory));
        service = new ClinicalAuditServiceImpl();
        service.setDao(dao);
        service.setSecurityContext(new org.openmrs.module.sihsalusaudit.api.AuditSecurityContext() {
            public User requireAuthenticatedUser() { return actor; }
            public User requireAuthenticatedUserWithPrivilege(String privilege) { return actor; }
        });
        service.setClock(() -> new Date(SERVER_TIME));
    }

    @Test
    public void firstSaveAndReloadPreserveMillisecondsWhileTheGlobalInterceptorRemainsActive() {
        ClinicalAuditSubmission submission = submission(UUID.randomUUID().toString(), CLIENT_TIME);

        assertEquals(Collections.singletonList(submission.getClientEventId()), record(submission));
        ClinicalAuditEvent stored = read(submission.getClientEventId());

        assertEquals(CLIENT_TIME, stored.getClientOccurredAt().getTime());
        // Control: the same real interceptor still truncates the server's Date field.
        assertNotEquals(SERVER_TIME, stored.getServerTimestamp().getTime());
        assertEquals(SERVER_TIME - 987, stored.getServerTimestamp().getTime());
    }

    @Test
    public void identicalReplayAfterReloadConfirmsExactlyOneStoredEvent() {
        ClinicalAuditSubmission submission = submission(UUID.randomUUID().toString(), CLIENT_TIME);
        record(submission);

        assertEquals(Collections.singletonList(submission.getClientEventId()), record(submission));
        assertEquals(1L, count(submission.getClientEventId()));
        assertEquals(CLIENT_TIME, read(submission.getClientEventId()).getClientOccurredAt().getTime());
    }

    @Test
    public void replayWithOneMillisecondDifferenceConflictsAndPreservesTheOriginal() {
        String id = UUID.randomUUID().toString();
        record(submission(id, CLIENT_TIME));

        assertThrows(ValidationException.class, () -> record(submission(id, CLIENT_TIME + 1)));

        assertEquals(1L, count(id));
        assertEquals(CLIENT_TIME, read(id).getClientOccurredAt().getTime());
    }

    @Test
    public void missingClientTimeRemainsNullAndIdenticalReplaySucceeds() {
        String id = UUID.randomUUID().toString();
        ClinicalAuditSubmission submission = new ClinicalAuditSubmission(id, "PATIENT_SEARCH", null, null, null,
                "{\"offline\":true}", null);
        record(submission);

        assertNull(read(id).getClientOccurredAt());
        assertEquals(Collections.singletonList(id), record(submission));
        assertThrows(ValidationException.class, () -> record(submission(id, CLIENT_TIME)));
        assertEquals(1L, count(id));
    }

    @Test
    public void existingDateTimeRowsRemainReadableWithoutRewritingEvidence() {
        String id = UUID.randomUUID().toString();
        inTransaction(() -> sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sihsalus_clinical_audit_event "
                            + "(client_event_id,event_type,actor_id,server_timestamp,client_occurred_at) "
                            + "VALUES (?, 'PATIENT_SEARCH', 42, '2026-09-14 22:28:00', '2026-09-14 22:27:12')")) {
                statement.setString(1, id);
                return statement.executeUpdate();
            }
        }));

        assertEquals(CLIENT_TIME - 345, read(id).getClientOccurredAt().getTime());
        assertEquals(1L, count(id));
    }

    @Test
    public void driverControlDemonstratesWhyTimestampBindingLosesMillisecondsOnMariaDb() {
        inTransaction(() -> sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT CAST(? AS DATETIME(3))")) {
                statement.setTimestamp(1, new Timestamp(CLIENT_TIME),
                        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(MARIADB ? 0 : 345000000, result.getTimestamp(1).getNanos());
                }
                statement.setString(1, "2026-09-14 22:27:12.345");
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(345000000, result.getTimestamp(1).getNanos());
                }
            }
            return null;
        }));
    }

    private ClinicalAuditSubmission submission(String id, long time) {
        return new ClinicalAuditSubmission(id, "PATIENT_SEARCH", null, null, null,
                "{\"offline\":true}", new Date(time));
    }

    private List<String> record(ClinicalAuditSubmission submission) {
        return inTransaction(() -> service.recordEvents(Collections.singletonList(submission)));
    }

    private ClinicalAuditEvent read(String id) {
        return inTransaction(() -> (ClinicalAuditEvent) sessionFactory.getCurrentSession()
                .createQuery("from ClinicalAuditEvent where clientEventId = :id")
                .setParameter("id", id).uniqueResult());
    }

    private long count(String id) {
        return inTransaction(() -> (Long) sessionFactory.getCurrentSession()
                .createQuery("select count(*) from ClinicalAuditEvent where clientEventId = :id")
                .setParameter("id", id).uniqueResult());
    }

    private static <T> T inTransaction(Supplier<T> work) {
        try (Session session = sessionFactory.openSession()) {
            ManagedSessionContext.bind(session);
            Transaction transaction = session.beginTransaction();
            try {
                T result = work.get();
                transaction.commit();
                return result;
            }
            catch (RuntimeException error) {
                transaction.rollback();
                throw error;
            }
            finally {
                ManagedSessionContext.unbind(sessionFactory);
            }
        }
    }
}
