package org.openmrs.module.sihsalusaudit.api.impl;

import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.sihsalusaudit.api.AuditSecurityContext;

public class OpenmrsAuditSecurityContext implements AuditSecurityContext {

    @Override
    public User requireAuthenticatedUser() {
        if (!Context.isAuthenticated()) {
            throw new APIAuthenticationException("Authentication is required");
        }

        User actor = Context.getAuthenticatedUser();
        if (actor == null) {
            throw new APIAuthenticationException("The authenticated user is not authorized");
        }
        return actor;
    }

    @Override
    public User requireAuthenticatedUserWithPrivilege(String privilege) {
        User actor = requireAuthenticatedUser();
        if (!actor.hasPrivilege(privilege)) {
            throw new APIAuthenticationException("The authenticated user is not authorized");
        }
        return actor;
    }
}
