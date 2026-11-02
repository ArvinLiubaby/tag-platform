package com.tagplatform.meta.model;

import java.util.regex.Pattern;

/**
 * 对象类型注册表（object_type_meta 表映射）。
 */
public record ObjectTypeMeta(
        String objectType,
        String typeName,
        String idField,
        String idPattern,
        String objectClass,
        int defaultTtl,
        String status) {

    public boolean enabled() {
        return "enabled".equalsIgnoreCase(status);
    }

    /** 对象 ID 格式校验（未配置 pattern 时仅要求非空） */
    public boolean idMatches(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return false;
        }
        return idPattern == null || idPattern.isBlank() || Pattern.matches(idPattern, objectId);
    }
}
