package com.github.h3yow.privacyreleasegatewayjava.web;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：不同 HTTP 请求经过同一网关，验证稳定错误类型不透出原始异常，
 * 且拒绝原因在响应体中可区分。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayControllerIntegrationTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private HttpEntity<String> json(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private String url() {
        return "http://localhost:" + port + "/api/v1/release";
    }

    @Test
    void happyPathReturnsNoisyAggregation() {
        String body = """
                {
                  "requestId": "http-ok",
                  "idempotencyKey": "http-key-1",
                  "dataset": "customers",
                  "dimensions": ["city"],
                  "metrics": [{"type": "COUNT"}],
                  "fields": ["city"],
                  "role": "analyst",
                  "strategyVersion": 1
                }
                """;
        ResponseEntity<String> resp = rest.exchange(url(), HttpMethod.POST, json(body), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String json = resp.getBody();
        assertNotNull(json);
        assertTrue(json.contains("\"allowed\":true"), json);
        log.info("[http-ok] 正常发布响应: {}", json);
    }

    @Test
    void unauthorizedAndExhaustedMapToStableCodes() {
        // viewer 无权访问 name -> 403 UNAUTHORIZED
        String unauth = """
                {
                  "requestId": "http-unauth",
                  "dataset": "customers",
                  "dimensions": ["city"],
                  "metrics": [{"type": "COUNT"}],
                  "fields": ["city", "name"],
                  "role": "viewer",
                  "strategyVersion": 1
                }
                """;
        ResponseEntity<String> r1 = rest.exchange(url(), HttpMethod.POST, json(unauth), String.class);
        assertEquals(HttpStatus.OK, r1.getStatusCode());
        String unauthBody = r1.getBody();
        assertTrue(unauthBody.contains("\"allowed\":false"), unauthBody);
        assertTrue(unauthBody.contains("UNAUTHORIZED"), unauthBody);
        assertFalse(unauthBody.contains("NullPointerException"), "不得透出原始异常");
        log.info("[http-unauth] status={} body={}", r1.getStatusCode(), unauthBody);

        // 预算耗尽：对同一账户连续请求超过 3 次（初始预算 3.0、v1 单价 1.0）
        String templ = """
                {
                  "requestId": "%s",
                  "dataset": "customers",
                  "dimensions": ["age"],
                  "metrics": [{"type": "COUNT"}],
                  "fields": ["age"],
                  "role": "viewer",
                  "strategyVersion": 1
                }
                """;
        for (int i = 0; i < 3; i++) {
            rest.exchange(url(), HttpMethod.POST, json(templ.formatted("http-exh-" + i)), String.class);
        }
        ResponseEntity<String> exhausted = rest.exchange(url(), HttpMethod.POST,
                json(templ.formatted("http-exh-final")), String.class);
        // 拒绝以 200 + allowed=false 表达（业务判定结论），body 中代码可区分
        String body = exhausted.getBody();
        assertTrue(body.contains("\"allowed\":false"), body);
        assertTrue(body.contains("BUDGET_EXHAUSTED"), body);
        log.info("[http-exh-final] 预算耗尽响应: {}", body);
    }

    @Test
    void malformedJsonIsNormalizedToInvalidRequest() {
        ResponseEntity<String> resp = rest.exchange(url(), HttpMethod.POST, json("{ not-json"), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().contains("INVALID_REQUEST"), resp.getBody());
        log.info("[malformed] status={} body={}", resp.getStatusCode(), resp.getBody());
    }
}
