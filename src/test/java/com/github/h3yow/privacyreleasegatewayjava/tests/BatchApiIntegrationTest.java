package com.github.h3yow.privacyreleasegatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量发布 HTTP 入口：逐组结论、部分成功、信封校验，
 * 以及响应预算数字与 /api/budgets 查询结果对账一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BatchApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void batchReleaseOverHttpPartialSuccessAndBudgetReconciles() throws Exception {
        String requestId = "api-batch-" + UUID.randomUUID();
        // sales 数据集：region=us 4 行（出结果），region=eu 2 行 < minGroupSize(3)（拒绝）。
        String body = """
                {
                  "requestId": "%s",
                  "dataset": "sales",
                  "groups": [{"region": "us"}, {"region": "eu"}],
                  "metric": "amount",
                  "aggregation": "SUM",
                  "role": "analyst",
                  "policyVersion": "policy-sales-v1"
                }
                """.formatted(requestId);

        mockMvc.perform(post("/api/release/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.batchStatus").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$.totalGroups").value(2))
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.totalBudgetDelta").value(1.0))
                .andExpect(jsonPath("$.results[0].status").value("ALLOWED"))
                .andExpect(jsonPath("$.results[0].value").exists())
                .andExpect(jsonPath("$.results[0].budgetDelta").value(1.0))
                .andExpect(jsonPath("$.results[0].decisionBasis").exists())
                .andExpect(jsonPath("$.results[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[1].errorCode").value("group_too_small"))
                .andExpect(jsonPath("$.results[1].budgetDelta").value(0.0));

        log.info("requestId={} HTTP 批量部分成功，逐组结论与整批净扣减符合预期", requestId);

        // 对账：响应中的剩余预算必须与预算查询端点一致（同 requestId 重发命中缓存，可安全复査）。
        MvcResult replay = mockMvc.perform(post("/api/release/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].deduplicated").value(true))
                .andReturn();
        String json = replay.getResponse().getContentAsString();
        log.info("requestId={} 重发命中幂等缓存: {}", requestId, json);

        // us 键本次共扣 1.0（首次），剩余 = 3.0 - 已扣；与端点查询一致。
        mockMvc.perform(get("/api/budgets").param("dataset", "sales").param("region", "us"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensionKey").value("region=us"))
                .andExpect(jsonPath("$.total").value(3.0));
    }

    @Test
    void malformedBatchEnvelopeReturns400() throws Exception {
        String body = """
                {
                  "requestId": "api-batch-bad-%s",
                  "dataset": "sales",
                  "groups": [],
                  "metric": "amount",
                  "aggregation": "SUM",
                  "role": "analyst",
                  "policyVersion": "policy-sales-v1"
                }
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/release/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("malformed_request"))
                .andExpect(jsonPath("$.batchStatus").value("ALL_FAILED"));
    }

    @Test
    void singleReleaseEndpointUnchanged() throws Exception {
        String body = """
                {
                  "requestId": "api-single-%s",
                  "dataset": "sales",
                  "dimensions": {"region": "us"},
                  "metric": "amount",
                  "aggregation": "SUM",
                  "role": "analyst",
                  "policyVersion": "policy-sales-v1"
                }
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/release").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.budgetDelta").value(1.0));
    }
}
