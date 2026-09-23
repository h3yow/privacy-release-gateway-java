package com.github.h3yow.privacyreleasegatewayjava.budget;

import java.util.List;

/**
 * 单个预算账户的不可变快照：剩余额度 + 成功扣减流水。
 *
 * <p>每次成功扣减都会产生一个新快照（旧快照保留给调用方展示 before/after），
 * 配合 {@code AtomicReference} 的 CAS 实现无锁原子扣减。</p>
 */
public record BudgetAccount(double remaining, double initial, List<LedgerEntry> ledger) {

    public static BudgetAccount open(double initialBudget) {
        return new BudgetAccount(initialBudget, initialBudget, List.of());
    }
}
