package com.github.h3yow.privacyreleasegatewayjava.policy;

import java.util.Set;

/**
 * 针对单个字段的访问 + 脱敏规则。
 * <ul>
 *   <li>allowedRoles：允许读取该字段的角色；角色不在集合内 => 越权。</li>
 *   <li>transform：NONE / MASK / HASH / GENERALIZE / RESTRICT。</li>
 *   <li>maskKeep：MASK 时保留的前缀长度。</li>
 *   <li>generalizeToSeconds：GENERALIZE 时间值时归整到的秒数。</li>
 * </ul>
 * 规则一旦归入某个策略版本即不可变，历史版本结论可长期复现。
 */
public class FieldRule {

    private final String field;
    private final Set<String> allowedRoles;
    private final FieldTransform transform;
    private final int maskKeep;
    private final int generalizeToSeconds;

    public FieldRule(String field, Set<String> allowedRoles, FieldTransform transform) {
        this(field, allowedRoles, transform, 2, 3600);
    }

    public FieldRule(String field, Set<String> allowedRoles, FieldTransform transform,
                     int maskKeep, int generalizeToSeconds) {
        this.field = field;
        this.allowedRoles = Set.copyOf(allowedRoles);
        this.transform = transform;
        this.maskKeep = maskKeep;
        this.generalizeToSeconds = generalizeToSeconds;
    }

    public String field() {
        return field;
    }

    public Set<String> allowedRoles() {
        return allowedRoles;
    }

    public FieldTransform transform() {
        return transform;
    }

    public int maskKeep() {
        return maskKeep;
    }

    public int generalizeToSeconds() {
        return generalizeToSeconds;
    }
}
