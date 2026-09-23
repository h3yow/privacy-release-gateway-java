package com.github.h3yow.privacyreleasegatewayjava.policy;

/**
 * 单个字段在某策略版本下的规则：允许的角色、变换方式、
 * 是否允许作为聚合度量字段、是否允许作为分组维度。
 */
public record FieldPolicy(
        String field,
        java.util.Set<String> allowedRoles,
        FieldTransform transform,
        boolean metricAllowed,
        boolean dimensionAllowed
) {
}
