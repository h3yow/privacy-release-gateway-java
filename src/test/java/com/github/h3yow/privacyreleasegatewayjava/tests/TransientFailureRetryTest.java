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
import com.github.h3yow.privacyreleasegatewayjava.model.BatchStatus;
import com.github.h3yow.privacyreleasegatewayjava.model.GatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非确定性失败的重试语义：底层抖动导致的内部失败
 * 不进入幂等缓存、净扣减为零；底层恢复后同一 requestId 原样重发可拿到新结论。
 */
class TransientFailureRetryTest {

    private static final Logger log = LoggerFactory.getLogger(TransientFailureRetryTest.class);

    /** 可切换“故障 / 恢复”状态的仓库，模拟底层数据层抖动。 */
    static class FlakyDatasetRepository extends DatasetRepository {
        private final AtomicBoolean healthy = new AtomicBoolean(false);

        void recover() {
            healthy.set(true);
        }

        @Override
        public List<DataRow> matchingRows(String dataset, Map<String, String> dimensions) {
            if (!healthy.get()) {
                throw new IllegalStateException("simulated backend outage");
            }
            return super.matchingRows(dataset, dimensions);
        }
    }

    private FlakyDatasetRepository repository;
    private BudgetLedger budgetLedger;
    private PrivacyGateway gateway;
    private String dataset;
    private String policy;

    @BeforeEach
    void setUp() {
        dataset = "flaky-" + UUID.randomUUID();
        policy = dataset + "-p";
        repository = new FlakyDatasetRepository();
        budgetLedger = new BudgetLedger();
        PolicyRegistry registry = new PolicyRegistry();
        repository.registerDataset(dataset, List.of(
                new DataRow("f1", Map.of("region", "us", "amount", 100)),
                new DataRow("f2", Map.of("region", "us", "amount", 200)),
                new DataRow("f3", Map.of("region", "us", "amount", 300))
        ));
        Set<String> roles = Set.of("analyst");
        registry.register(new MaskingPolicy(policy, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);
        gateway = new PrivacyGateway(repository, registry, new PolicyEvaluator(),
                new AggregationEngine(), budgetLedger);
    }

    private double spent(Map<String, String> dims) {
        return budgetLedger.snapshot(BudgetKey.of(dataset, dims), 3.0d).spent();
    }

    @Test
    void internalFailureCostsNothingAndSameRequestIdRetriesToSuccessAfterRecovery() {
        String requestId = "retry-single-" + UUID.randomUUID();
        Map<String, String> dims = Map.of("region", "us");
        QueryRequest request = new QueryRequest(requestId, dataset, dims,
                "amount", AggregationType.SUM, "analyst", policy);

        // 底层故障期：稳定失败类型、不夹带内部细节、净扣减为零。
        GatewayResponse failed = gateway.submit(request);
        log.info("requestId={} 故障期 判定={} code={} 预算变化=0 依据={}",
                requestId, failed.getStatus(), failed.getErrorCode(), failed.getDecisionBasis());
        assertEquals(ReleaseStatus.REJECTED, failed.getStatus());
        assertEquals("internal_processing_error", failed.getErrorCode());
        assertFalse(failed.getErrorMessage().contains("IllegalStateException"),
                "对外信息不得夹带内部异常细节");
        assertFalse(failed.getErrorMessage().contains("backend outage"));
        assertEquals(0d, failed.getBudgetDelta(), 1e-9);
        assertEquals(0d, spent(dims), 1e-9, "内部失败不得消耗预算");

        // 故障期内重发同一 requestId：重新计算（非缓存回放），仍是同样的稳定失败。
        GatewayResponse failedAgain = gateway.submit(request);
        assertEquals("internal_processing_error", failedAgain.getErrorCode());
        assertFalse(failedAgain.isDeduplicated(), "内部失败不得命中幂等缓存");
        assertEquals(0d, spent(dims), 1e-9);

        // 底层恢复：同一 requestId 原样重发，拿到全新结论且只扣一次。
        repository.recover();
        GatewayResponse recovered = gateway.submit(request);
        log.info("requestId={} 恢复后 判定={} 预算变化=-{} 依据={}",
                requestId, recovered.getStatus(), recovered.getBudgetDelta(),
                recovered.getDecisionBasis());
        assertEquals(ReleaseStatus.ALLOWED, recovered.getStatus());
        assertNotNull(recovered.getValue());
        assertFalse(recovered.isDeduplicated());
        assertEquals(1.0d, spent(dims), 1e-9, "恢复后重试只扣减一次");

        // 成功结论进入幂等缓存：再次重发命中缓存，不再扣。
        GatewayResponse dedup = gateway.submit(request);
        assertTrue(dedup.isDeduplicated());
        assertEquals(recovered.getValue(), dedup.getValue());
        assertEquals(1.0d, spent(dims), 1e-9);
    }

    @Test
    void batchRetryAfterRecoveryRecomputesOnlyFailedGroups() {
        String requestId = "retry-batch-" + UUID.randomUUID();
        Map<String, String> dims = Map.of("region", "us");
        // 两个分组共用同一维度（同一预算键），便于观察净扣减。
        BatchQueryRequest batch = new BatchQueryRequest(requestId, dataset,
                List.of(dims, dims), "amount", AggregationType.SUM, "analyst", policy);

        BatchGatewayResponse failed = gateway.submitBatch(batch);
        log.info("requestId={} 故障期 批量判定={} 整批预算净变化=0", requestId, failed.getBatchStatus());
        assertEquals(BatchStatus.ALL_FAILED, failed.getBatchStatus());
        assertEquals("internal_processing_error", failed.getResults().get(0).getErrorCode());
        assertEquals("internal_processing_error", failed.getResults().get(1).getErrorCode());
        assertEquals(0d, failed.getTotalBudgetDelta(), 1e-9);
        assertEquals(0d, spent(dims), 1e-9);

        repository.recover();
        BatchGatewayResponse recovered = gateway.submitBatch(batch);
        log.info("requestId={} 恢复后 批量判定={} 整批预算净变化=-{}",
                requestId, recovered.getBatchStatus(), recovered.getTotalBudgetDelta());
        assertEquals(BatchStatus.ALL_SUCCEEDED, recovered.getBatchStatus());
        assertFalse(recovered.getResults().get(0).isDeduplicated());
        assertEquals(2.0d, recovered.getTotalBudgetDelta(), 1e-9);
        assertEquals(2.0d, spent(dims), 1e-9, "故障期零扣减 + 恢复后一次性扣减 = 账本实际变化");

        // 全部出结果后，整批重发完全幂等。
        BatchGatewayResponse dedup = gateway.submitBatch(batch);
        assertTrue(dedup.getResults().get(0).isDeduplicated());
        assertTrue(dedup.getResults().get(1).isDeduplicated());
        assertEquals(2.0d, spent(dims), 1e-9);
    }
}
