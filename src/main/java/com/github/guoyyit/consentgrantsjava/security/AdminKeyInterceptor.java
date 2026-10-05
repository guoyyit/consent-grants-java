package com.github.guoyyit.consentgrantsjava.security;

import com.github.guoyyit.consentgrantsjava.service.AuditService;
import com.github.guoyyit.consentgrantsjava.domain.AuditResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Gates /admin/** endpoints behind a configured shared secret. Successful authentication
 * publishes an ADMIN {@link AuthContext}; missing/wrong keys are rejected with 401 and
 * recorded as denied audit events so unauthorized access is traceable.
 */
@Component
public class AdminKeyInterceptor implements HandlerInterceptor {

    public static final String AUTH_ATTRIBUTE = "authContext";

    private final String adminKey;
    private final AuditService auditService;

    public AdminKeyInterceptor(@Value("${consent.admin.key:change-me-admin-key}") String adminKey,
                               AuditService auditService) {
        this.adminKey = adminKey;
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String provided = request.getHeader("X-Admin-Key");
        if (provided == null || !provided.equals(adminKey)) {
            auditService.record(
                    provided == null ? "anonymous" : "invalid-admin-key",
                    null, null, "ADMIN_ACCESS_DENIED", AuditResult.DENIED,
                    request.getMethod() + " " + request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"admin key required\"}");
            return false;
        }
        request.setAttribute(AUTH_ATTRIBUTE, AuthContext.admin());
        return true;
    }
}
