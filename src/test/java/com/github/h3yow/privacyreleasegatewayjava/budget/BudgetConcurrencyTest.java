package com.github.h3yow.privacyreleasegatewayjava.budget;

import com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发预算扣减边界测试：
 * 高并发下预算总量不得超扣、丢失或重复计算；成功/失败原因可区分；失败不留流水。
 */
class BudgetConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(BudgetConcurrencyTest.class);

    @Test
    void concurrentDeductionsNeverOverspend() throws Exception {
        double initial = 10.0d;
        BudgetStore store = new BudgetStore(initial);
        BudgetKey key = new BudgetKey("customers", List.of("city"));

        int threads = 50;
        double cost = 1.0d; // 恰好 10 个请求应成功
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<BudgetStore.DeductResult> results = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger exhaustedCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String rid = "req-budget-" + idx;
                    BudgetStore.DeductResult r = store.deduct(key, cost, rid, "idem-" + idx);
                    results.add(r);
                    if (r.success()) {
                        successCount.incrementAndGet();
                        log.info("[{}] 预算扣减成功 before={} cost={} after={}",
                                rid, r.before(), cost, r.after());
                    } else {
                        exhaustedCount.incrementAndGet();
                        log.info("[{}] 预算拒绝 code={} detail={} remaining={}",
                                rid, r.reason(), r.detail(), r.after());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "所有并发请求应在超时前完成");
        pool.shutdown();

        assertEquals(10, successCount.get(), "初始预算 10、单价 1：恰好 10 个成功");
        assertEquals(40, exhaustedCount.get(), "其余 40 个应被拒绝");
        assertEquals(0.0d, store.remaining(key), 1e-9, "预算应恰好耗尽，不超扣");
        for (BudgetStore.DeductResult r : results) {
            if (!r.success()) {
                assertEquals(RejectionCode.BUDGET_EXHAUSTED, r.reason(), "余额不足原因可区分");
                assertEquals(r.before(), r.after(), 1e-12, "失败请求前后余额不变");
            }
        }
        List<LedgerEntry> ledger = store.ledger(key);
        assertEquals(10, ledger.size(), "只有成功扣减写入流水，无重复无丢失");
        double summed = ledger.stream().mapToDouble(LedgerEntry::cost).sum();
        assertEquals(initial, summed, 1e-9, "流水成本之和必须等于初始预算总量");
        for (int i = 1; i < ledger.size(); i++) {
            assertTrue(ledger.get(i).before() <= ledger.get(i - 1).before(),
                    "流水 before 必须单调不增");
            assertEquals(ledger.get(i - 1).after(), ledger.get(i).before(), 1e-12,
                    "相邻流水必须首尾相接，状态视图连续可解释");
        }
        log.info("并发判定依据: threads={}, success={}, exhausted={}, ledgerSize={}, finalRemaining={}",
                threads, successCount.get(), exhaustedCount.get(), ledger.size(), store.remaining(key));
    }

    @Test
    void costAboveTotalIsDistinguishedFromExhausted() {
        BudgetStore store = new BudgetStore(2.0d);
        BudgetKey key = new BudgetKey("ds", List.of());
        BudgetStore.DeductResult r = store.deduct(key, 5.0d, "req-range", null);
        assertFalse(r.success());
        assertEquals(RejectionCode.BUDGET_OUT_OF_RANGE, r.reason(),
                "单价超过账户总量应归类 BUDGET_OUT_OF_RANGE，与 BUDGET_EXHAUSTED 可区分");
        assertEquals(0, store.ledger(key).size(), "超范围请求不留流水、不扣预算");
        log.info("[{}] 超范围拒绝 code={} detail={}", "req-range", r.reason(), r.detail());

        store.deduct(key, 1.5d, "req-ok", null);
        BudgetStore.DeductResult r2 = store.deduct(key, 1.0d, "req-exh", null);
        assertEquals(RejectionCode.BUDGET_EXHAUSTED, r2.reason(),
                "余额不足但单价合法应归类 BUDGET_EXHAUSTED");
        log.info("[{}] 预算耗尽拒绝 code={} detail={}", "req-exh", r2.reason(), r2.detail());
    }
}
