package com.github.guoyyit.consentgrantsjava.web;

import com.github.guoyyit.consentgrantsjava.repo.RevocationRepository;
import com.github.guoyyit.consentgrantsjava.service.RevocationPropagationService;
import com.github.guoyyit.consentgrantsjava.web.dto.PropagationView;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Admin-only observability and manual convergence of revocation propagation. */
@RestController
@RequestMapping("/admin/revocations")
public class AdminPropagationController {

    private final RevocationRepository revocationRepository;
    private final RevocationPropagationService propagationService;

    public AdminPropagationController(RevocationRepository revocationRepository,
                                      RevocationPropagationService propagationService) {
        this.revocationRepository = revocationRepository;
        this.propagationService = propagationService;
    }

    @GetMapping
    public List<PropagationView> recent(@RequestParam(defaultValue = "100") int limit) {
        return revocationRepository.findRecent(limit).stream().map(PropagationView::from).toList();
    }

    @PostMapping("/converge")
    public Map<String, Integer> converge() {
        return java.util.Map.of("processed", propagationService.processPendingBatch());
    }
}
