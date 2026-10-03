package com.github.h3yow.privacyreleasegatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * 批量发布请求：共享数据集 / 指标 / 聚合 / 角色 / 策略版本，
 * groups 中每个元素是一个独立的分组维度组合，逐组独立判定。
 */
public record BatchQueryRequest(
        String requestId,
        String dataset,
        List<Map<String, String>> groups,
        String metric,
        AggregationType aggregation,
        String role,
        String policyVersion) {
}
