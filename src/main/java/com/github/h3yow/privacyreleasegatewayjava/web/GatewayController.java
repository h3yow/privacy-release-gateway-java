package com.github.h3yow.privacyreleasegatewayjava.web;

import com.github.h3yow.privacyreleasegatewayjava.batch.BatchGatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.batch.BatchQueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetLedger;
import com.github.h3yow.privacyreleasegatewayjava.engine.PrivacyGateway;
import com.github.h3yow.privacyreleasegatewayjava.model.GatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 唯一对外入口。所有请求都经 {@link PrivacyGateway} 同一判定路径，
 * 因此 HTTP 与测试直连对同一输入必然得到一致结论。
 */
@RestController
@RequestMapping("/api")
public class GatewayController {

    private final PrivacyGateway privacyGateway;
    private final PolicyRegistry policyRegistry;
    private final BudgetLedger budgetLedger;

    public GatewayController(PrivacyGateway privacyGateway,
                             PolicyRegistry policyRegistry,
                             BudgetLedger budgetLedger) {
        this.privacyGateway = privacyGateway;
        this.policyRegistry = policyRegistry;
        this.budgetLedger = budgetLedger;
    }

    @PostMapping("/release")
    public ResponseEntity<GatewayResponse> release(@RequestBody QueryRequest request) {
        GatewayResponse response = privacyGateway.submit(request);
        return ResponseEntity.status(
                "REJECTED".equals(response.getStatus().name()) ? 422 : 200).body(response);
    }

    /**
     * 批量发布入口。只要请求本身合法就返回 200，逐分组结论在 results 中
     * （部分成功是正常语义，不是传输层错误）；整批报文不合法时返回 400。
     */
    @PostMapping("/release/batch")
    public ResponseEntity<BatchGatewayResponse> releaseBatch(@RequestBody BatchQueryRequest request) {
        BatchGatewayResponse response = privacyGateway.submitBatch(request);
        return ResponseEntity.status(response.getBatchError() != null ? 400 : 200).body(response);
    }

    @GetMapping("/budgets")
    public Map<String, Object> budget(@RequestParam String dataset,
                                      @RequestParam(required = false)
                                      Map<String, String> allParams) {
        // Spring 会把所有查询参数（含 dataset 本身）注入 Map，这里显式剔除，
        // 保证预算键只由真正的维度参数构成，与发布路径保持一致。
        Map<String, String> dimensions = new java.util.HashMap<>(
                allParams == null ? Map.of() : allParams);
        dimensions.remove("dataset");
        BudgetKey key = BudgetKey.of(dataset, dimensions);
        var snapshot = budgetLedger.snapshot(key, 3.0d);
        return Map.of(
                "dataset", key.dataset(),
                "dimensionKey", key.dimensionKey(),
                "total", snapshot.total(),
                "remaining", snapshot.remaining(),
                "spent", snapshot.spent());
    }

    @GetMapping("/policies")
    public Object policies() {
        return Map.of("versions", policyRegistry.versions());
    }

    @GetMapping("/policies/{version}")
    public Object policy(@PathVariable String version) {
        return policyRegistry.findVersion(version)
                .map(p -> (Object) Map.of(
                        "version", p.version(),
                        "datasets", p.datasets(),
                        "epsilon", p.epsilon(),
                        "minGroupSize", p.minGroupSize(),
                        "strictMissing", p.strictMissing(),
                        "hasConflictingRules", p.hasConflictingRules()))
                .orElseGet(() -> Map.of("version", version, "registered", false));
    }
}
