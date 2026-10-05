package com.github.guoyyit.consentgrantsjava.consent.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, re-drives every revocation job that was left unfinished by a
 * crash or restart, so revocation propagation always converges.
 */
@Component
public class RevocationRecoveryRunner implements ApplicationRunner {

    private final RevocationPropagationService propagation;

    public RevocationRecoveryRunner(RevocationPropagationService propagation) {
        this.propagation = propagation;
    }

    @Override
    public void run(ApplicationArguments args) {
        propagation.recoverUnfinished();
    }
}
