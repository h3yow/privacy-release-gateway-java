package com.github.h3yow.privacyreleasegatewayjava.policy;

/**
 * 脱敏/最小化变换类型。
 */
public enum FieldTransform {
    NONE,       // 原值返回（公开字段）
    MASK,       // 部分掩码（如身份证、姓名）
    GENERALIZE, // 泛化（如年龄分桶、地址截断到城市）
    SUPPRESS,   // 抑制：不发布
    HASH        // 稳定哈希（用于复现）
}
