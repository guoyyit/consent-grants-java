package com.github.guoyyit.consentgrantsjava.consent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the consent module.
 *
 * <ul>
 *   <li>{@code consent.scope.initial-version} — version number published at
 *       startup when no scope version exists yet (default 1).</li>
 *   <li>{@code consent.scope.initial-items} — permission item codes that make
 *       up the initial scope definition.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "consent.scope")
public class ConsentProperties {

    private long initialVersion = 1L;
    private List<String> initialItems = new ArrayList<>(List.of("read", "write"));

    public long getInitialVersion() {
        return initialVersion;
    }

    public void setInitialVersion(long initialVersion) {
        this.initialVersion = initialVersion;
    }

    public List<String> getInitialItems() {
        return initialItems;
    }

    public void setInitialItems(List<String> initialItems) {
        this.initialItems = initialItems;
    }
}
