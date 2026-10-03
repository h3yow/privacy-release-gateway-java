package com.github.h3yow.privacyreleasegatewayjava.batch;

import com.github.h3yow.privacyreleasegatewayjava.model.AggregationType;

import java.util.List;

/**
 * 批量发布请求：一次携带多个分组条件，共享数据集 / 指标 / 聚合方式 / 角色 / 策略版本。
 * 每个分组独立判定、独立出结论，互不影响。
 *
 * @param requestId     整批的请求标识（幂等粒度 = requestId + 分组）
 * @param dataset       数据集
 * @param groups        分组条件列表，至少一个
 * @param metric        指标字段
 * @param aggregation   聚合方式
 * @param role          调用方角色
 * @param policyVersion 策略版本（可空，空则取当前生效版本）
 */
public record BatchQueryRequest(
        String requestId,
        String dataset,
        List<BatchGroup> groups,
        String metric,
        AggregationType aggregation,
        String role,
        String policyVersion) {
}
