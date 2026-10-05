package com.github.guoyyit.consentgrantsjava.web.dto;

import com.github.guoyyit.consentgrantsjava.domain.EffectiveConsent;

import java.util.List;

public record ConsentView(String userId,
                          String clientId,
                          String scopeVersion,
                          String state,
                          long versionSeq,
                          List<EffectiveItemView> items) {

    public static ConsentView from(EffectiveConsent c) {
        return new ConsentView(c.userId(), c.clientId(), c.scopeVersion(), c.state().name(),
                c.versionSeq(), c.items().stream().map(EffectiveItemView::from).toList());
    }
}
