package com.github.h3yow.privacyreleasegatewayjava.model;

import java.util.List;

/**
 * 网关判定结论。
 *
 * <p>allowed=false 时 rows 为空且 rejectionCode 给出<b>可区分</b>的拒绝原因；
 * 被拒绝的请求预算不变化（budgetCost=0，budgetBefore == budgetRemaining）。
 * 所有字段在重复请求下保持不变，结论可复现。</p>
 */
public record QueryResponse(
        String requestId,
        String idempotencyKey,
        boolean allowed,
        boolean degraded,
        int strategyVersion,
        RejectionCode rejectionCode,
        String rejectionDetail,
        double budgetBefore,
        double budgetCost,
        double budgetRemaining,
        String decisionBasis,
        List<ResultRow> rows
) {
}
