package com.github.guoyyit.consentgrantsjava.consent.domain;

import java.util.List;

/**
 * A single fine-grained permission item inside a scope definition version.
 *
 * @param code       stable business code of the permission item
 * @param status     lifecycle status within this version
 * @param replacedBy when status is SPLIT, the items this one was split into (empty otherwise)
 */
public record PermissionItem(String code, PermissionItemStatus status, List<String> replacedBy) {

    public PermissionItem {
        replacedBy = replacedBy == null ? List.of() : List.copyOf(replacedBy);
    }

    public static PermissionItem active(String code) {
        return new PermissionItem(code, PermissionItemStatus.ACTIVE, List.of());
    }

    public static PermissionItem split(String code, List<String> replacedBy) {
        return new PermissionItem(code, PermissionItemStatus.SPLIT, replacedBy);
    }

    public static PermissionItem deprecated(String code) {
        return new PermissionItem(code, PermissionItemStatus.DEPRECATED, List.of());
    }
}
