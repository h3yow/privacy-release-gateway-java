package com.github.h3yow.privacyreleasegatewayjava.tests;

import com.github.h3yow.privacyreleasegatewayjava.aggregate.AggregationEngine;
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
 * 整批预算对账回归（bugfix-b）：响应里的 totalBudgetDelta 口径是“这一趟对账本的实际变化”。
 * <ul>
 *   <li>同一批量 requestId 原样重发：逐组结论逐字复现，但本趟零扣减，整批合计必须为 0；</li>
 *   <li>一批里部分分组本趟重算扣钱、部分沿用历史结论：合计只算本趟新扣的那部分；</li>
 *   <li>底层恢复后重算的分组按同样口径计入；重发不重复扣，并发同标识重发也不超扣。</li>
 * </ul>
 */
class BatchResendReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(BatchResendReconciliationTest.class);

    /** 可按维度值注入故障、随后恢复的仓库，用于让一批里只有部分分组在首趟内部失败。 */
    static class PartialFlakyRepository extends DatasetRepository {
        private final String failingDimensionValue;
        private final AtomicBoolean healthy = new AtomicBoolean(false);

        PartialFlakyRepository(String failingDimensionValue) {
            this.failingDimensionValue = failingDimensionValue;
        }

        void recover() {
            healthy.set(true);
        }

        @Override
        public List<DataRow> matchingRows(String dataset, Map<String, String> dimensions) {
            if (!healthy.get() && dimensions != null
                    && failingDimensionValue.equals(dimensions.get("region"))) {
                throw new IllegalStateException("simulated backend outage for " + failingDimensionValue);
            }
            return super.matchingRows(dataset, dimensions);
        }
    }

    private BudgetLedger budgetLedger;
    private PrivacyGateway gateway;
    private String dataset;
    private String policy;

    private PartialFlakyRepository newGateway(boolean flaky, String failingValue) {
        dataset = "resend-" + UUID.randomUUID();
        policy = dataset + "-p";
        PartialFlakyRepository repository =
                new PartialFlakyRepository(flaky ? failingValue : "__never__");
        budgetLedger = new BudgetLedger();
        PolicyRegistry registry = new PolicyRegistry();
        repository.registerDataset(dataset, List.of(
                new DataRow("u1", Map.of("region", "us", "amount", 100)),
                new DataRow("u2", Map.of("region", "us", "amount", 200)),
                new DataRow("u3", Map.of("region", "us", "amount", 300)),
                new DataRow("e1", Map.of("region", "eu", "amount", 10)),
                new DataRow("e2", Map.of("region", "eu", "amount", 20)),
                new DataRow("e3", Map.of("region", "eu", "amount", 30))
        ));
        Set<String> roles = Set.of("analyst");
        // epsilon=1.0，默认账户总额 3.0。
        registry.register(new MaskingPolicy(policy, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);
        gateway = new PrivacyGateway(repository, registry, new PolicyEvaluator(),
                new AggregationEngine(), budgetLedger);
        return repository;
    }

    @BeforeEach
    void setUp() {
        newGateway(false, "eu");
    }

    private BatchQueryRequest batch(String id, List<Map<String, String>> groups) {
        return new BatchQueryRequest(id, dataset, groups, "amount",
                AggregationType.SUM, "analyst", policy);
    }

    private double spent(Map<String, String> dims) {
        return budgetLedger.snapshot(BudgetKey.of(dataset, dims), 3.0d).spent();
    }

    private void logGroups(String tag, String requestId, BatchGatewayResponse b) {
        for (int i = 0; i < b.getResults().size(); i++) {
            GatewayResponse g = b.getResults().get(i);
            log.info("requestId={} {} 分组{} 判定={} code={} deduplicated={} 本趟预算变化=-{} 依据={}",
                    requestId, tag, i, g.getStatus(), g.getErrorCode(), g.isDeduplicated(),
                    g.isDeduplicated() ? 0d : (g.getBudgetDelta() == null ? 0d : g.getBudgetDelta()),
                    g.getDecisionBasis());
        }
    }

    @Test
    void fullResendTotalReflectsThisTripLedgerChangeAndGroupConclusionsUnchanged() {
        String requestId = "resend-full-" + UUID.randomUUID();
        Map<String, String> us = Map.of("region", "us");
        Map<String, String> eu = Map.of("region", "eu");
        BatchQueryRequest req = batch(requestId, List.of(us, eu));

        BatchGatewayResponse first = gateway.submitBatch(req);
        logGroups("首趟", requestId, first);
        assertEquals(2.0d, first.getTotalBudgetDelta(), 1e-9, "首趟两组各扣 1.0");
        double spentBeforeReplay = spent(us) + spent(eu);
        assertEquals(2.0d, spentBeforeReplay, 1e-9);

        // 原样重发（例如第一次响应超时后照单再发一遍）。
        BatchGatewayResponse replay = gateway.submitBatch(req);
        logGroups("重发", requestId, replay);

        double spentAfterReplay = spent(us) + spent(eu);
        double ledgerChangeThisTrip = spentAfterReplay - spentBeforeReplay;
        log.info("requestId={} 重发对账 响应合计={} 账本本趟变化={} 账本累计已扣={}",
                requestId, replay.getTotalBudgetDelta(), ledgerChangeThisTrip, spentAfterReplay);

        // 这一趟预算根本没动：合计必须为 0，且等于账本本趟差值，可直接与预算查询对上。
        assertEquals(0d, replay.getTotalBudgetDelta(), 1e-9,
                "原样重发本趟零扣减，合计必须体现这一趟而不是首趟那笔 2.0");
        assertEquals(0d, ledgerChangeThisTrip, 1e-9);
        assertEquals(replay.getTotalBudgetDelta(), ledgerChangeThisTrip, 1e-9);
        assertEquals(2.0d, spentAfterReplay, 1e-9, "重发绝不重复扣");

        // 逐组结论（加噪值、分组规模、首算扣减快照、判定依据、正常/降级/拒绝）保持不变。
        for (int i = 0; i < 2; i++) {
            GatewayResponse a = first.getResults().get(i);
            GatewayResponse b = replay.getResults().get(i);
            assertTrue(b.isDeduplicated());
            assertEquals(a.getStatus(), b.getStatus());
            assertEquals(a.getValue(), b.getValue());
            assertEquals(a.getGroupSize(), b.getGroupSize());
            assertEquals(a.getBudgetDelta(), b.getBudgetDelta(), 1e-9,
                    "逐组 budgetDelta 是历史首算快照，重发逐字不变");
            assertEquals(a.getBudgetBefore(), b.getBudgetBefore(), 1e-9);
            assertEquals(a.getBudgetRemaining(), b.getBudgetRemaining(), 1e-9);
            assertEquals(a.getDecisionBasis(), b.getDecisionBasis());
        }
    }

    @Test
    void partialRecomputedTripTotalCountsOnlyGroupsActuallyChargedThisTrip() {
        // 重建一个 eu 分组在故障期必失败的网关。
        PartialFlakyRepository repository = newGateway(true, "eu");
        String requestId = "resend-partial-" + UUID.randomUUID();
        Map<String, String> us = Map.of("region", "us");
        Map<String, String> eu = Map.of("region", "eu");
        BatchQueryRequest req = batch(requestId, List.of(us, eu));

        // 首趟：us 真正算出并扣 1.0；eu 底层抖动内部失败，零扣减且不缓存。
        BatchGatewayResponse trip1 = gateway.submitBatch(req);
        logGroups("首趟(故障)", requestId, trip1);
        GatewayResponse us1 = trip1.getResults().get(0);
        GatewayResponse eu1 = trip1.getResults().get(1);
        assertEquals(ReleaseStatus.ALLOWED, us1.getStatus());
        assertFalse(us1.isDeduplicated());
        assertEquals(ReleaseStatus.REJECTED, eu1.getStatus());
        assertEquals("internal_processing_error", eu1.getErrorCode());
        assertEquals(1.0d, trip1.getTotalBudgetDelta(), 1e-9, "首趟只有 us 新扣");
        assertEquals(1.0d, spent(us), 1e-9);
        assertEquals(0d, spent(eu), 1e-9);

        // 底层恢复后同标识重发：us 沿用历史结论（本趟零扣减、不计入），
        // eu 这一趟才真正算出结论并扣钱（计入合计）。
        repository.recover();
        double spentBeforeTrip2 = spent(us) + spent(eu);
        BatchGatewayResponse trip2 = gateway.submitBatch(req);
        logGroups("恢复后重发", requestId, trip2);
        double ledgerChangeTrip2 = spent(us) + spent(eu) - spentBeforeTrip2;

        GatewayResponse us2 = trip2.getResults().get(0);
        GatewayResponse eu2 = trip2.getResults().get(1);
        assertTrue(us2.isDeduplicated(), "us 沿用历史结论");
        assertFalse(eu2.isDeduplicated(), "eu 本趟重新算出结论");
        assertEquals(ReleaseStatus.ALLOWED, eu2.getStatus());
        assertEquals(1.0d, eu2.getBudgetDelta(), 1e-9);
        assertEquals(us1.getValue(), us2.getValue(), "沿用分组的加噪值不变");
        assertEquals(us1.getBudgetDelta(), us2.getBudgetDelta(), 1e-9,
                "沿用分组的逐组扣减快照仍是首算那笔，不代表本趟扣减");

        log.info("requestId={} 恢复重发对账 响应合计={} 账本本趟变化={} us已扣={} eu已扣={}",
                requestId, trip2.getTotalBudgetDelta(), ledgerChangeTrip2, spent(us), spent(eu));
        assertEquals(1.0d, trip2.getTotalBudgetDelta(), 1e-9,
                "合计只能算本趟新扣的 eu，us 没再扣一个子儿都不能算进去");
        assertEquals(1.0d, ledgerChangeTrip2, 1e-9, "eu 一个都不能漏");
        assertEquals(trip2.getTotalBudgetDelta(), ledgerChangeTrip2, 1e-9);
        assertEquals(1.0d, spent(us), 1e-9);
        assertEquals(1.0d, spent(eu), 1e-9);

        // 全部出结果后再次原样重发：两组都沿用历史结论，本趟合计为 0，不重复扣。
        double spentBeforeTrip3 = spent(us) + spent(eu);
        BatchGatewayResponse trip3 = gateway.submitBatch(req);
        logGroups("再次重发", requestId, trip3);
        assertEquals(0d, trip3.getTotalBudgetDelta(), 1e-9);
        assertEquals(0d, spent(us) + spent(eu) - spentBeforeTrip3, 1e-9);
        assertTrue(trip3.getResults().get(0).isDeduplicated());
        assertTrue(trip3.getResults().get(1).isDeduplicated());
        assertEquals(1.0d, spent(us), 1e-9);
        assertEquals(1.0d, spent(eu), 1e-9);
    }

    @Test
    void concurrentSameRequestIdResendsNeverOverdrawAndTotalsReconcile() throws Exception {
        String requestId = "resend-concurrent-" + UUID.randomUUID();
        Map<String, String> dims = Map.of("region", "us");
        // 两个分组命中同一预算键，账户总额 3.0 / 单价 1.0：整个 requestId 最多扣 2.0。
        BatchQueryRequest req = batch(requestId, List.of(dims, dims));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<BatchGatewayResponse> responses = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    responses.add(gateway.submitBatch(req));
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

        double sumOfBatchTotals = responses.stream()
                .mapToDouble(BatchGatewayResponse::getTotalBudgetDelta).sum();
        long chargedGroupResults = responses.stream()
                .flatMap(b -> b.getResults().stream())
                .filter(g -> !g.isDeduplicated()
                        && (g.getStatus() == ReleaseStatus.ALLOWED
                        || g.getStatus() == ReleaseStatus.DEGRADED))
                .count();

        double ledgerSpent = spent(dims);
        log.info("requestId={} 并发同标识重发 线程={} 各趟合计之和={} 本趟新扣分组结果数={} 账本已扣={} 剩余={}",
                requestId, threads, sumOfBatchTotals, chargedGroupResults,
                ledgerSpent, budgetLedger.snapshot(BudgetKey.of(dataset, dims), 3.0d).remaining());

        // 只有一个线程能首算这两个分组（扣 2.0），其余全部命中缓存：
        // 各响应“本趟合计”之和必须恰好等于账本实际扣减，绝不超扣。
        assertEquals(2, chargedGroupResults, "同一 requestId 下每个分组只允许真正扣一次");
        assertEquals(2.0d, sumOfBatchTotals, 1e-9, "各趟合计之和 = 首趟 2.0 + 重发各趟 0");
        assertEquals(2.0d, ledgerSpent, 1e-9, "并发重发不得超扣/重复扣");
        assertEquals(1.0d,
                budgetLedger.snapshot(BudgetKey.of(dataset, dims), 3.0d).remaining(), 1e-9);
    }
}
