package org.openmrs.module.sihsalusaudit.api;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.*;
import org.openmrs.api.OrderContext;
import org.openmrs.module.sihsalusaudit.api.db.ClinicalAuditDao;
import org.openmrs.module.sihsalusaudit.model.ClinicalAuditEvent;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.support.TransactionTemplate;

public class ClinicalMutationAuditTest {
    public interface ClinicalActions {
        Encounter saveEncounter(Encounter encounter);
        Encounter voidEncounter(Encounter encounter, String reason);
        Obs saveObs(Obs obs, String reason);
        Order saveOrder(Order order, OrderContext context);
        Encounter getEncounter(Integer id);
    }
    private ClinicalMutationAudit hook;
    private ClinicalActions target;
    private ClinicalActions proxy;
    private ClinicalAuditDao dao;
    private JdbcTemplate jdbc;
    private User actor;
    private DataSourceTransactionManager transactions;

    @Before
    public void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:audit" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table clinical (id int primary key)");
        jdbc.execute("create table events (kind varchar(64))");
        transactions = new DataSourceTransactionManager(ds);
        target = mock(ClinicalActions.class);
        dao = mock(ClinicalAuditDao.class);
        actor = new User(42);
        AuditSecurityContext security = mock(AuditSecurityContext.class);
        when(security.requireAuthenticatedUser()).thenReturn(actor);
        hook = new ClinicalMutationAudit();
        hook.setDao(dao); hook.setSecurityContext(security); hook.setClock(() -> new Date(123456789));
        doAnswer(i -> { jdbc.update("insert into events values (?)", ((ClinicalAuditEvent) i.getArgument(0)).getEventType()); return null; })
                .when(dao).appendServerEvent(any());
        NameMatchTransactionAttributeSource source = new NameMatchTransactionAttributeSource();
        source.addTransactionalMethod("*", new RuleBasedTransactionAttribute());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, source));
        factory.addAdvice(hook); // Native Context.addAdvice appends after the transaction interceptor.
        proxy = (ClinicalActions) factory.getProxy();
        when(target.saveEncounter(any())).thenAnswer(i -> {
            jdbc.update("insert into clinical values (1)");
            Encounter e = i.getArgument(0); e.setEncounterId(1); return e;
        });
    }

    private Encounter encounter() {
        Encounter e = new Encounter(); e.setUuid(UUID.randomUUID().toString());
        Patient p = new Patient(7); p.setUuid(UUID.randomUUID().toString()); e.setPatient(p);
        return e;
    }

    @Test public void commitsAnAuthoritativeEventWithTheClinicalWrite() {
        Encounter e = encounter(); proxy.saveEncounter(e);
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("select count(*) from clinical", Integer.class));
        org.mockito.ArgumentCaptor<ClinicalAuditEvent> capture = org.mockito.ArgumentCaptor.forClass(ClinicalAuditEvent.class);
        verify(dao).appendServerEvent(capture.capture());
        ClinicalAuditEvent event = capture.getValue();
        assertEquals("SERVER_ENCOUNTER_CREATE", event.getEventType());
        assertSame(actor, event.getActor());
        assertEquals(e.getUuid(), event.getEncounterUuid());
        assertEquals(new Date(123456789), event.getServerTimestamp());
        assertNull(event.getClientOccurredAt());
        assertTrue(event.getMetadataJson().contains(e.getUuid()));
    }

    @Test public void rollsBackClinicalWriteWhenAuditFails() {
        doThrow(new IllegalStateException("test failure")).when(dao).appendServerEvent(any());
        assertThrows(IllegalStateException.class, () -> proxy.saveEncounter(encounter()));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("select count(*) from clinical", Integer.class));
    }

    @Test public void doesNotAuditAFailedClinicalWrite() {
        doThrow(new IllegalArgumentException("invalid")).when(target).saveEncounter(any());
        assertThrows(IllegalArgumentException.class, () -> proxy.saveEncounter(encounter()));
        verifyNoInteractions(dao);
    }

    @Test public void enclosingRollbackRemovesBothWrites() {
        new TransactionTemplate(transactions).execute(status -> { proxy.saveEncounter(encounter()); status.setRollbackOnly(); return null; });
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("select count(*) from clinical", Integer.class));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("select count(*) from events", Integer.class));
    }

    @Test public void capturesVoidWithoutCopyingItsFreeTextReason() {
        Encounter e = encounter(); e.setEncounterId(9);
        when(target.voidEncounter(e, "private reason")).thenReturn(e);
        proxy.voidEncounter(e, "private reason");
        verify(dao).appendServerEvent(argThat(event -> event.getEventType().equals("SERVER_ENCOUNTER_VOID")
                && !event.getMetadataJson().contains("private reason")));
    }

    @Test public void recordsObservationReplacementAndItsPreviousUuid() {
        Encounter e = encounter(); Obs before = new Obs(4); before.setUuid(UUID.randomUUID().toString());
        Obs after = new Obs(5); after.setUuid(UUID.randomUUID().toString()); after.setPerson(e.getPatient()); after.setEncounter(e);
        when(target.saveObs(before, "correction")).thenReturn(after);
        proxy.saveObs(before, "correction");
        verify(dao).appendServerEvent(argThat(event -> event.getEventType().equals("SERVER_OBS_UPDATE")
                && event.getMetadataJson().contains(before.getUuid()) && event.getMetadataJson().contains(after.getUuid())));
    }

    @Test public void doesNotEmitWriteEventsForReads() {
        proxy.getEncounter(1); verifyNoInteractions(dao);
    }

    @Test public void refusesMutationWithoutTheNativeTransaction() {
        ProxyFactory f = new ProxyFactory(target); f.addAdvice(hook);
        assertThrows(IllegalStateException.class, () -> ((ClinicalActions)f.getProxy()).saveEncounter(encounter()));
        verifyNoInteractions(target, dao);
    }
}
