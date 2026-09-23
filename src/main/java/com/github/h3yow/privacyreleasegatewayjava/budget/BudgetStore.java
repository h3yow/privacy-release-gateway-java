package com.github.h3yow.privacyreleasegatewayjava.budget;

import com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 预算存储：每个 {@link BudgetKey} 一个账户，账户状态以不可变快照 + CAS 更新。
 *
 * <p>并发语义：</p>
 * <ul>
 *   <li>余额不足时不发生任何写入（无流水），返回可区分原因；</li>
 *   <li>扣减单价超出账户初始预算（物理上永远无法满足）归类为 BUDGET_OUT_OF_RANGE；</li>
 *   <li>只是当前余额不足归类为 BUDGET_EXHAUSTED；</li>
 *   <li>高并发下 CAS 自旋保证总量既不超扣也不丢失/重复计算。</li>
 * </ul>
 */
public class BudgetStore {

    private static final class Ref {
        final AtomicReference<BudgetAccount> account;

        Ref(double initial) {
            this.account = new AtomicReference<>(BudgetAccount.open(initial));
        }
    }

    private final double initialBudget;
    private final ConcurrentHashMap<BudgetKey, Ref> accounts = new ConcurrentHashMap<>();

    public BudgetStore(double initialBudget) {
        if (initialBudget <= 0) {
            throw new IllegalArgumentException("initialBudget must be positive");
        }
        this.initialBudget = initialBudget;
    }

    public double remaining(BudgetKey key) {
        return accounts.computeIfAbsent(key, k -> new Ref(initialBudget)).account.get().remaining();
    }

    /**
     * 原子扣减。失败时账户快照与流水均不变化。
     */
    public DeductResult deduct(BudgetKey key, double cost, String requestId, String idempotencyKey) {
        if (cost <= 0 || Double.isNaN(cost) || Double.isInfinite(cost)) {
            return new DeductResult(false, RejectionCode.BUDGET_OUT_OF_RANGE,
                    "非法预算单价: " + cost, 0d, 0d, false);
        }
        Ref ref = accounts.computeIfAbsent(key, k -> new Ref(initialBudget));
        while (true) {
            BudgetAccount cur = ref.account.get();
            double before = cur.remaining();
            if (cost > cur.initial()) {
                // 单价超过账户总量：无论历史扣减如何都不可能满足。
                return new DeductResult(false, RejectionCode.BUDGET_OUT_OF_RANGE,
                        "请求预算单价 " + cost + " 超过账户总量 " + cur.initial(),
                        before, before, false);
            }
            if (cost > before) {
                return new DeductResult(false, RejectionCode.BUDGET_EXHAUSTED,
                        "预算不足: 需要 " + cost + ", 剩余 " + before,
                        before, before, false);
            }
            double after = before - cost;
            LedgerEntry entry = new LedgerEntry(requestId, idempotencyKey, key,
                    before, cost, after, Instant.now());
            List<LedgerEntry> nextLedger = new ArrayList<>(cur.ledger().size() + 1);
            nextLedger.addAll(cur.ledger());
            nextLedger.add(entry);
            BudgetAccount next = new BudgetAccount(after, cur.initial(), List.copyOf(nextLedger));
            if (ref.account.compareAndSet(cur, next)) {
                return new DeductResult(true, null, null, before, after, false);
            }
            // CAS 失败：有并发扣减，基于最新快照重试判定。
        }
    }


    /**
     * 补偿性退还：结果生成阶段发生内部失败时调用。原子地把 cost 加回账户，
     * 并追加一条 cost 为负的流水，使“被拒绝的请求不消耗预算”且状态全程可解释。
     */
    public DeductResult refund(BudgetKey key, double cost, String requestId, String idempotencyKey) {
        Ref ref = accounts.computeIfAbsent(key, k -> new Ref(initialBudget));
        while (true) {
            BudgetAccount cur = ref.account.get();
            double before = cur.remaining();
            double after = Math.min(before + cost, cur.initial());
            LedgerEntry entry = new LedgerEntry(requestId, idempotencyKey, key,
                    before, -cost, after, Instant.now());
            List<LedgerEntry> nextLedger = new ArrayList<>(cur.ledger().size() + 1);
            nextLedger.addAll(cur.ledger());
            nextLedger.add(entry);
            BudgetAccount next = new BudgetAccount(after, cur.initial(), List.copyOf(nextLedger));
            if (ref.account.compareAndSet(cur, next)) {
                return new DeductResult(true, null, null, before, after, false);
            }
        }
    }

    public List<LedgerEntry> ledger(BudgetKey key) {
        Ref ref = accounts.get(key);
        return ref == null ? List.of() : List.copyOf(ref.account.get().ledger());
    }

    public Map<BudgetKey, Double> snapshot() {
        Map<BudgetKey, Double> out = new java.util.TreeMap<>(
                java.util.Comparator.comparing(BudgetKey::toString));
        for (Map.Entry<BudgetKey, Ref> e : accounts.entrySet()) {
            out.put(e.getKey(), e.getValue().account.get().remaining());
        }
        return out;
    }

    /** 扣减结果。duplicate 由上层幂等视图设置，预算层本身不识别幂等键。 */
    public record DeductResult(boolean success,
                               RejectionCode reason,
                               String detail,
                               double before,
                               double after,
                               boolean duplicate) {
    }
}
