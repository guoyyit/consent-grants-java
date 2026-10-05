package com.github.guoyyit.consentgrantsjava.domain;

/**
 * One entry of an explainable effective-consent view.
 *
 * @param source   EXPLICIT | MIGRATED_EQUIVALENT | SPLIT_AMBIGUOUS | DROPPED_RETIRED
 * @param decision null when the effective outcome is "no grant" rather than an explicit deny
 */
public record EffectiveItem(String key, Decision decision, String source, String note) {
}
