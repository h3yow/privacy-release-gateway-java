package com.github.h3yow.privacyreleasegatewayjava.config;

import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetStore;
import com.github.h3yow.privacyreleasegatewayjava.data.DataRepository;
import com.github.h3yow.privacyreleasegatewayjava.data.Dataset;
import com.github.h3yow.privacyreleasegatewayjava.engine.GatewayService;
import com.github.h3yow.privacyreleasegatewayjava.engine.PolicyEvaluator;
import com.github.h3yow.privacyreleasegatewayjava.noise.DeterministicNoise;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldPolicy;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldTransform;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyDefinition;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组装内存态网关组件，并播种：
 * <ul>
 *   <li>customers 演示数据集（12 行确定性记录）；</li>
 *   <li>策略 v1（较宽松）与 v2（收紧：age 强制泛化、minGroupSize=2、新噪声种子），
 *       历史版本永久保留，用于验证版本切换后旧结论仍可按版本复现。</li>
 * </ul>
 */
@Configuration
public class GatewayBootstrap {

    public static final double INITIAL_BUDGET = 3.0d;

    @Bean
    public DataRepository dataRepository() {
        DataRepository repo = new DataRepository();
        repo.register(new Dataset("customers", sampleRows()));
        return repo;
    }

    @Bean
    public PolicyRegistry policyRegistry() {
        PolicyRegistry registry = new PolicyRegistry();
        registry.register(new PolicyDefinition(1, 1001L, 1.0d, 1, v1Fields()));
        registry.register(new PolicyDefinition(2, 2002L, 1.5d, 2, v2Fields()));
        return registry;
    }

    @Bean
    public BudgetStore budgetStore() {
        return new BudgetStore(INITIAL_BUDGET);
    }

    @Bean
    public DeterministicNoise deterministicNoise() {
        return new DeterministicNoise();
    }

    @Bean
    public PolicyEvaluator policyEvaluator(PolicyRegistry registry, DataRepository repo) {
        return new PolicyEvaluator(registry, repo);
    }

    @Bean
    public GatewayService gatewayService(PolicyRegistry registry, DataRepository repo,
                                         BudgetStore store, DeterministicNoise noise) {
        return new GatewayService(registry, repo, store, noise);
    }

    private static Map<String, FieldPolicy> v1Fields() {
        Set<String> analyst = Set.of("analyst", "admin");
        Set<String> anyone = Set.of("analyst", "admin", "viewer");
        Map<String, FieldPolicy> m = new LinkedHashMap<>();
        m.put("id", new FieldPolicy("id", analyst, FieldTransform.HASH, false, false));
        m.put("name", new FieldPolicy("name", analyst, FieldTransform.MASK, false, false));
        m.put("age", new FieldPolicy("age", anyone, FieldTransform.NONE, true, true));
        m.put("city", new FieldPolicy("city", anyone, FieldTransform.GENERALIZE, false, true));
        m.put("salary", new FieldPolicy("salary", Set.of("admin"), FieldTransform.NONE, true, false));
        return Map.copyOf(m);
    }

    private static Map<String, FieldPolicy> v2Fields() {
        Set<String> analyst = Set.of("analyst", "admin");
        Set<String> anyone = Set.of("analyst", "admin", "viewer");
        Map<String, FieldPolicy> m = new LinkedHashMap<>();
        m.put("id", new FieldPolicy("id", analyst, FieldTransform.HASH, false, false));
        m.put("name", new FieldPolicy("name", analyst, FieldTransform.SUPPRESS, false, false));
        m.put("age", new FieldPolicy("age", anyone, FieldTransform.GENERALIZE, true, true));
        m.put("city", new FieldPolicy("city", anyone, FieldTransform.GENERALIZE, false, true));
        m.put("salary", new FieldPolicy("salary", Set.of("admin"), FieldTransform.NONE, true, false));
        return Map.copyOf(m);
    }

    private static List<Map<String, Object>> sampleRows() {
        Object[][] data = {
                {"c001", "Alice", 31, "Beijing", 12000},
                {"c002", "Bob", 34, "Beijing", 13500},
                {"c003", "Carol", 42, "Beijing", 21000},
                {"c004", "Dave", 29, "Shanghai", 11000},
                {"c005", "Eve", 27, "Shanghai", 9800},
                {"c006", "Frank", 38, "Shanghai", 18000},
                {"c007", "Grace", 45, "Guangzhou", 26000},
                {"c008", "Heidi", 51, "Guangzhou", 31000},
                {"c009", "Ivan", 23, "Shenzhen", 8600},
                {"c010", "Judy", 36, "Shenzhen", 17000},
                {"c011", "Karl", 33, "Beijing", 15000},
                {"c012", "Liam", 40, "Shanghai", 19500},
        };
        return java.util.Arrays.stream(data)
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", row[0]);
                    m.put("name", row[1]);
                    m.put("age", row[2]);
                    m.put("city", row[3]);
                    m.put("salary", row[4]);
                    return m;
                })
                .toList();
    }
}
