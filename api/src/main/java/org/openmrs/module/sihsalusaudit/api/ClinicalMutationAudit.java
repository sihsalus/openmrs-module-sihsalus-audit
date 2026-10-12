package org.openmrs.module.sihsalusaudit.api;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.OpenmrsObject;
import org.openmrs.Order;
import org.openmrs.User;
import org.openmrs.module.sihsalusaudit.api.db.ClinicalAuditDao;
import org.openmrs.module.sihsalusaudit.model.ClinicalAuditEvent;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Registered inside the native service transaction, after its authorization interceptors. */
public class ClinicalMutationAudit implements MethodInterceptor {

    private static final Set<String> METHODS = new HashSet<String>(Arrays.asList(
            "saveEncounter", "voidEncounter", "unvoidEncounter", "saveObs", "voidObs", "unvoidObs",
            "saveOrder", "voidOrder", "unvoidOrder", "discontinueOrder"));

    private ClinicalAuditDao dao;
    private AuditSecurityContext securityContext;
    private AuditClock clock;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        String method = invocation.getMethod().getName();
        if (!METHODS.contains(method)) {
            return invocation.proceed();
        }
        Object input = invocation.getArguments()[0];
        if (!(input instanceof Encounter || input instanceof Obs || input instanceof Order)) {
            return invocation.proceed();
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Clinical audit must run inside the clinical transaction");
        }
        User actor = securityContext.requireAuthenticatedUser();
        OpenmrsObject original = (OpenmrsObject) input;
        boolean creating = original.getId() == null;
        String originalUuid = original.getUuid();
        Object result = invocation.proceed();
        OpenmrsObject saved = result instanceof OpenmrsObject ? (OpenmrsObject) result : original;

        String resource;
        String patientUuid;
        Encounter encounter;
        if (saved instanceof Encounter) {
            resource = "Encounter";
            encounter = (Encounter) saved;
            patientUuid = encounter.getPatient().getUuid();
        } else if (saved instanceof Obs) {
            resource = "Obs";
            Obs obs = (Obs) saved;
            encounter = obs.getEncounter();
            patientUuid = obs.getPerson().getUuid();
        } else {
            resource = "Order";
            Order order = (Order) saved;
            encounter = order.getEncounter();
            patientUuid = order.getPatient().getUuid();
        }
        String action = method.startsWith("unvoid") ? "UNVOID" : method.startsWith("void") ? "VOID"
                : method.startsWith("discontinue") ? "DISCONTINUE" : creating ? "CREATE" : "UPDATE";
        ClinicalAuditEvent event = new ClinicalAuditEvent();
        event.setClientEventId(UUID.randomUUID().toString());
        event.setEventType("SERVER_" + resource.toUpperCase(java.util.Locale.ROOT) + "_" + action);
        event.setActor(actor);
        event.setServerTimestamp(clock.now());
        event.setPatientUuid(patientUuid);
        event.setEncounterUuid(encounter == null ? null : encounter.getUuid());
        event.setResourceType(resource);
        // UUID-only metadata; do not duplicate clinical values, names or free-text reasons.
        String metadata = "{\"resourceUuid\":\"" + uuid(saved.getUuid()) + "\"";
        if (!creating && originalUuid != null && !originalUuid.equals(saved.getUuid())) {
            metadata += ",\"previousResourceUuid\":\"" + uuid(originalUuid) + "\"";
        }
        event.setMetadataJson(metadata + "}");
        dao.appendServerEvent(event);
        return result;
    }

    private String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) {
            throw new IllegalStateException("Clinical audit requires a persisted resource UUID");
        }
        return value;
    }

    public void setDao(ClinicalAuditDao dao) { this.dao = dao; }
    public void setSecurityContext(AuditSecurityContext securityContext) { this.securityContext = securityContext; }
    public void setClock(AuditClock clock) { this.clock = clock; }
}
