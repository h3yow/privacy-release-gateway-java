package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetStore;
import com.github.h3yow.privacyreleasegatewayjava.data.DataRepository;
import com.github.h3yow.privacyreleasegatewayjava.data.Dataset;
import com.github.h3yow.privacyreleasegatewayjava.model.MetricSpec;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode;
import com.github.h3yow.privacyreleasegatewayjava.noise.DeterministicNoise;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldPolicy;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldTransform;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyDefinition;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部失败归一化测试：结果生成阶段的底层故障必须映射为稳定 INTERNAL_ERROR，
 * 不透出原始异常信息，且已扣预算被补偿退还（拒绝不耗预算）。
 */
class InternalFailureNormalizationTest {

    private static final Logger log = LoggerFactory.getLogger(InternalFailureNormalizationTest.class);

    /** getFields 正常（通过策略检查），getRows 抛底层异常（模拟解析/取数故障）。 */
    private static class ExplodingRepository extends DataRepository {
        @Override
        public List<Map<String, Object>> getRows(String dataset) {
            throw new IllegalStateException("DB-secret-connection-string://user:pwd@host");
        }
    }

    @Test
    void internalFailureIsMaskedAndBudgetRefunded() {
        DataRepository goodForSchema = new DataRepository();
        goodForSchema.register(new Dataset("customers", List.of(
                Map.of("city", "Beijing"))));
        // 用故障仓库替换：字段元数据来自真实仓库，行读取爆炸
        DataRepository exploding = new ExplodingRepository();
        exploding.register(new Dataset("customers", List.of(Map.of("city", "Beijing"))));

        PolicyRegistry registry = new PolicyRegistry();
        registry.register(new PolicyDefinition(1, 1001L, 1.0d, 1, fields()));
        BudgetStore store = new BudgetStore(3.0d);
        GatewayService service = new GatewayService(registry, exploding, store, new DeterministicNoise());

        QueryRequest request = new QueryRequest("r-boom", null, "customers",
                List.of("city"), List.of(new MetricSpec("COUNT", null)),
                List.of("city"), "analyst", 1);
        QueryResponse response = service.release(request);

        assertFalse(response.allowed());
        assertEquals(RejectionCode.INTERNAL_ERROR, response.rejectionCode(),
                "底层异常必须归一化为稳定错误类型");
        assertFalse(response.decisionBasis().contains("DB-secret"),
                "判定依据中不得透出原始异常细节");
        assertFalse(String.valueOf(response.rejectionDetail()).contains("DB-secret"),
                "拒绝说明中不得透出原始异常细节");
        assertEquals(0d, response.budgetCost(), 1e-9, "内部失败补偿后净成本为 0");
        assertEquals(3.0d, response.budgetRemaining(), 1e-9, "预算被退还，拒绝不耗预算");
        assertEquals(3.0d, store.remaining(new BudgetKey("customers", List.of("city"))), 1e-9);

        // 流水里应能看到一次扣减与一次负向退还，状态视图可解释
        double net = store.ledger(new BudgetKey("customers", List.of("city")))
                .stream().mapToDouble(e -> e.cost()).sum();
        assertEquals(0d, net, 1e-9, "扣减+退还净额为 0");
        log.info("[{}] 内部失败归一化 code={} detail={} budgetNet={} basis={}",
                response.requestId(), response.rejectionCode(), response.rejectionDetail(),
                net, response.decisionBasis());
    }

    private static Map<String, FieldPolicy> fields() {
        return Map.of(
                "city", new FieldPolicy("city", Set.of("analyst"),
                        FieldTransform.GENERALIZE, false, true));
    }
}
