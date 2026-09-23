package com.github.h3yow.privacyreleasegatewayjava.model;

import java.util.List;

/**
 * 一次数据发布请求。strategyVersion 为空表示使用当前生效策略版本；
 * idempotencyKey 用于重复请求识别，同一键只计算、只扣预算一次。
 */
public record QueryRequest(
        String requestId,
        String idempotencyKey,
        String dataset,
        List<String> dimensions,
        List<MetricSpec> metrics,
        List<String> fields,
        String role,
        Integer strategyVersion
) {
}
