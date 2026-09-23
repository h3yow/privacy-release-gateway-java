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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 策略版本边界：同一版本 + 相同输入结论可复现；不同版本结论与投影不同；
 * 新版本成为默认后，显式指定旧版本仍按旧版本复现历史结论。
 * 每个测试方法使用独立数据集，避免共享预算账户造成相互干扰。
 */
@SpringBootTest
class PolicyVersionTest {

    private static final Logger log = LoggerFactory.getLogger(PolicyVersionTest.class);

    @Autowired
    private PrivacyGateway gateway;
    @Autowired
    private DatasetRepository repository;
    @Autowired
    private PolicyRegistry registry;

    private String dataset;

    @BeforeEach
    void setUpIsolatedDataset() {
        dataset = "iso-" + UUID.randomUUID();
        repository.registerDataset(dataset, List.of(
                new DataRow("i1", Map.of("region", "us", "customerId", "C-1001", "amount", 100)),
                new DataRow("i2", Map.of("region", "us", "customerId", "C-1002", "amount", 200)),
                new DataRow("i3", Map.of("region", "us", "customerId", "C-1003", "amount", 300)),
                new DataRow("i4", Map.of("region", "us", "customerId", "C-1004", "amount", 400))
        ));
        Set<String> roles = Set.of("analyst");
        String v1 = dataset + "-v1";
        String v2 = dataset + "-v2";
        registry.register(new MaskingPolicy(v1, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.NONE),
                new FieldRule("customerId", roles, FieldTransform.MASK, 3, 3600),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 1.0d, 3, false), true);
        registry.register(new MaskingPolicy(v2, Set.of(dataset), List.of(
                new FieldRule("region", roles, FieldTransform.GENERALIZE, 2, 3600),
                new FieldRule("customerId", roles, FieldTransform.HASH, 3, 3600),
                new FieldRule("amount", roles, FieldTransform.NONE)
        ), 0.5d, 3, false), false);
    }

    private QueryRequest request(String id, String version) {
        return new QueryRequest(id, dataset, Map.of("region", "us"), "amount",
                AggregationType.SUM, "analyst", version);
    }

    @Test
    void sameVersionSameInputIsReproducible() {
        String v1 = dataset + "-v1";
        GatewayResponse a = gateway.submit(request("rep-a-" + UUID.randomUUID(), v1));
        GatewayResponse b = gateway.submit(request("rep-b-" + UUID.randomUUID(), v1));

        log.info("请求A requestId={} value={} 投影={} 依据={}",
                a.getRequestId(), a.getValue(), a.getMaskedDimensions(), a.getDecisionBasis());
        log.info("请求B requestId={} value={} 投影={}", b.getRequestId(), b.getValue(), b.getMaskedDimensions());

        assertEquals(ReleaseStatus.ALLOWED, a.getStatus());
        assertEquals(a.getValue(), b.getValue(), "同版本同输入噪声必须完全一致");
        assertEquals(a.getMaskedDimensions(), b.getMaskedDimensions());
        assertEquals(a.getNoiseScale(), b.getNoiseScale());
        // 维度投影：v1 对 region 为 NONE，保留原值。
        assertEquals("us", a.getMaskedDimensions().get("region"));
    }

    @Test
    void switchingVersionChangesConclusionButBothReproducible() {
        String v1 = dataset + "-v1";
        String v2 = dataset + "-v2";
        GatewayResponse r1 = gateway.submit(request("sw-v1-" + UUID.randomUUID(), v1));
        GatewayResponse r2a = gateway.submit(request("sw-v2a-" + UUID.randomUUID(), v2));
        GatewayResponse r2b = gateway.submit(request("sw-v2b-" + UUID.randomUUID(), v2));

        log.info("requestId={} v1 value={} scale={} 投影={}", r1.getRequestId(), r1.getValue(),
                r1.getNoiseScale(), r1.getMaskedDimensions());
        log.info("requestId={} v2 value={} scale={} 投影={}", r2a.getRequestId(), r2a.getValue(),
                r2a.getNoiseScale(), r2a.getMaskedDimensions());

        // v1 epsilon=1.0 => scale=1.0；v2 epsilon=0.5 => scale=2.0，噪声尺度与种子均不同。
        assertNotEquals(r1.getNoiseScale(), r2a.getNoiseScale());
        assertNotEquals(r1.getMaskedDimensions(), r2a.getMaskedDimensions());
        assertEquals(1.0d, r1.getNoiseScale());
        assertEquals(2.0d, r2a.getNoiseScale());
        // 各自版本内仍可复现。
        assertEquals(r2a.getValue(), r2b.getValue());
        assertEquals(v2, r2a.getPolicyVersion());
        assertEquals(v1, r1.getPolicyVersion());
    }

    @Test
    void unknownVersionRejectedDistinguishably() {
        QueryRequest request = request("miss-" + UUID.randomUUID(), "does-not-exist");
        GatewayResponse response = gateway.submit(request);
        log.info("requestId={} 拒绝类型={} 预算变化={} 依据={}",
                response.getRequestId(), response.getErrorCode(),
                response.getBudgetDelta(), response.getDecisionBasis());

        assertEquals(ReleaseStatus.REJECTED, response.getStatus());
        assertEquals("policy_version_not_found", response.getErrorCode());
        assertEquals(0d, response.getBudgetDelta());
    }
}
