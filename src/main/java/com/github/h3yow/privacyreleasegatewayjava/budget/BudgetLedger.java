package com.github.h3yow.privacyreleasegatewayjava.budget;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 预算账本：按 BudgetKey 维护账户。账户懒创建，ConcurrentHashMap 保证并发下每个键只有一个账户；
 * 真正的“检查+扣减”原子性由 {@link BudgetAccount} 的内置锁保证，因此总额不可能被超扣、丢失或重复计算。
 */
@Component
public class BudgetLedger {

    private final ConcurrentHashMap<BudgetKey, BudgetAccount> accounts = new ConcurrentHashMap<>();

    /**
     * 在指定键上原子扣减；账户不存在时以 defaultTotal 初始化。
     * @return 扣减快照；余额不足返回 null（不产生任何扣减）。
     */
    public BudgetAccount.ConsumeResult consume(BudgetKey key, double delta, double defaultTotal) {
        return accountFor(key, defaultTotal).tryConsume(delta);
    }

    public BudgetAccount.BudgetSnapshot snapshot(BudgetKey key, double defaultTotal) {
        return accountFor(key, defaultTotal).snapshot();
    }

    public void reset() {
        accounts.clear();
    }

    public int accountCount() {
        return accounts.size();
    }

    private BudgetAccount accountFor(BudgetKey key, double defaultTotal) {
        return accounts.computeIfAbsent(key, k -> new BudgetAccount(k, defaultTotal));
    }
}
