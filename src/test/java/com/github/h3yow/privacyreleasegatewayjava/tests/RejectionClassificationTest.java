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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒绝分类边界：
 * 各拒绝原因可区分且可复现；多问题同时命中时按确定性优先级归类；
 * 被拒绝的请求预算变化恒为 0；字段缺失在非严格策略下降级放行并给出说明。
 */
@SpringBootTest
class RejectionClassificationTest {

    private static final Logger log = LoggerFactory.getLogger(RejectionClassificationTest.class);

    @Autowired
    private PrivacyGateway gateway;
    @Autowired
    private DatasetRepository repository;
    @Autowired
    private PolicyRegistry registry;

    private String dataset;

    @BeforeEach
    void setUp() {
        dataset = "rej-" + UUID.randomUUID();
        Set<String> roles = Set.of("analyst");
        // 非严格缺失：4 行中 1 行没有 amount，降级剔除后仍有 3 行满足阈值。
        repository.registerDataset(dataset, List.of(
                new DataRow("j1", Map.of("region", "us", "amount", 100)),
                new DataRow("j2", Map.of("region", "us", "amount", 200)),
                new DataRow("j3", Map.of("region", "us", "amount", 300)),
                new DataRow("j4", Map.of("region", "us")),
                new DataRow("j5", Map.of("region", "tiny", "amount", 50))
        ));
        registry.register(new MaskingPolicy(dataset + "-p", Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);
        registry.register(new MaskingPolicy(dataset + "-strict", Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, true), false);
        registry.register(new MaskingPolicy(dataset + "-conflict", Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.NONE),
                new FieldRule("amount", roles, FieldTransform.RESTRICT)
        ), 1.0d, 3, false), false);
    }

    private QueryRequest req(String id, String role, String version, String region) {
        return new QueryRequest(id, dataset, Map.of("region", region == null ? "us" : region),
                "amount", AggregationType.SUM, role, version);
    }

    private void expectReject(String name, GatewayResponse response, String expectedCode) {
        log.info("[{}] requestId={} status={} code={} 预算变化={} 依据={}",
                name, response.getRequestId(), response.getStatus(), response.getErrorCode(),
                response.getBudgetDelta(), response.getDecisionBasis());
        assertEquals(ReleaseStatus.REJECTED, response.getStatus(), name);
        assertEquals(expectedCode, response.getErrorCode(), name);
        assertEquals(0d, response.getBudgetDelta(), name + " 拒绝不得消耗预算");
    }

    @Test
    void allRejectionReasonsAreDistinguishableAndReproducible() {
        expectReject("malformed",
                gateway.submit(new QueryRequest("r-mal-" + UUID.randomUUID(), dataset,
                        Map.of("region", "us"), "amount", AggregationType.SUM, "  ", dataset + "-p")),
                "malformed_request");
        expectReject("datasetNotFound",
                gateway.submit(new QueryRequest("r-ds-" + UUID.randomUUID(), "no-such-dataset",
                        Map.of("region", "us"), "amount", AggregationType.SUM, "analyst", null)),
                "dataset_not_found");
        expectReject("policyMissing",
                gateway.submit(req("r-pm-" + UUID.randomUUID(), "analyst", "no-such-version", "us")),
                "policy_version_not_found");
        expectReject("accessDenied",
                gateway.submit(req("r-ac-" + UUID.randomUUID(), "viewer", dataset + "-p", "us")),
                "access_denied");
        expectReject("policyConflict",
                gateway.submit(req("r-pc-" + UUID.randomUUID(), "analyst", dataset + "-conflict", "us")),
                "policy_conflict");
        expectReject("groupTooSmall",
                gateway.submit(req("r-gs-" + UUID.randomUUID(), "analyst", dataset + "-p", "tiny")),
                "group_too_small");
        expectReject("fieldMissingStrict",
                gateway.submit(req("r-fm-" + UUID.randomUUID(), "analyst", dataset + "-strict", "us")),
                "field_missing");
    }

    @Test
    void priorityIsDeterministicWhenMultipleIssuesCoexist() {
        // 同时缺角色（access_denied, 优先级40）且命中冲突策略（policy_conflict, 优先级50）。
        // 按确定性优先级必须归类为 access_denied。
        GatewayResponse response = gateway.submit(
                req("r-prio-" + UUID.randomUUID(), "viewer", dataset + "-conflict", "us"));
        expectReject("priority", response, "access_denied");
        assertTrue(response.getDecisionBasis().contains("优先级=40"),
                "判定依据必须体现优先级: " + response.getDecisionBasis());
    }

    @Test
    void missingFieldDegradesWithExplicitNotice() {
        GatewayResponse response = gateway.submit(
                req("r-deg-" + UUID.randomUUID(), "analyst", dataset + "-p", "us"));
        log.info("[degrade] requestId={} status={} value={} groupSize={} 预算变化={}->{} 依据={}",
                response.getRequestId(), response.getStatus(), response.getValue(),
                response.getGroupSize(), response.getBudgetBefore(), response.getBudgetRemaining(),
                response.getDecisionBasis());
        assertEquals(ReleaseStatus.DEGRADED, response.getStatus());
        assertEquals(3, response.getGroupSize());
        assertEquals(1, response.getNotices().size());
        assertTrue(response.getNotices().get(0).contains("降级"));
        assertEquals(1.0d, response.getBudgetDelta());
    }

    @Test
    void sameRejectionIsReproducibleAcrossSubmissions() {
        GatewayResponse a = gateway.submit(
                req("r-rep1-" + UUID.randomUUID(), "viewer", dataset + "-p", "us"));
        GatewayResponse b = gateway.submit(
                req("r-rep2-" + UUID.randomUUID(), "viewer", dataset + "-p", "us"));
        assertEquals(a.getErrorCode(), b.getErrorCode());
        assertEquals(a.getStatus(), b.getStatus());
    }
}
