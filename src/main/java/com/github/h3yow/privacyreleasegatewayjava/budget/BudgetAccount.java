package com.github.h3yow.privacyreleasegatewayjava.budget;

/**
 * 单个（数据集+维度）预算账户。
 * 余额检查与扣减在同一内置锁上原子完成：并发请求不可能超扣。
 * 扣减成功与否通过返回的扣减快照表达，调用方据此生成结果或拒绝结论。
 */
public class BudgetAccount {

    private final BudgetKey key;
    private final double total;
    private double remaining;
    private double spent;

    public BudgetAccount(BudgetKey key, double total) {
        if (total <= 0) {
            throw new IllegalArgumentException("budget total must be positive");
        }
        this.key = key;
        this.total = total;
        this.remaining = total;
        this.spent = 0d;
    }

    /**
     * 原子地尝试扣减 delta。
     * @return 扣减快照；余额不足时返回 null，且余额保持不变（拒绝不消耗预算）。
     */
    public synchronized ConsumeResult tryConsume(double delta) {
        if (delta <= 0) {
            throw new IllegalArgumentException("budget delta must be positive");
        }
        double before = this.remaining;
        if (delta > before) {
            return null;
        }
        this.remaining = before - delta;
        this.spent += delta;
        return new ConsumeResult(key, total, before, delta, remaining, spent);
    }

    public synchronized BudgetSnapshot snapshot() {
        return new BudgetSnapshot(key, total, remaining, spent);
    }

    public BudgetKey key() {
        return key;
    }

    /** 一次成功扣减后的不可变状态视图。 */
    public record ConsumeResult(BudgetKey key, double total, double before,
                                double delta, double remaining, double spent) {
    }

    /** 账户只读快照。 */
    public record BudgetSnapshot(BudgetKey key, double total, double remaining, double spent) {
    }
}
