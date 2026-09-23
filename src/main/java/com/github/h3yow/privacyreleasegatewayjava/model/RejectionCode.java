package com.github.h3yow.privacyreleasegatewayjava.model;

/**
 * 对外稳定的拒绝/错误类型。内部异常一律归一化到这些代码，不透出原始异常信息。
 * 声明顺序即多问题并存时的确定性归类优先级（ordinal 越小优先级越高）。
 */
public enum RejectionCode {
    INVALID_REQUEST,
    UNAUTHORIZED,
    FIELD_MISSING,
    POLICY_CONFLICT,
    BUDGET_OUT_OF_RANGE,
    BUDGET_EXHAUSTED,
    INTERNAL_ERROR
}
