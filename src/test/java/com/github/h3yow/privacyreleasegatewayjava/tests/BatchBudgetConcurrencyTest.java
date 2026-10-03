package com.github.h3yow.privacyreleasegatewayjava.tests;

import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetAccount;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetLedger;
import com.github.h3yow.privacyreleasegatewayjava.data.DataRow;
import com.github.h3yow.privacyreleasegatewayjava.data.DatasetRepository;
import com.github.h3yow.privacyreleasegatewayjava.engine.PrivacyGateway;
import com.github.h3yow.privacyreleasegatewayjava.model.AggregationType;
import com.github.h3yow.privacyreleasegatewayjava.model.BatchGatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.BatchQueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.GatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.ReleaseStatus;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldRule;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldTransform;
import com.github.h3yow.privacyreleasegatewayjava.policy.MaskingPolicy;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量并发预算边界：并发批量请求共享同一预算键时，
 * 整批成功扣减之和必须恰好等于账户实际扣减，绝不超扣；
 * 预算不足的分组被确定性拒绝且零扣减，不影响同批其它分组。
 */
@SpringBootTest
class BatchBudgetConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(BatchBudgetConcurrencyTest.class);

    @Autowired
    private PrivacyGateway gateway;
    @Autowired
    private DatasetRepository repository;
    @Autowired
    private PolicyRegistry registry;
    @Autowired
    private BudgetLedger budgetLedger;

    private String dataset;
    private String policy;
    private Map<String, String> dims;

    @BeforeEach
    void setUp() {
        dataset = "bconc-" + UUID.randomUUID();
        policy = dataset + "-p";
        dims = Map.of("region", "us");
        Set<String> roles = Set.of("analyst");
        repository.registerDataset(dataset, List.of(
                new DataRow("c1", Map.of("region", "us", "amount", 100)),
                new DataRow("c2", Map.of("region", "us", "amount", 200)),
                new DataRow("c3", Map.of("region", "us", "amount", 300))
        ));
        // epsilon=1.0，账户总额 3.0 => 全并发下恰好允许 3 次成功扣减。
        registry.register(new MaskingPolicy(policy, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);
    }

    @Test
    void concurrentBatchesSharingOneBudgetKeyNeverOverdraw() throws Exception {
        int batches = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(batches);
        AtomicInteger succeededGroups = new AtomicInteger();
        AtomicInteger rejectedGroups = new AtomicInteger();
        ConcurrentLinkedQueue<Double> deltas = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < batches; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String requestId = "bconc-" + idx + "-" + UUID.randomUUID();
                    BatchGatewayResponse batch = gateway.submitBatch(new BatchQueryRequest(
                            requestId, dataset, List.of(dims), "amount",
                            AggregationType.SUM, "analyst", policy));
                    GatewayResponse g = batch.getResults().get(0);
                    if (g.getStatus() == ReleaseStatus.ALLOWED) {
                        succeededGroups.incrementAndGet();
                        deltas.add(g.getBudgetDelta());
                    } else {
                        rejectedGroups.incrementAndGet();
                    }
                    log.info("requestId={} 判定={} code={} 预算变化=-{} 依据={}",
                            requestId, g.getStatus(), g.getErrorCode(),
                            g.getBudgetDelta(), g.getDecisionBasis());
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

        BudgetAccount.BudgetSnapshot snapshot =
                budgetLedger.snapshot(BudgetKey.of(dataset, dims), 3.0d);
        double sumDelta = deltas.stream().mapToDouble(Double::doubleValue).sum();

        log.info("并发批量汇总 成功分组={} 拒绝分组={} 响应扣减合计=-{} 账本已扣=-{} 剩余={}",
                succeededGroups.get(), rejectedGroups.get(), sumDelta,
                snapshot.spent(), snapshot.remaining());

        assertEquals(3, succeededGroups.get(), "总额 3.0 / epsilon 1.0，恰好允许 3 个成功分组");
        assertEquals(batches - 3, rejectedGroups.get());
        assertEquals(3.0d, sumDelta, 1e-9, "成功分组扣减之和必须等于总额");
        assertEquals(sumDelta, snapshot.spent(), 1e-9, "响应数字必须与账本对得上");
        assertEquals(0.0d, snapshot.remaining(), 1e-9, "不得超扣");
    }
}
