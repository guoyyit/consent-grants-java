package com.github.guoyyit.consentgrantsjava.consent.api;

import com.github.guoyyit.consentgrantsjava.consent.domain.AccessPrincipal;
import com.github.guoyyit.consentgrantsjava.consent.domain.AuditEvent;
import org.springframework.stereotype.Component;

/**
 * Builds the caller principal from request headers. In a real deployment this
 * would be backed by authentication; here the headers are the trust boundary
 * and every read is still audited.
 */
@Component
public class PrincipalFactory {

    public static final String SUBJECT_HEADER = "X-Subject";
    public static final String ROLE_HEADER = "X-Role";

    public AccessPrincipal from(String subject, String role) {
        if (subject == null || subject.isBlank()) {
            throw new SecurityException("missing " + SUBJECT_HEADER + " header");
        }
        AuditEvent.ActorRole actorRole = "ADMIN".equalsIgnoreCase(role)
                ? AuditEvent.ActorRole.ADMIN
                : AuditEvent.ActorRole.USER;
        return new AccessPrincipal(subject, actorRole);
    }
}
