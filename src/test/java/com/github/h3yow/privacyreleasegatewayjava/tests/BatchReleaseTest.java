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
import com.github.h3yow.privacyreleasegatewayjava.model.BatchStatus;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量发布核心语义：一次请求多个分组，逐组独立给结论；
 * 部分分组被拒绝不连累其它分组；整批预算净变化与账本严格对账。
 */
@SpringBootTest
class BatchReleaseTest {

    private static final Logger log = LoggerFactory.getLogger(BatchReleaseTest.class);

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

    @BeforeEach
    void setUp() {
        dataset = "batch-" + UUID.randomUUID();
        policy = dataset + "-p";
        Set<String> roles = Set.of("analyst");
        repository.registerDataset(dataset, List.of(
                new DataRow("b1", Map.of("region", "us", "channel", "web", "amount", 100)),
                new DataRow("b2", Map.of("region", "us", "channel", "web", "amount", 200)),
                new DataRow("b3", Map.of("region", "us", "channel", "web", "amount", 250)),
                new DataRow("b4", Map.of("region", "us", "channel", "app", "amount", 400)),
                new DataRow("b5", Map.of("region", "eu", "channel", "web", "amount", 50)),
                new DataRow("b6", Map.of("region", "eu", "channel", "web", "amount", 60)),
                new DataRow("b7", Map.of("region", "eu", "channel", "web", "amount", 70)),
                new DataRow("b8", Map.of("region", "eu", "channel", "app", "amount", 80))
                // eu+app 仅 1 行 < minGroupSize(3)，必然 GROUP_TOO_SMALL
        ));
        // epsilon=1.0，默认账户总额 3.0。
        registry.register(new MaskingPolicy(policy, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("channel", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);
    }

    private BatchQueryRequest batchReq(String id, List<Map<String, String>> groups) {
        return new BatchQueryRequest(id, dataset, groups, "amount",
                AggregationType.SUM, "analyst", policy);
    }

    private double spent(String dataset, Map<String, String> dims) {
        BudgetAccount.BudgetSnapshot snap =
                budgetLedger.snapshot(BudgetKey.of(dataset, dims), 3.0d);
        return snap.spent();
    }

    @Test
    void partialSuccessDoesNotAffectOtherGroups() {
        String requestId = "batch-partial-" + UUID.randomUUID();
        Map<String, String> usWeb = Map.of("region", "us", "channel", "web");
        Map<String, String> euWeb = Map.of("region", "eu", "channel", "web");
        Map<String, String> euApp = Map.of("region", "eu", "channel", "app");

        BatchGatewayResponse batch = gateway.submitBatch(
                batchReq(requestId, List.of(usWeb, euWeb, euApp)));

        assertEquals(BatchStatus.PARTIAL_SUCCESS, batch.getBatchStatus());
        assertEquals(3, batch.getTotalGroups());
        assertEquals(2, batch.getSucceeded());
        assertEquals(1, batch.getFailed());
        assertEquals(3, batch.getResults().size());

        GatewayResponse g0 = batch.getResults().get(0);
        GatewayResponse g1 = batch.getResults().get(1);
        GatewayResponse g2 = batch.getResults().get(2);

        log.info("requestId={} 分组0 判定={} 预算变化=-{} 依据={}",
                requestId, g0.getStatus(), g0.getBudgetDelta(), g0.getDecisionBasis());
        log.info("requestId={} 分组1 判定={} 预算变化=-{} 依据={}",
                requestId, g1.getStatus(), g1.getBudgetDelta(), g1.getDecisionBasis());
        log.info("requestId={} 分组2 判定={} code={} 预算变化=0 依据={}",
                requestId, g2.getStatus(), g2.getErrorCode(), g2.getDecisionBasis());

        // 出结果的分组：各自独立的值、规模、预算与判定依据。
        assertEquals(ReleaseStatus.ALLOWED, g0.getStatus());
        assertNotNull(g0.getValue());
        assertEquals(3, g0.getGroupSize());
        assertEquals(1.0d, g0.getBudgetDelta(), 1e-9);
        assertNotNull(g0.getDecisionBasis());

        assertEquals(ReleaseStatus.ALLOWED, g1.getStatus());
        assertEquals(3, g1.getGroupSize());

        // 被拒绝的分组：稳定错误类型、无值、零扣减，且不连累其它分组。
        assertEquals(ReleaseStatus.REJECTED, g2.getStatus());
        assertEquals("group_too_small", g2.getErrorCode());
        assertNull(g2.getValue());
        assertEquals(0d, g2.getBudgetDelta(), 1e-9);

        // 预算对账：整批净变化 = 出结果分组扣减之和 = 账本实际变化。
        double sumDelta = batch.getResults().stream()
                .mapToDouble(GatewayResponse::getBudgetDelta).sum();
        assertEquals(2.0d, sumDelta, 1e-9);
        assertEquals(sumDelta, batch.getTotalBudgetDelta(), 1e-9);
        double ledgerSpent = spent(dataset, usWeb) + spent(dataset, euWeb) + spent(dataset, euApp);
        log.info("requestId={} 预算对账 响应合计=-{} 账本实际扣减=-{}", requestId, sumDelta, ledgerSpent);
        assertEquals(sumDelta, ledgerSpent, 1e-9);
        assertEquals(0d, spent(dataset, euApp), 1e-9, "被拒绝分组一分钱预算都不能占");
    }

    @Test
    void sharedBudgetKeyWithinBatchDebitsSameAccount() {
        String requestId = "batch-shared-" + UUID.randomUUID();
        Map<String, String> usWeb = Map.of("region", "us", "channel", "web");

        // 同一批里两个分组命中同一预算键：各扣 1.0，账户合计扣 2.0。
        BatchGatewayResponse batch = gateway.submitBatch(
                batchReq(requestId, List.of(usWeb, usWeb)));

        assertEquals(BatchStatus.ALL_SUCCEEDED, batch.getBatchStatus());
        GatewayResponse g0 = batch.getResults().get(0);
        GatewayResponse g1 = batch.getResults().get(1);
        log.info("requestId={} 同键分组0 预算 {}->{}(-{})", requestId,
                g0.getBudgetBefore(), g0.getBudgetRemaining(), g0.getBudgetDelta());
        log.info("requestId={} 同键分组1 预算 {}->{}(-{})", requestId,
                g1.getBudgetBefore(), g1.getBudgetRemaining(), g1.getBudgetDelta());

        // 第二次扣减的 before 必须接上第一次的 remaining：同一账户顺序扣减。
        assertEquals(g0.getBudgetRemaining(), g1.getBudgetBefore(), 1e-9);
        assertEquals(2.0d, batch.getTotalBudgetDelta(), 1e-9);
        assertEquals(2.0d, spent(dataset, usWeb), 1e-9);
    }

    @Test
    void deterministicBatchRetryIsIdempotentAndDoesNotDoubleCharge() {
        String requestId = "batch-idem-" + UUID.randomUUID();
        Map<String, String> usWeb = Map.of("region", "us", "channel", "web");
        Map<String, String> euApp = Map.of("region", "eu", "channel", "app");

        BatchGatewayResponse first = gateway.submitBatch(
                batchReq(requestId, List.of(usWeb, euApp)));
        BatchGatewayResponse second = gateway.submitBatch(
                batchReq(requestId, List.of(usWeb, euApp)));

        assertEquals(BatchStatus.PARTIAL_SUCCESS, first.getBatchStatus());
        assertEquals(BatchStatus.PARTIAL_SUCCESS, second.getBatchStatus());

        GatewayResponse okAgain = second.getResults().get(0);
        GatewayResponse rejAgain = second.getResults().get(1);
        log.info("requestId={} 重发 成功分组 deduplicated={} 拒绝分组 code={} deduplicated={}",
                requestId, okAgain.isDeduplicated(), rejAgain.getErrorCode(), rejAgain.isDeduplicated());

        // 确定性结论（成功 + 业务拒绝）都幂等复现，不重复扣预算。
        assertTrue(okAgain.isDeduplicated());
        assertEquals(first.getResults().get(0).getValue(), okAgain.getValue());
        assertTrue(rejAgain.isDeduplicated());
        assertEquals("group_too_small", rejAgain.getErrorCode());
        assertEquals(1.0d, spent(dataset, usWeb), 1e-9, "重发不得二次扣减");
        assertEquals(0d, spent(dataset, euApp), 1e-9);
        // 整批合计口径 = 本趟真正新扣的部分：首趟扣 1.0，重发趟一分没扣，合计必须为 0，
        // 才能与预算查询结果直接对上。
        assertEquals(1.0d, first.getTotalBudgetDelta(), 1e-9);
        assertEquals(0d, second.getTotalBudgetDelta(), 1e-9,
                "重发趟没有真正扣预算，整批合计必须为 0");
        log.info("requestId={} 重发对账 首趟合计=-{} 重发趟合计=-{} 账本实际扣减=-{}",
                requestId, first.getTotalBudgetDelta(), second.getTotalBudgetDelta(),
                spent(dataset, usWeb));
    }

    @Test
    void malformedEnvelopeRejectedWithoutTouchingBudget() {
        BatchGatewayResponse noGroups = gateway.submitBatch(
                new BatchQueryRequest("batch-bad-" + UUID.randomUUID(), dataset, List.of(),
                        "amount", AggregationType.SUM, "analyst", policy));
        log.info("批量信封拒绝 code={} 预算变化=0", noGroups.getErrorCode());
        assertEquals(BatchStatus.ALL_FAILED, noGroups.getBatchStatus());
        assertEquals("malformed_request", noGroups.getErrorCode());
        assertEquals(0d, noGroups.getTotalBudgetDelta(), 1e-9);
        assertTrue(noGroups.getResults().isEmpty());
        assertFalse(gateway.submitBatch(null).getErrorCode() == null);
    }
}
