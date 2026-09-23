package com.github.h3yow.privacyreleasegatewayjava.budget;

import java.time.Instant;

/**
 * 预算流水。只在成功扣减时追加；拒绝请求不留流水、不扣预算。
 */
public record LedgerEntry(
        String requestId,
        String idempotencyKey,
        BudgetKey key,
        double before,
        double cost,
        double after,
        Instant at
) {
}
