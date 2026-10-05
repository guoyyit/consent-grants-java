package com.github.guoyyit.consentgrantsjava.security;

/**
 * Identification of the caller. A user principal may only read its own consents;
 * admins may read across subjects.
 */
public class AuthContext {

    public enum Role { USER, ADMIN }

    private final String userId;
    private final Role role;

    private AuthContext(String userId, Role role) {
        this.userId = userId;
        this.role = role;
    }

    public static AuthContext user(String userId) {
        return new AuthContext(userId, Role.USER);
    }

    public static AuthContext admin() {
        return new AuthContext(null, Role.ADMIN);
    }

    public String userId() {
        return userId;
    }

    public Role role() {
        return role;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
