package com.github.h3yow.privacyreleasegatewayjava.tests;

import com.github.h3yow.privacyreleasegatewayjava.data.DataRow;
import com.github.h3yow.privacyreleasegatewayjava.data.DatasetRepository;
import com.github.h3yow.privacyreleasegatewayjava.engine.PrivacyGateway;
import com.github.h3yow.privacyreleasegatewayjava.model.AggregationType;
import com.github.h3yow.privacyreleasegatewayjava.model.GatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
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
 * 端到端预算边界：串行耗尽后可区分地拒绝且拒绝不扣；
 * 并发的不同 requestId 抢同一预算键时，成功数严格受总额约束，绝不超扣。
 */
@SpringBootTest
class BudgetExhaustionIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BudgetExhaustionIntegrationTest.class);

    @Autowired
    private PrivacyGateway gateway;
    @Autowired
    private DatasetRepository repository;
    @Autowired
    private PolicyRegistry registry;

    private String dataset;
    private String policy;

    @BeforeEach
    void setUp() {
        dataset = "exh-" + UUID.randomUUID();
        policy = dataset + "-p";
        Set<String> roles = Set.of("analyst");
        repository.registerDataset(dataset, List.of(
                new DataRow("e1", Map.of("region", "us", "amount", 100)),
                new DataRow("e2", Map.of("region", "us", "amount", 200)),
                new DataRow("e3", Map.of("region", "us", "amount", 300))
        ));
        // epsilon=1.0，默认账户总额 3.0 => 恰好允许 3 次。
        registry.register(new MaskingPolicy(policy, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);
    }

    private QueryRequest req(String id) {
        return new QueryRequest(id, dataset, Map.of("region", "us"), "amount",
                AggregationType.SUM, "analyst", policy);
    }

    @Test
    void budgetExhaustsAfterThreeQueriesAndRejectsAfterwards() {
        double expectedRemaining = 3.0d;
        for (int i = 1; i <= 3; i++) {
            GatewayResponse response = gateway.submit(req("exh-seq-" + i + "-" + UUID.randomUUID()));
            expectedRemaining -= 1.0d;
            log.info("requestId={} 判定={} 预算变化 {}-{}={} value={}",
                    response.getRequestId(), response.getStatus(),
                    response.getBudgetBefore(), response.getBudgetDelta(),
                    response.getBudgetRemaining(), response.getValue());
            assertEquals(ReleaseStatus.ALLOWED, response.getStatus());
            assertEquals(expectedRemaining, response.getBudgetRemaining(), 1e-9);
        }

        GatewayResponse rejected = gateway.submit(req("exh-seq-rej-" + UUID.randomUUID()));
        log.info("requestId={} 判定=REJECTED code={} 预算变化={} 依据={}",
                rejected.getRequestId(), rejected.getErrorCode(),
                rejected.getBudgetDelta(), rejected.getDecisionBasis());
        assertEquals(ReleaseStatus.REJECTED, rejected.getStatus());
        assertEquals("budget_exhausted", rejected.getErrorCode());
        assertEquals(0d, rejected.getBudgetDelta());

        // 再拒绝一次仍然不扣，原因可复现。
        GatewayResponse rejectedAgain = gateway.submit(req("exh-seq-rej2-" + UUID.randomUUID()));
        assertEquals("budget_exhausted", rejectedAgain.getErrorCode());
        assertEquals(0d, rejectedAgain.getBudgetDelta());
    }

    @Test
    void concurrentDistinctRequestsNeverOverdrawEndToEnd() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        ConcurrentLinkedQueue<Double> spend = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    GatewayResponse response = gateway.submit(
                            req("exh-conc-" + idx + "-" + UUID.randomUUID()));
                    if (response.getStatus() == ReleaseStatus.ALLOWED) {
                        allowed.incrementAndGet();
                        spend.add(response.getBudgetDelta());
                    } else {
                        exhausted.incrementAndGet();
                    }
                    log.info("requestId={} 判定={} code={} 预算变化={}",
                            response.getRequestId(), response.getStatus(),
                            response.getErrorCode(), response.getBudgetDelta());
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

        double totalSpend = spend.stream().mapToDouble(Double::doubleValue).sum();
        log.info("并发汇总 allowed={} budget_exhausted={} 总扣减={}",
                allowed.get(), exhausted.get(), totalSpend);

        assertEquals(3, allowed.get(), "总额 3.0 / 单价 1.0 => 仅 3 个请求可成功");
        assertEquals(17, exhausted.get());
        assertEquals(3.0d, totalSpend, 1e-9, "成功扣减合计不得超过总额");
    }
}
