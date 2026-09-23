package com.github.h3yow.privacyreleasegatewayjava.engine;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略版本化执行测试：
 * <ul>
 *   <li>同一版本对相同输入可复现；</li>
 *   <li>切换版本后输出（脱敏口径/降级/噪声）按新版本变化；</li>
 *   <li>历史版本定义永久保留，旧结论仍能按旧版本复现；</li>
 *   <li>新策略下被禁止的访问给出可区分拒绝原因，而不是静默放行。</li>
 * </ul>
 */
class PolicyVersionSwitchTest {

    private static final Logger log = LoggerFactory.getLogger(PolicyVersionSwitchTest.class);

    private final TestHarness h = new TestHarness();

    private QueryRequest countByAge(String rid, Integer version) {
        return new QueryRequest(rid, null, "customers",
                List.of("age"), List.of(new MetricSpec("COUNT", null)),
                List.of("age"), "viewer", version);
    }

    @Test
    void historicalVersionsRemainReproducibleAfterUpgrade() {
        QueryResponse v1a = h.service.release(countByAge("r-v1-a", 1));
        QueryResponse v1b = h.service.release(countByAge("r-v1-b", 1));
        QueryResponse v2a = h.service.release(countByAge("r-v2-a", 2));
        QueryResponse v2b = h.service.release(countByAge("r-v2-b", 2));

        assertTrue(v1a.allowed() && v2a.allowed());
        assertEquals(v1a.rows(), v1b.rows(), "v1 结论可复现");
        assertEquals(v2a.rows(), v2b.rows(), "v2 结论可复现");
        assertNotEquals(v1a.rows(), v2a.rows(),
                "v2 对 age 强制泛化且 minGroupSize=2，输出应与 v1 不同");

        // v1：age 原值返回（NONE），minGroupSize=1 单值年龄组保留 -> 出现 6 个组
        assertEquals(6, v1a.rows().size(), "v1 不做小组抑制");
        // v2：年龄泛化到 10 岁桶 + minGroupSize=2：[20,30)=2 [30,40)=3 [40,50)=1
        //     [40,50) 只有 1 行被抑制（降级），剩 2 个组
        assertEquals(2, v2a.rows().size(), "v2 抑制单元素年龄桶");
        assertTrue(v2a.degraded(), "发生小组抑制时必须显式标记降级，不静默丢弃");
        assertFalse(v1a.degraded());

        for (var row : v2a.rows()) {
            Object age = row.group().get("age");
            assertTrue(age.toString().matches("\\[\\d+,\\d+\\)"),
                    "v2 的 age 必须为泛化桶而不是原值: " + age);
        }

        // 历史结论按版本复现：策略升级后重新请求 v1，仍得到 v1 口径
        QueryResponse v1Again = h.service.release(countByAge("r-v1-again", 1));
        assertEquals(v1a.rows(), v1Again.rows(), "策略升级后 v1 历史结论仍可复现");

        log.info("[{}] v1 rows={} basis={}", v1a.requestId(), v1a.rows(), v1a.decisionBasis());
        log.info("[{}] v2 rows={} degraded={} basis={}", v2a.requestId(),
                v2a.rows(), v2a.degraded(), v2a.decisionBasis());
    }

    @Test
    void defaultVersionFollowsCurrentAndUnknownVersionIsConflict() {
        QueryRequest noVersion = new QueryRequest("r-default", null, "customers",
                List.of("age"), List.of(new MetricSpec("COUNT", null)),
                List.of("age"), "viewer", null);
        QueryResponse def = h.service.release(noVersion);
        assertEquals(2, def.strategyVersion(), "未指定版本时使用当前生效版本 v2");

        QueryResponse unknown = h.service.release(countByAge("r-unknown", 77));
        assertFalse(unknown.allowed());
        assertEquals(RejectionCode.POLICY_CONFLICT, unknown.rejectionCode(),
                "未知策略版本必须可区分拒绝");
        assertEquals(0d, unknown.budgetCost(), 1e-12);
        log.info("[{}] 默认版本={} rows={}; 未知版本拒绝 code={} detail={}",
                def.requestId(), def.strategyVersion(), def.rows().size(),
                unknown.rejectionCode(), unknown.rejectionDetail());
    }

