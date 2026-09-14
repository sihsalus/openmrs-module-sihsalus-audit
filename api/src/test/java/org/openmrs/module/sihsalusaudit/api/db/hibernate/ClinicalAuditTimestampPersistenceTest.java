package org.openmrs.module.sihsalusaudit.api.db.hibernate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

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

    private static User actor;

    private ClinicalAuditServiceImpl service;

    @BeforeClass
    public static void createDatabase() {
        sessionFactory = new Configuration()
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:audit-timestamps;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
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
        service.setSecurityContext(privilege -> actor);
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
