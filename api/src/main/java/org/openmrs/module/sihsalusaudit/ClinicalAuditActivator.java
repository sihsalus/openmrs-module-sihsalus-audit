package org.openmrs.module.sihsalusaudit;

import org.openmrs.module.BaseModuleActivator;
import org.openmrs.api.context.Context;
import org.openmrs.api.EncounterService;
import org.openmrs.api.ObsService;
import org.openmrs.api.OrderService;
import org.openmrs.module.sihsalusaudit.api.ClinicalMutationAudit;

/**
 * Supplies the lifecycle entry point required by OpenMRS. Schema changes and
 * privileges are declared in the module resources; no background task is started.
 */
public class ClinicalAuditActivator extends BaseModuleActivator {
    private ClinicalMutationAudit advice;

    @Override
    public void started() {
        advice = Context.getRegisteredComponent("sihsalusClinicalMutationAudit", ClinicalMutationAudit.class);
        Context.addAdvice(EncounterService.class, advice);
        Context.addAdvice(ObsService.class, advice);
        Context.addAdvice(OrderService.class, advice);
    }

    @Override
    public void stopped() {
        if (advice != null) {
            Context.removeAdvice(EncounterService.class, advice);
            Context.removeAdvice(ObsService.class, advice);
            Context.removeAdvice(OrderService.class, advice);
            advice = null;
        }
    }
}
