package com.github.guoyyit.consentgrantsjava.web.dto;

import com.github.guoyyit.consentgrantsjava.domain.EffectiveItem;

public record EffectiveItemView(String key, String decision, String source, String note) {
    public static EffectiveItemView from(EffectiveItem item) {
        return new EffectiveItemView(item.key(),
                item.decision() == null ? null : item.decision().name(),
                item.source(), item.note());
    }
}
