package org.openmrs.module.sihsalusaudit.api;

import org.openmrs.User;

public interface AuditSecurityContext {

    User requireAuthenticatedUser();

    User requireAuthenticatedUserWithPrivilege(String privilege);
}
