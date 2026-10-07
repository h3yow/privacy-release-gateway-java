package com.github.h3yow.privacyreleasegatewayjava.tests;

import com.github.h3yow.privacyreleasegatewayjava.aggregate.AggregationEngine;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetAccount;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetLedger;
import com.github.h3yow.privacyreleasegatewayjava.data.DataRow;
import com.github.h3yow.privacyreleasegatewayjava.data.DatasetRepository;
import com.github.h3yow.privacyreleasegatewayjava.engine.PrivacyGateway;
import com.github.h3yow.privacyreleasegatewayjava.model.AggregationType;
import com.github.h3yow.privacyreleasegatewayjava.model.BatchGatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.BatchQueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.BatchStatus;
import com.github.h3yow.privacyreleasegatewayjava.model.GatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.ReleaseStatus;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldRule;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldTransform;
import com.github.h3yow.privacyreleasegatewayjava.policy.MaskingPolicy;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyEvaluator;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整批预算合计（totalBudgetDelta）的对账口径：
 * 合计只代表“本趟请求真正新扣”的预算，恒等于本趟对账本的实际净变化。
 * <ul>
 *   <li>整批原样重发：本趟一分没扣，合计必须为 0，与预算查询直接对上；</li>
 *   <li>部分分组沿用历史结论（幂等命中）、部分分组本趟重算新扣：合计只算新扣部分；</li>
 *   <li>底层恢复后重发拿到新结论的分组按同样口径计入；</li>
 *   <li>重发不重复扣；并发重发 / 并发新请求共享同一预算键不超扣，
 *       且所有趟次的合计之和恒等于账本累计扣减。</li>
 * </ul>
 */
class BatchBudgetReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(BatchBudgetReconciliationTest.class);
    private static final double BUDGET_TOTAL = 3.0d;

    /** 可切换故障状态的仓库：故障期内仅 region=eu 的分组查询抛错，模拟底层部分抖动。 */
    static class PartiallyFlakyDatasetRepository extends DatasetRepository {
        private final AtomicBoolean euHealthy = new AtomicBoolean(true);

        void failEu() {
            euHealthy.set(false);
        }

        void recoverEu() {
            euHealthy.set(true);
        }

        @Override
        public List<DataRow> matchingRows(String dataset, Map<String, String> dimensions) {
            if (!euHealthy.get() && "eu".equals(dimensions.get("region"))) {
                throw new IllegalStateException("simulated backend outage");
            }
            return super.matchingRows(dataset, dimensions);
        }
    }

    private PartiallyFlakyDatasetRepository repository;
    private BudgetLedger budgetLedger;
    private PrivacyGateway gateway;
    private String dataset;
    private String policy;

    private final Map<String, String> us = Map.of("region", "us");
    private final Map<String, String> eu = Map.of("region", "eu");

    @BeforeEach
    void setUp() {
        dataset = "recon-" + UUID.randomUUID();
        policy = dataset + "-p";
        repository = new PartiallyFlakyDatasetRepository();
        budgetLedger = new BudgetLedger();
        PolicyRegistry registry = new PolicyRegistry();
        repository.registerDataset(dataset, List.of(
                new DataRow("r1", Map.of("region", "us", "amount", 100)),
                new DataRow("r2", Map.of("region", "us", "amount", 200)),
                new DataRow("r3", Map.of("region", "us", "amount", 300)),
                new DataRow("r4", Map.of("region", "eu", "amount", 10)),
                new DataRow("r5", Map.of("region", "eu", "amount", 20)),
                new DataRow("r6", Map.of("region", "eu", "amount", 30))
        ));
        Set<String> roles = Set.of("analyst");
        // epsilon=1.0，账户总额 3.0。
        registry.register(new MaskingPolicy(policy, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);
        gateway = new PrivacyGateway(repository, registry, new PolicyEvaluator(),
                new AggregationEngine(), budgetLedger);
    }

    private BatchQueryRequest batchReq(String requestId, List<Map<String, String>> groups) {
        return new BatchQueryRequest(requestId, dataset, groups, "amount",
                AggregationType.SUM, "analyst", policy);
    }

    private double spent(Map<String, String> dims) {
        BudgetAccount.BudgetSnapshot snap =
                budgetLedger.snapshot(BudgetKey.of(dataset, dims), BUDGET_TOTAL);
        return snap.spent();
    }

    private void logGroup(String requestId, String tag, int idx, GatewayResponse g) {
        log.info("requestId={} {} 分组{} 判定={} code={} deduplicated={} 本组扣减=-{} 依据={}",
                requestId, tag, idx, g.getStatus(), g.getErrorCode(), g.isDeduplicated(),
                g.getBudgetDelta(), g.getDecisionBasis());
    }

    @Test
    void fullBatchResendReportsZeroAndReconcilesWithLedger() {
        String requestId = "recon-full-" + UUID.randomUUID();

        BatchGatewayResponse first = gateway.submitBatch(batchReq(requestId, List.of(us, eu)));
        assertEquals(BatchStatus.ALL_SUCCEEDED, first.getBatchStatus());
        assertEquals(2.0d, first.getTotalBudgetDelta(), 1e-9);
        assertEquals(2.0d, spent(us) + spent(eu), 1e-9);
        logGroup(requestId, "首趟", 0, first.getResults().get(0));
        logGroup(requestId, "首趟", 1, first.getResults().get(1));

        // 第一次响应超时后原样重发：逐组结论幂等复现，但本趟账本没有变化，合计必须为 0。
        BatchGatewayResponse resent = gateway.submitBatch(batchReq(requestId, List.of(us, eu)));
        logGroup(requestId, "重发", 0, resent.getResults().get(0));
        logGroup(requestId, "重发", 1, resent.getResults().get(1));
        log.info("requestId={} 重发对账 首趟合计=-{} 重发趟合计=-{} 账本累计扣减=-{}",
                requestId, first.getTotalBudgetDelta(), resent.getTotalBudgetDelta(),
                spent(us) + spent(eu));

        assertEquals(BatchStatus.ALL_SUCCEEDED, resent.getBatchStatus());
        assertTrue(resent.getResults().get(0).isDeduplicated());
        assertTrue(resent.getResults().get(1).isDeduplicated());
        // 逐组结论（值 / 规模 / 历史扣减快照 / 判定依据）保持不变。
        assertEquals(first.getResults().get(0).getValue(), resent.getResults().get(0).getValue());
        assertEquals(first.getResults().get(1).getValue(), resent.getResults().get(1).getValue());
        assertEquals(first.getResults().get(0).getDecisionBasis(),
                resent.getResults().get(0).getDecisionBasis());
        // 重发趟合计 = 本趟账本实际变化 = 0。
        assertEquals(0d, resent.getTotalBudgetDelta(), 1e-9,
                "整批重发没有真正扣预算，合计必须为 0");
        // 各趟合计之和 = 账本累计扣减：对账闭环。
        assertEquals(spent(us) + spent(eu),
                first.getTotalBudgetDelta() + resent.getTotalBudgetDelta(), 1e-9);
        assertEquals(1.0d, spent(us), 1e-9, "重发不得重复扣减");
        assertEquals(1.0d, spent(eu), 1e-9, "重发不得重复扣减");
    }

    @Test
    void mixedBatchResendCountsOnlyNewlyChargedGroups() {
        String requestId = "recon-mixed-" + UUID.randomUUID();

        // 第一趟：us 正常算出并扣 1.0（进缓存）；eu 底层抖动内部失败（不缓存、零扣减）。
        repository.failEu();
        BatchGatewayResponse first = gateway.submitBatch(batchReq(requestId, List.of(us, eu)));
        logGroup(requestId, "首趟", 0, first.getResults().get(0));
        logGroup(requestId, "首趟", 1, first.getResults().get(1));
        assertEquals(BatchStatus.PARTIAL_SUCCESS, first.getBatchStatus());
        assertEquals(ReleaseStatus.ALLOWED, first.getResults().get(0).getStatus());
        assertEquals("internal_processing_error", first.getResults().get(1).getErrorCode());
        assertEquals(1.0d, first.getTotalBudgetDelta(), 1e-9);
        assertEquals(1.0d, spent(us), 1e-9);
        assertEquals(0d, spent(eu), 1e-9);

        // 底层恢复后原样重发：us 沿用历史结论（本趟不扣），eu 这趟才真正算出并扣 1.0。
        repository.recoverEu();
        BatchGatewayResponse resent = gateway.submitBatch(batchReq(requestId, List.of(us, eu)));
        GatewayResponse usAgain = resent.getResults().get(0);
        GatewayResponse euFresh = resent.getResults().get(1);
        logGroup(requestId, "重发", 0, usAgain);
        logGroup(requestId, "重发", 1, euFresh);
        log.info("requestId={} 混合重发对账 首趟合计=-{} 重发趟合计=-{} 账本 us=-{} eu=-{}",
                requestId, first.getTotalBudgetDelta(), resent.getTotalBudgetDelta(),
                spent(us), spent(eu));

        assertEquals(BatchStatus.ALL_SUCCEEDED, resent.getBatchStatus());
        // us：命中缓存，本趟没扣钱，不得计入合计；逐组结论与首趟一致。
        assertTrue(usAgain.isDeduplicated());
        assertEquals(first.getResults().get(0).getValue(), usAgain.getValue());
        // eu：本趟真正新扣，必须计入合计，一个都不能漏。
        assertFalse(euFresh.isDeduplicated());
        assertEquals(ReleaseStatus.ALLOWED, euFresh.getStatus());
        assertEquals(1.0d, euFresh.getBudgetDelta(), 1e-9);
        // 重发趟合计只算新扣的 eu：1.0，而不是首趟的 2.0，也不是 0。
        assertEquals(1.0d, resent.getTotalBudgetDelta(), 1e-9,
                "合计只能算本趟真正新扣的分组（eu），沿用历史结论的 us 不得计入");
        // 对账闭环：各趟合计之和 = 账本累计扣减。
        assertEquals(spent(us) + spent(eu),
                first.getTotalBudgetDelta() + resent.getTotalBudgetDelta(), 1e-9);
        assertEquals(1.0d, spent(us), 1e-9, "us 全程只扣一次");
        assertEquals(1.0d, spent(eu), 1e-9, "eu 恢复后只扣一次");
    }

    @Test
    void concurrentResendsOfSameBatchChargeOnlyOnce() throws Exception {
        String requestId = "recon-cresend-" + UUID.randomUUID();

        BatchGatewayResponse first = gateway.submitBatch(batchReq(requestId, List.of(us, eu)));
        assertEquals(2.0d, first.getTotalBudgetDelta(), 1e-9);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<BatchGatewayResponse> responses = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    responses.add(gateway.submitBatch(batchReq(requestId, List.of(us, eu))));
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

        double resentTotal = 0d;
        for (BatchGatewayResponse r : responses) {
            resentTotal += r.getTotalBudgetDelta();
            assertTrue(r.getResults().get(0).isDeduplicated(), "并发重发必须命中缓存");
            assertTrue(r.getResults().get(1).isDeduplicated(), "并发重发必须命中缓存");
        }
        log.info("requestId={} 并发重发 {} 趟 合计之和=-{} 账本累计扣减=-{}",
                requestId, responses.size(), resentTotal, spent(us) + spent(eu));

        assertEquals(0d, resentTotal, 1e-9, "并发重发各趟都没有真正扣预算，合计必须全为 0");
        assertEquals(1.0d, spent(us), 1e-9, "并发重发不得重复扣减");
        assertEquals(1.0d, spent(eu), 1e-9, "并发重发不得重复扣减");
    }

    @Test
    void concurrentFreshAndResendSharingOneBudgetNeverOverdraw() throws Exception {
        // 6 个不同 requestId 各自提交后又原样重发一次，全部并发抢同一个预算键（总额 3.0 / 单价 1.0）。
        int requestIds = 6;
        int submissions = requestIds * 2;
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < requestIds; i++) {
            ids.add("recon-conc-" + i + "-" + UUID.randomUUID());
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(submissions);
        ConcurrentLinkedQueue<BatchGatewayResponse> responses = new ConcurrentLinkedQueue<>();
        for (String id : ids) {
            for (int attempt = 0; attempt < 2; attempt++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        responses.add(gateway.submitBatch(batchReq(id, List.of(us))));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        double sumTotals = responses.stream()
                .mapToDouble(BatchGatewayResponse::getTotalBudgetDelta).sum();
        long allowedFresh = responses.stream()
                .flatMap(r -> r.getResults().stream())
                .filter(g -> g.getStatus() == ReleaseStatus.ALLOWED && !g.isDeduplicated())
                .count();
        BudgetAccount.BudgetSnapshot snap =
                budgetLedger.snapshot(BudgetKey.of(dataset, us), BUDGET_TOTAL);

        log.info("并发混合汇总 提交={} 真正新扣分组={} 各趟合计之和=-{} 账本已扣=-{} 剩余={}",
                submissions, allowedFresh, sumTotals, snap.spent(), snap.remaining());
        responses.forEach(r -> logGroup(r.getRequestId(), "并发", 0, r.getResults().get(0)));

        assertEquals(3, allowedFresh, "总额 3.0 / epsilon 1.0，恰好 3 个分组真正扣减成功");
        assertEquals(3.0d, sumTotals, 1e-9,
                "所有趟次的合计之和必须等于真正新扣的总量（重发趟贡献 0）");
        assertEquals(sumTotals, snap.spent(), 1e-9, "各趟合计之和必须与账本对得上");
        assertEquals(0.0d, snap.remaining(), 1e-9, "并发下同一份预算不得被扣超");
    }
}
