package com.github.h3yow.privacyreleasegatewayjava.tests;

import com.github.h3yow.privacyreleasegatewayjava.engine.PrivacyGateway;
import com.github.h3yow.privacyreleasegatewayjava.model.AggregationType;
import com.github.h3yow.privacyreleasegatewayjava.model.GatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.ReleaseStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复请求边界：同一 requestId 只产生一次判定与一次预算扣减，后续命中缓存结论完全一致。
 */
@SpringBootTest
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    @Autowired
    private PrivacyGateway gateway;

    @Test
    void repeatedRequestServesSameConclusionAndConsumesOnce() {
        String requestId = "idem-" + UUID.randomUUID();
        QueryRequest request = new QueryRequest(
                requestId, "sales", Map.of("region", "us"),
                "amount", AggregationType.SUM, "analyst", "policy-sales-v1");

        GatewayResponse first = gateway.submit(request);
        GatewayResponse second = gateway.submit(request);

        log.info("requestId={} 判定依据(first)={}", requestId, first.getDecisionBasis());
        log.info("requestId={} 重复请求 status={} deduplicated={} 预算变化=0(第二次)",
                requestId, second.getStatus(), second.isDeduplicated());

        assertEquals(ReleaseStatus.ALLOWED, first.getStatus());
        assertEquals(ReleaseStatus.ALLOWED, second.getStatus());
        assertFalse(first.isDeduplicated());
        assertTrue(second.isDeduplicated());
        assertEquals(first.getValue(), second.getValue());
        assertEquals(first.getBudgetRemaining(), second.getBudgetRemaining());
        assertEquals(first.getPolicyVersion(), second.getPolicyVersion());
        assertEquals(1.0d, first.getBudgetDelta());
        assertNotNull(second.getMaskedDimensions());
    }

    @Test
    void rejectedRequestIsAlsoIdempotentAndCostsNothing() {
        String requestId = "idem-rej-" + UUID.randomUUID();
        // eu 分组 2 行 < minGroupSize(3)，必然 GROUP_TOO_SMALL。
        QueryRequest request = new QueryRequest(
                requestId, "sales", Map.of("region", "eu"),
                "amount", AggregationType.SUM, "analyst", "policy-sales-v1");

        GatewayResponse first = gateway.submit(request);
        GatewayResponse second = gateway.submit(request);

        log.info("requestId={} 拒绝类型={} 预算变化=0 依据={}",
                requestId, first.getErrorCode(), first.getDecisionBasis());

        assertEquals(ReleaseStatus.REJECTED, first.getStatus());
        assertEquals("group_too_small", first.getErrorCode());
        assertEquals(0d, first.getBudgetDelta());
        assertTrue(second.isDeduplicated());
        assertEquals(first.getErrorCode(), second.getErrorCode());
    }
}
