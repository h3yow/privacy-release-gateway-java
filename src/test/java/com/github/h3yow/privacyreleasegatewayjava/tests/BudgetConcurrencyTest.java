package com.github.h3yow.privacyreleasegatewayjava.tests;

import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetAccount;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetLedger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发预算扣减边界：总额不得超扣、丢失或重复计算；余额不足的请求不消耗预算。
 */
class BudgetConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(BudgetConcurrencyTest.class);

    @Test
    void concurrentConsumeNeverOverdraws() throws Exception {
        BudgetLedger ledger = new BudgetLedger();
        BudgetKey key = BudgetKey.of("sales", java.util.Map.of("region", "us"));

        int threads = 50;
        double delta = 0.1d;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<Double> deltas = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    BudgetAccount.ConsumeResult result = ledger.consume(key, delta, 1.0d);
                    if (result != null) {
                        success.incrementAndGet();
                        deltas.add(result.delta());
                        log.info("requestId=budget-conc-{} 判定=ALLOWED 预算变化 key={} {}->{}(-{})",
                                idx, key.canonical(),
                                String.format("%.4f", result.before()),
                                String.format("%.4f", result.remaining()),
                                String.format("%.4f", result.delta()));
                    } else {
                        rejected.incrementAndGet();
                        log.info("requestId=budget-conc-{} 判定=REJECTED 预算变化=0 依据=budget_exhausted", idx);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        BudgetAccount.BudgetSnapshot snapshot = ledger.snapshot(key, 1.0d);
        double sumDelta = deltas.stream().mapToDouble(Double::doubleValue).sum();

        log.info("并发汇总 成功={} 拒绝={} 已扣合计={} 账户剩余={} 账户总额={}",
                success.get(), rejected.get(),
                String.format("%.4f", sumDelta),
                String.format("%.4f", snapshot.remaining()),
                String.format("%.4f", snapshot.total()));

        assertEquals(10, success.get(), "恰好只允许扣满 10 次（1.0 / 0.1）");
        assertEquals(40, rejected.get());
        assertEquals(1.0d, sumDelta, 1e-9, "成功扣减之和必须等于总额");
        assertEquals(0.0d, snapshot.remaining(), 1e-9, "余额必须恰好耗尽");
        assertEquals(1.0d, snapshot.spent(), 1e-9);
    }
}
