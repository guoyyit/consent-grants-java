package com.github.guoyyit.consentgrantsjava.consent.api;

import com.github.guoyyit.consentgrantsjava.consent.domain.AccessPrincipal;
import com.github.guoyyit.consentgrantsjava.consent.domain.ConsentRecord;
import com.github.guoyyit.consentgrantsjava.consent.service.ConsentQueryService;
import com.github.guoyyit.consentgrantsjava.consent.service.ConsentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * User-facing consent endpoints: submit, revoke, read.
 */
@RestController
@RequestMapping("/api/consents")
public class ConsentController {

    public record SubmitRequest(long scopeVersion, Set<String> grant, Set<String> deny) {
    }

    public record ConsentView(long scopeVersion, Set<String> grantedItems,
                              Set<String> deniedItems, String status, long revision) {
        static ConsentView of(ConsentRecord r) {
            return new ConsentView(r.scopeVersion(), r.grantedItems(), r.deniedItems(),
                    r.status().name(), r.revision());
        }
    }

    private final ConsentService consents;
    private final ConsentQueryService queries;
    private final PrincipalFactory principals;

    public ConsentController(ConsentService consents, ConsentQueryService queries,
                             PrincipalFactory principals) {
        this.consents = consents;
        this.queries = queries;
        this.principals = principals;
    }

    @PostMapping("/{userId}/{clientId}")
    public ResponseEntity<Map<String, Object>> submit(
            @PathVariable String userId,
            @PathVariable String clientId,
            @RequestBody SubmitRequest request) {
        ConsentService.SubmitResult result = consents.submit(userId, clientId,
                request.scopeVersion(), request.grant(), request.deny());
        return ResponseEntity.ok(Map.of(
                "changed", result.changed(),
                "replay", result.replay(),
                "consent", ConsentView.of(result.record())));
    }

    @DeleteMapping("/{userId}/{clientId}")
    public ResponseEntity<ConsentView> revoke(@PathVariable String userId,
                                              @PathVariable String clientId) {
        return ResponseEntity.ok(ConsentView.of(consents.revoke(userId, clientId)));
    }

    @GetMapping("/{userId}/{clientId}")
    public ResponseEntity<?> read(@PathVariable String userId,
                                  @PathVariable String clientId,
                                  @RequestHeader(PrincipalFactory.SUBJECT_HEADER) String subject,
                                  @RequestHeader(value = PrincipalFactory.ROLE_HEADER, required = false) String role) {
        AccessPrincipal principal = principals.from(subject, role);
        ConsentQueryService.ReadResult result = queries.read(principal, userId, clientId);
        if (result instanceof ConsentQueryService.ReadResult.Allowed allowed) {
            return ResponseEntity.ok(ConsentView.of(allowed.record()));
        }
        if (result instanceof ConsentQueryService.ReadResult.Forbidden forbidden) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "FORBIDDEN", "message", forbidden.reason()));
        }
        return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
    }
}
