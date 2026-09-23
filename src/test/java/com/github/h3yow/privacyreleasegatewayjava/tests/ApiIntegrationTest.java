package com.github.h3yow.privacyreleasegatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口边界：成功请求、可区分拒绝、错误 JSON 归一化（不含内部细节），
 * 以及预算查询端点展示预算变化。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void releaseHappyPathOverHttp() throws Exception {
        String body = """
                {
                  "requestId": "api-ok-%s",
                  "dataset": "sales",
                  "dimensions": {"region": "us"},
                  "metric": "amount",
                  "aggregation": "SUM",
                  "role": "analyst",
                  "policyVersion": "policy-sales-v1"
                }
                """.formatted(UUID.randomUUID());
        log.info("requestId 见请求体 判定依据=HTTP happy path");
        mockMvc.perform(post("/api/release").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.value").exists())
                .andExpect(jsonPath("$.budgetDelta").value(1.0))
                .andExpect(jsonPath("$.decisionBasis").exists());
    }

    @Test
    void malformedJsonMapsToStableErrorWithoutInternals() throws Exception {
        String body = "{ not-json ";
        mockMvc.perform(post("/api/release").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("malformed_request"))
                .andExpect(jsonPath("$.message").value("请求格式不正确或缺少必填字段"));
    }

    @Test
    void accessDeniedIsDistinguishableOverHttp() throws Exception {
        String body = """
                {
                  "requestId": "api-deny-%s",
                  "dataset": "sales",
                  "dimensions": {"region": "us"},
                  "metric": "amount",
                  "aggregation": "SUM",
                  "role": "viewer",
                  "policyVersion": "policy-sales-v1"
                }
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/release").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCode").value("access_denied"))
                .andExpect(jsonPath("$.budgetDelta").value(0));
    }

    @Test
    void budgetEndpointReflectsState() throws Exception {
        mockMvc.perform(get("/api/budgets").param("dataset", "sales").param("region", "us"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.remaining").exists());
    }
}
