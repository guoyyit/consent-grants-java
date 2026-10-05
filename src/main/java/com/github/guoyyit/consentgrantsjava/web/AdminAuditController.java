package com.github.guoyyit.consentgrantsjava.web;

import com.github.guoyyit.consentgrantsjava.service.AuditService;
import com.github.guoyyit.consentgrantsjava.web.dto.AuditView;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin-only audit visibility: every read of any subject, including denials. */
@RestController
@RequestMapping("/admin/audit")
public class AdminAuditController {

    private final AuditService auditService;

    public AdminAuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public List<AuditView> recent(@RequestParam(defaultValue = "100") int limit) {
        return auditService.recent(limit).stream().map(AuditView::from).toList();
    }

    @GetMapping("/users/{userId}")
    public List<AuditView> forUser(@PathVariable String userId,
                                   @RequestParam(defaultValue = "100") int limit) {
        return auditService.forSubject(userId, limit).stream().map(AuditView::from).toList();
    }
}