    @Test
    void tighteningPolicyProducesDistinguishableRejection() {
        // name 在 v1 对 analyst 允许（MASK），在 v2 被 SUPPRESS：请求明细字段必须冲突拒绝
        QueryRequest reqV1 = new QueryRequest("r-name-v1", null, "customers",
                List.of("city"), List.of(new MetricSpec("COUNT", null)),
                List.of("city", "name"), "analyst", 1);
        QueryRequest reqV2 = new QueryRequest("r-name-v2", null, "customers",
                List.of("city"), List.of(new MetricSpec("COUNT", null)),
                List.of("city", "name"), "analyst", 2);
        assertTrue(h.service.release(reqV1).allowed(), "v1 下 name 掩码后可发布");
        QueryResponse rejected = h.service.release(reqV2);
        assertFalse(rejected.allowed());
        assertEquals(RejectionCode.POLICY_CONFLICT, rejected.rejectionCode(),
                "v2 抑制 name，必须拒绝而非静默放行");
        log.info("[{}] 策略收紧拒绝 code={} detail={}", rejected.requestId(),
                rejected.rejectionCode(), rejected.rejectionDetail());
    }

    private static final class TestHarness {
        final GatewayService service;

        TestHarness() {
            DataRepository repo = new DataRepository();
            repo.register(new Dataset("customers", rows()));
            PolicyRegistry registry = new PolicyRegistry();
            registry.register(new PolicyDefinition(1, 1001L, 1.0d, 1, v1()));
            registry.register(new PolicyDefinition(2, 2002L, 1.5d, 2, v2()));
            this.service = new GatewayService(registry, repo, new BudgetStore(100d),
                    new DeterministicNoise());
        }

        private static Map<String, FieldPolicy> v1() {
            Map<String, FieldPolicy> m = new LinkedHashMap<>();
            m.put("id", new FieldPolicy("id", Set.of("analyst", "admin"), FieldTransform.HASH, false, false));
            m.put("name", new FieldPolicy("name", Set.of("analyst", "admin"), FieldTransform.MASK, false, false));
            m.put("age", new FieldPolicy("age", Set.of("analyst", "admin", "viewer"), FieldTransform.NONE, true, true));
            m.put("city", new FieldPolicy("city", Set.of("analyst", "admin", "viewer"), FieldTransform.GENERALIZE, false, true));
            m.put("salary", new FieldPolicy("salary", Set.of("admin"), FieldTransform.NONE, true, false));
            return Map.copyOf(m);
        }

        private static Map<String, FieldPolicy> v2() {
            Map<String, FieldPolicy> m = new LinkedHashMap<>();
            m.put("id", new FieldPolicy("id", Set.of("analyst", "admin"), FieldTransform.HASH, false, false));
            m.put("name", new FieldPolicy("name", Set.of("analyst", "admin"), FieldTransform.SUPPRESS, false, false));
            m.put("age", new FieldPolicy("age", Set.of("analyst", "admin", "viewer"), FieldTransform.GENERALIZE, true, true));
            m.put("city", new FieldPolicy("city", Set.of("analyst", "admin", "viewer"), FieldTransform.GENERALIZE, false, true));
            m.put("salary", new FieldPolicy("salary", Set.of("admin"), FieldTransform.NONE, true, false));
            return Map.copyOf(m);
        }

        private static List<Map<String, Object>> rows() {
            Object[][] data = {
                    {"c001", "Alice", 21, "Beijing", 12000},
                    {"c002", "Bob", 23, "Beijing", 13500},
                    {"c003", "Carol", 31, "Beijing", 21000},
                    {"c004", "Dave", 34, "Shanghai", 11000},
                    {"c005", "Eve", 38, "Shanghai", 9800},
                    {"c006", "Frank", 42, "Shanghai", 18000},
            };
            return java.util.Arrays.stream(data).map(row -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row[0]);
                m.put("name", row[1]);
                m.put("age", row[2]);
                m.put("city", row[3]);
                m.put("salary", row[4]);
                return m;
            }).toList();
        }
    }
}
