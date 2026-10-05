package com.github.guoyyit.consentgrantsjava.consent.domain;

/**
 * Who is calling. Used to enforce that user-facing reads only see their own
 * consent details while admins see the scope they are allowed to see.
 */
public record AccessPrincipal(String subject, AuditEvent.ActorRole role) {

    public static AccessPrincipal user(String userId) {
        return new AccessPrincipal(userId, AuditEvent.ActorRole.USER);
    }

    public static AccessPrincipal admin(String adminId) {
        return new AccessPrincipal(adminId, AuditEvent.ActorRole.ADMIN);
    }

    public static AccessPrincipal system() {
        return new AccessPrincipal("system", AuditEvent.ActorRole.SYSTEM);
    }

    public boolean isAdmin() {
        return role == AuditEvent.ActorRole.ADMIN;
    }
}
