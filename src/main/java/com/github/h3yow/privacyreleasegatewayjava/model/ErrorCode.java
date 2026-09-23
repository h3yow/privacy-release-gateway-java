package com.github.h3yow.privacyreleasegatewayjava.model;

/**
 * 对外稳定的错误分类。任何底层异常都必须归一化为本枚举，禁止透出原始异常信息。
 * priority 为确定性归类优先级：同一请求命中多个问题时，取优先级数值最小者。
 */
public enum ErrorCode {

    /** 请求报文无法解析 / 必填字段缺失（解析层问题，最先拦截）。 */
    MALFORMED_REQUEST(10, "malformed_request", "请求格式不正确或缺少必填字段"),

    /** 请求所指数据集不存在。 */
    DATASET_NOT_FOUND(20, "dataset_not_found", "数据集不存在"),

    /** 策略版本不存在，无法保证结论可复现。 */
    POLICY_VERSION_NOT_FOUND(30, "policy_version_not_found", "策略版本不存在"),

    /** 调用方角色无权访问所请求指标 / 字段。 */
    ACCESS_DENIED(40, "access_denied", "无权访问该数据项"),

    /** 同一策略内部对同一字段存在相互冲突的规则。 */
    POLICY_CONFLICT(50, "policy_conflict", "策略规则冲突，拒绝执行"),

    /** 脱敏所需字段在数据中缺失，且无法降级。 */
    FIELD_MISSING(60, "field_missing", "所需字段缺失且无法降级"),

    /** 聚合分组过小，存在重标识风险，超出允许范围。 */
    GROUP_TOO_SMALL(70, "group_too_small", "分组规模低于最小阈值"),

    /** 隐私预算余额不足以覆盖本次扣减。 */
    BUDGET_EXHAUSTED(80, "budget_exhausted", "隐私预算不足"),

    /** 底层解析 / 策略求值 / 加噪等内部失败的统一兜底。 */
    INTERNAL_PROCESSING_ERROR(90, "internal_processing_error", "内部处理失败，请稍后重试");

    private final int priority;
    private final String code;
    private final String safeMessage;

    ErrorCode(int priority, String code, String safeMessage) {
        this.priority = priority;
        this.code = code;
        this.safeMessage = safeMessage;
    }

    public int priority() {
        return priority;
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
