package com.github.h3yow.privacyreleasegatewayjava.tests;

import com.github.h3yow.privacyreleasegatewayjava.batch.BatchGatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.batch.BatchGroup;
import com.github.h3yow.privacyreleasegatewayjava.batch.BatchQueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.batch.BudgetUsageSummary;
import com.github.h3yow.privacyreleasegatewayjava.batch.GroupOutcome;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetAccount;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetLedger;
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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量发布新行为边界：
 * 部分成功部分拒绝互不影响、预算对账、并发不超扣、失败分组零扣减、
 * 底层恢复后同一 requestId 重试拿到新结论、确定性拒绝幂等。
 */
@SpringBootTest
class BatchReleaseTest {

    private static final Logger log = LoggerFactory.getLogger(BatchReleaseTest.class);
    private static final double DEFAULT_TOTAL = 3.0d;

    @Autowired
    private PrivacyGateway gateway;
    @Autowired
    private DatasetRepository repository;
    @Autowired
    private PolicyRegistry registry;
    @Autowired
    private BudgetLedger ledger;

    /** 注册隔离的测试数据集与策略：us/eu 各 3 行（达标），tiny 2 行（低于阈值）。 */
    private String[] newDataset() {
        return newDatasetWithRows(null);
    }

    private String[] newDatasetWithRows(List<DataRow> extraRows) {
        String dataset = "batch-" + UUID.randomUUID();
        String policy = dataset + "-p";
        Set<String> roles = Set.of("analyst");
        List<DataRow> rows = new ArrayList<>(List.of(
                new DataRow("u1", Map.of("region", "us", "amount", 100)),
                new DataRow("u2", Map.of("region", "us", "amount", 200)),
                new DataRow("u3", Map.of("region", "us", "amount", 300)),
                new DataRow("e1", Map.of("region", "eu", "amount", 10)),
                new DataRow("e2", Map.of("region", "eu", "amount", 20)),
                new DataRow("e3", Map.of("region", "eu", "amount", 30)),
                new DataRow("t1", Map.of("region", "tiny", "amount", 1)),
                new DataRow("t2", Map.of("region", "tiny", "amount", 2))
        ));
        if (extraRows != null) {
            rows.addAll(extraRows);
        }
        repository.registerDataset(dataset, rows);
        registry.register(new MaskingPolicy(policy, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);
        return new String[]{dataset, policy};
    }

    private BatchQueryRequest batchReq(String requestId, String dataset, String policy,
                                       List<BatchGroup> groups) {
        return new BatchQueryRequest(requestId, dataset, groups, "amount",
                AggregationType.SUM, "analyst", policy);
    }

    private static GroupOutcome group(BatchGatewayResponse response, String groupId) {
        return response.getResults().stream()
                .filter(g -> g.getGroupId().equals(groupId)).findFirst().orElseThrow();
    }

    private void logGroup(String requestId, GroupOutcome g) {
        log.info("requestId={} 分组={} 判定={} code={} 预算变化 {}->{}(-{}) 依据={}",
                requestId, g.getGroupId(), g.getStatus(), g.getErrorCode(),
                g.getBudgetBefore(), g.getBudgetRemaining(), g.getBudgetDelta(),
                g.getDecisionBasis());
    }

    @Test
    void partialSuccessGivesPerGroupOutcomes() {
        String[] env = newDataset();
        String requestId = "batch-partial-" + UUID.randomUUID();
        BatchGatewayResponse response = gateway.submitBatch(batchReq(requestId, env[0], env[1],
                List.of(
                        new BatchGroup("ok-us", Map.of("region", "us")),
                        new BatchGroup("too-small", Map.of("region", "tiny")),
                        new BatchGroup("bad-body", null)
                )));

        response.getResults().forEach(g -> logGroup(requestId, g));
        log.info("requestId={} 预算对账={}", requestId, response.getBudgetUsage());

        assertEquals(3, response.getTotalGroups());
        assertEquals(1, response.getAllowedGroups());
        assertEquals(2, response.getRejectedGroups());
        assertNull(response.getBatchError());

        GroupOutcome ok = group(response, "ok-us");
        assertEquals(ReleaseStatus.ALLOWED, ok.getStatus());
        assertNotNull(ok.getValue());
        assertEquals(3, ok.getGroupSize());
        assertEquals(1.0d, ok.getBudgetDelta());
        assertNotNull(ok.getDecisionBasis());

        // 分组过小：确定性业务拒绝，零扣减，且不影响其它分组。
        GroupOutcome small = group(response, "too-small");
        assertEquals(ReleaseStatus.REJECTED, small.getStatus());
        assertEquals("group_too_small", small.getErrorCode());
        assertEquals(0d, small.getBudgetDelta());

        // 分组级报文问题只影响该分组。
        GroupOutcome badBody = group(response, "bad-body");
        assertEquals(ReleaseStatus.REJECTED, badBody.getStatus());
        assertEquals("malformed_request", badBody.getErrorCode());
        assertEquals(0d, badBody.getBudgetDelta());

        // 对账：本批净扣减 == 账本实际变化 == 1.0（仅 ok-us）。
        assertEquals(1, response.getBudgetUsage().size());
        BudgetUsageSummary usage = response.getBudgetUsage().get(0);
        assertEquals(1.0d, usage.totalDelta(), 1e-9);
        BudgetAccount.BudgetSnapshot snapshot = ledger.snapshot(
                BudgetKey.of(env[0], Map.of("region", "us")), DEFAULT_TOTAL);
        assertEquals(1.0d, snapshot.spent(), 1e-9);
        assertEquals(usage.remaining(), snapshot.remaining(), 1e-9);
    }

    @Test
    void budgetReconcilesAcrossSharedAndSeparateKeys() {
        String[] env = newDataset();
        BudgetKey usKey = BudgetKey.of(env[0], Map.of("region", "us"));
        BudgetKey euKey = BudgetKey.of(env[0], Map.of("region", "eu"));
        double usBefore = ledger.snapshot(usKey, DEFAULT_TOTAL).spent();
        double euBefore = ledger.snapshot(euKey, DEFAULT_TOTAL).spent();

        String requestId = "batch-recon-" + UUID.randomUUID();
        // g1 与 g2 维度相同 => 共用同一份预算；g3 独立预算键。
        BatchGatewayResponse response = gateway.submitBatch(batchReq(requestId, env[0], env[1],
                List.of(
                        new BatchGroup("g1", Map.of("region", "us")),
                        new BatchGroup("g2", Map.of("region", "us")),
                        new BatchGroup("g3", Map.of("region", "eu"))
                )));

        response.getResults().forEach(g -> logGroup(requestId, g));
        log.info("requestId={} 预算对账={}", requestId, response.getBudgetUsage());

        assertEquals(3, response.getAllowedGroups());
        // 共享键上的两次扣减必须链式衔接：g2 的 before == g1 的 remaining。
        GroupOutcome g1 = group(response, "g1");
        GroupOutcome g2 = group(response, "g2");
        assertEquals(g1.getBudgetRemaining(), g2.getBudgetBefore(), 1e-9);

        double usDelta = g1.getBudgetDelta() + g2.getBudgetDelta();
        double euDelta = group(response, "g3").getBudgetDelta();

        // 响应中的预算数字 == 事后直接查账本的状态。
        BudgetAccount.BudgetSnapshot usSnap = ledger.snapshot(usKey, DEFAULT_TOTAL);
        BudgetAccount.BudgetSnapshot euSnap = ledger.snapshot(euKey, DEFAULT_TOTAL);
        assertEquals(usBefore + usDelta, usSnap.spent(), 1e-9);
        assertEquals(euBefore + euDelta, euSnap.spent(), 1e-9);

        assertEquals(2, response.getBudgetUsage().size());
        for (BudgetUsageSummary usage : response.getBudgetUsage()) {
            if (usage.budgetKey().equals(usKey.canonical())) {
                assertEquals(usDelta, usage.totalDelta(), 1e-9);
                assertEquals(usSnap.remaining(), usage.remaining(), 1e-9);
            } else {
                assertEquals(euKey.canonical(), usage.budgetKey());
                assertEquals(euDelta, usage.totalDelta(), 1e-9);
                assertEquals(euSnap.remaining(), usage.remaining(), 1e-9);
            }
        }
    }

    @Test
    void concurrentBatchesNeverOverdrawSharedBudget() throws Exception {
        String[] env = newDataset();
        BudgetKey usKey = BudgetKey.of(env[0], Map.of("region", "us"));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<Double> spend = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String requestId = "batch-conc-" + idx + "-" + UUID.randomUUID();
                    BatchGatewayResponse response = gateway.submitBatch(
                            batchReq(requestId, env[0], env[1],
                                    List.of(new BatchGroup("only", Map.of("region", "us")))));
                    GroupOutcome outcome = group(response, "only");
                    if (outcome.getStatus() == ReleaseStatus.ALLOWED) {
                        allowed.incrementAndGet();
                        spend.add(outcome.getBudgetDelta());
                    } else {
                        rejected.incrementAndGet();
                        assertEquals(0d, outcome.getBudgetDelta(), "拒绝分组不得扣预算");
                    }
                    logGroup(requestId, outcome);
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
        BudgetAccount.BudgetSnapshot snap = ledger.snapshot(usKey, DEFAULT_TOTAL);
        log.info("并发汇总 allowed={} rejected={} 成功扣减合计={} 账本已扣={} 剩余={}",
                allowed.get(), rejected.get(), totalSpend, snap.spent(), snap.remaining());

        assertEquals(3, allowed.get(), "总额 3.0 / 单价 1.0 => 恰好 3 个分组成功");
        assertEquals(9, rejected.get());
        assertEquals(3.0d, totalSpend, 1e-9, "成功扣减合计不得超总额");
        assertEquals(3.0d, snap.spent(), 1e-9);
        assertEquals(0.0d, snap.remaining(), 1e-9);
    }

    @Test
    void transientFailureCostsNothingAndSameRequestIdRecoversAfterFix() {
        // 底层数据问题：bad 组指标值无法解析为数值 => INTERNAL_PROCESSING_ERROR。
        String dataset = "batch-flaky-" + UUID.randomUUID();
        String policy = dataset + "-p";
        Set<String> roles = Set.of("analyst");
        repository.registerDataset(dataset, List.of(
                new DataRow("b1", Map.of("region", "bad", "amount", "oops")),
                new DataRow("b2", Map.of("region", "bad", "amount", "oops")),
                new DataRow("b3", Map.of("region", "bad", "amount", "oops")),
                new DataRow("g1", Map.of("region", "good", "amount", 5)),
                new DataRow("g2", Map.of("region", "good", "amount", 6)),
                new DataRow("g3", Map.of("region", "good", "amount", 7))
        ));
        registry.register(new MaskingPolicy(policy, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);

        BudgetKey badKey = BudgetKey.of(dataset, Map.of("region", "bad"));
        BudgetKey goodKey = BudgetKey.of(dataset, Map.of("region", "good"));
        String requestId = "batch-flaky-" + UUID.randomUUID();
        List<BatchGroup> groups = List.of(
                new BatchGroup("broken", Map.of("region", "bad")),
                new BatchGroup("fine", Map.of("region", "good")));

        BatchGatewayResponse first = gateway.submitBatch(
                batchReq(requestId, dataset, policy, groups));
        first.getResults().forEach(g -> logGroup(requestId, g));

        GroupOutcome broken = group(first, "broken");
        assertEquals(ReleaseStatus.REJECTED, broken.getStatus());
        assertEquals("internal_processing_error", broken.getErrorCode());
        assertEquals("内部处理失败，请稍后重试", broken.getErrorMessage(), "不得夹带内部细节");
        assertEquals(0d, broken.getBudgetDelta(), "失败分组净扣减必须为零");
        assertEquals(0d, ledger.snapshot(badKey, DEFAULT_TOTAL).spent(), 1e-9);
        // 同批其它分组不受影响。
        assertEquals(ReleaseStatus.ALLOWED, group(first, "fine").getStatus());
        assertEquals(1.0d, ledger.snapshot(goodKey, DEFAULT_TOTAL).spent(), 1e-9);

        // 底层恢复：修复数据后，同一 requestId 原样重发应拿到新结论。
        repository.registerDataset(dataset, List.of(
                new DataRow("b1", Map.of("region", "bad", "amount", 1)),
                new DataRow("b2", Map.of("region", "bad", "amount", 2)),
                new DataRow("b3", Map.of("region", "bad", "amount", 3)),
                new DataRow("g1", Map.of("region", "good", "amount", 5)),
                new DataRow("g2", Map.of("region", "good", "amount", 6)),
                new DataRow("g3", Map.of("region", "good", "amount", 7))
        ));
        BatchGatewayResponse retry = gateway.submitBatch(
                batchReq(requestId, dataset, policy, groups));
        retry.getResults().forEach(g -> logGroup(requestId, g));
        log.info("requestId={} 重试预算对账={}", requestId, retry.getBudgetUsage());

        GroupOutcome recovered = group(retry, "broken");
        assertEquals(ReleaseStatus.ALLOWED, recovered.getStatus(), "恢复后同一 requestId 必须能出新结论");
        assertFalse(recovered.isDeduplicated());
        assertEquals(1.0d, recovered.getBudgetDelta());

        // 已成功的分组重放命中缓存，不得重复扣预算。
        GroupOutcome replayed = group(retry, "fine");
        assertTrue(replayed.isDeduplicated());
        assertEquals(1.0d, ledger.snapshot(goodKey, DEFAULT_TOTAL).spent(), 1e-9,
                "重试不得对已成功分组重复扣减");
        assertEquals(1.0d, ledger.snapshot(badKey, DEFAULT_TOTAL).spent(), 1e-9);

        // 本次调用的对账单只含真正新扣减的分组。
        assertEquals(1, retry.getBudgetUsage().size());
        assertEquals(badKey.canonical(), retry.getBudgetUsage().get(0).budgetKey());
        assertEquals(1.0d, retry.getBudgetUsage().get(0).totalDelta(), 1e-9);
    }

    @Test
    void singleRequestTransientFailureAlsoRetriesToNewConclusion() {
        String dataset = "single-flaky-" + UUID.randomUUID();
        String policy = dataset + "-p";
        Set<String> roles = Set.of("analyst");
        repository.registerDataset(dataset, List.of(
                new DataRow("s1", Map.of("region", "bad", "amount", "NaN-x")),
                new DataRow("s2", Map.of("region", "bad", "amount", "NaN-y")),
                new DataRow("s3", Map.of("region", "bad", "amount", "NaN-z"))
        ));
        registry.register(new MaskingPolicy(policy, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);

        BudgetKey key = BudgetKey.of(dataset, Map.of("region", "bad"));
        String requestId = "single-flaky-" + UUID.randomUUID();
        QueryRequest request = new QueryRequest(requestId, dataset, Map.of("region", "bad"),
                "amount", AggregationType.SUM, "analyst", policy);

        GatewayResponse first = gateway.submit(request);
        log.info("requestId={} 判定={} code={} 预算变化=0 依据={}",
                requestId, first.getStatus(), first.getErrorCode(), first.getDecisionBasis());
        assertEquals(ReleaseStatus.REJECTED, first.getStatus());
        assertEquals("internal_processing_error", first.getErrorCode());
        assertEquals(0d, ledger.snapshot(key, DEFAULT_TOTAL).spent(), 1e-9);

        repository.registerDataset(dataset, List.of(
                new DataRow("s1", Map.of("region", "bad", "amount", 1)),
                new DataRow("s2", Map.of("region", "bad", "amount", 2)),
                new DataRow("s3", Map.of("region", "bad", "amount", 3))
        ));
        GatewayResponse retry = gateway.submit(request);
        log.info("requestId={} 恢复后重试 判定={} 预算变化 {}->{}(-{})",
                requestId, retry.getStatus(), retry.getBudgetBefore(),
                retry.getBudgetRemaining(), retry.getBudgetDelta());
        assertEquals(ReleaseStatus.ALLOWED, retry.getStatus(), "恢复后同一 requestId 必须给出新结论");
        assertFalse(retry.isDeduplicated());
        assertEquals(1.0d, ledger.snapshot(key, DEFAULT_TOTAL).spent(), 1e-9);

        // 成功结论落缓存：第三次重发命中幂等，不再扣。
        GatewayResponse third = gateway.submit(request);
        assertTrue(third.isDeduplicated());
        assertEquals(1.0d, ledger.snapshot(key, DEFAULT_TOTAL).spent(), 1e-9);
    }

    @Test
    void deterministicRejectionIsIdempotentInBatch() {
        String[] env = newDataset();
        BudgetKey tinyKey = BudgetKey.of(env[0], Map.of("region", "tiny"));
        String requestId = "batch-idem-" + UUID.randomUUID();
        BatchQueryRequest request = batchReq(requestId, env[0], env[1],
                List.of(new BatchGroup("small", Map.of("region", "tiny"))));

        BatchGatewayResponse first = gateway.submitBatch(request);
        BatchGatewayResponse second = gateway.submitBatch(request);
        logGroup(requestId, group(first, "small"));
        logGroup(requestId, group(second, "small"));

        GroupOutcome f = group(first, "small");
        GroupOutcome s = group(second, "small");
        assertEquals(ReleaseStatus.REJECTED, f.getStatus());
        assertEquals("group_too_small", f.getErrorCode());
        assertFalse(f.isDeduplicated());
        assertTrue(s.isDeduplicated(), "确定性拒绝重发必须命中幂等缓存");
        assertEquals(f.getErrorCode(), s.getErrorCode());
        assertEquals(0d, ledger.snapshot(tinyKey, DEFAULT_TOTAL).spent(), 1e-9,
                "确定性拒绝重发不得扣预算");
        assertTrue(second.getBudgetUsage().isEmpty(), "纯重放的批次净扣减为零");
    }
}
