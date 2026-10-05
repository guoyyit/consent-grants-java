package com.github.guoyyit.consentgrantsjava.web;

import com.github.guoyyit.consentgrantsjava.domain.ScopeVersionDefinition;
import com.github.guoyyit.consentgrantsjava.service.ScopeDefinitionService;
import com.github.guoyyit.consentgrantsjava.web.dto.ScopePublishRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Admin-only scope-definition evolution endpoints (gated by AdminKeyInterceptor). */
@RestController
@RequestMapping("/admin/scope-versions")
public class AdminScopeController {

    private final ScopeDefinitionService scopeService;

    public AdminScopeController(ScopeDefinitionService scopeService) {
        this.scopeService = scopeService;
    }

    @GetMapping
    public List<String> list() {
        return scopeService.versions();
    }

    @GetMapping("/{version}")
    public ScopeVersionDefinition get(@PathVariable String version) {
        return scopeService.getDefinition(version);
    }

    @PostMapping
    public ScopeVersionDefinition publish(@RequestBody ScopePublishRequest request) {
        return scopeService.publish(new ScopeVersionDefinition(request.version(), request.items()));
    }
}
