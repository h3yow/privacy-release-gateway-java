package com.github.h3yow.privacyreleasegatewayjava.config;

import com.github.h3yow.privacyreleasegatewayjava.data.DataRow;
import com.github.h3yow.privacyreleasegatewayjava.data.DatasetRepository;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldRule;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldTransform;
import com.github.h3yow.privacyreleasegatewayjava.policy.MaskingPolicy;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地化演示数据与策略版本：
 * <ul>
 *   <li>sales：region 精确分组（us 组 4 行 ≥ 阈值；eu 组 2 行 < 阈值）。</li>
 *   <li>policy-sales-v1：customerId 掩码，epsilon=1.0（默认版本）。</li>
 *   <li>policy-sales-v2：customerId 哈希、region 泛化，epsilon=0.5（新版本，切换后结论不同但各自可复现）。</li>
 *   <li>policy-sales-conflict：同字段规则冲突，验证 POLICY_CONFLICT 不静默放行。</li>
 * </ul>
 */
@Configuration
public class BootstrapData {

    public static final String SALES = "sales";
    public static final String POLICY_V1 = "policy-sales-v1";
    public static final String POLICY_V2 = "policy-sales-v2";
    public static final String POLICY_CONFLICT = "policy-sales-conflict";

    @Bean
    CommandLineRunner bootstrap(DatasetRepository repository, PolicyRegistry registry) {
        return args -> {
            repository.registerDataset(SALES, List.of(
                    new DataRow("r1", Map.of("region", "us", "customerId", "C-1001", "amount", 100)),
                    new DataRow("r2", Map.of("region", "us", "customerId", "C-1002", "amount", 200)),
                    new DataRow("r3", Map.of("region", "us", "customerId", "C-1003", "amount", 300)),
                    new DataRow("r4", Map.of("region", "us", "customerId", "C-1004", "amount", 400)),
                    new DataRow("r5", Map.of("region", "eu", "customerId", "C-2001", "amount", 50)),
                    new DataRow("r6", Map.of("region", "eu", "customerId", "C-2002"))
            ));

            Set<String> analyst = Set.of("analyst", "admin");
            Set<String> viewer = Set.of("viewer");

            MaskingPolicy v1 = new MaskingPolicy(
                    POLICY_V1, Set.of(SALES),
                    List.of(
                            new FieldRule("region", analyst, FieldTransform.NONE),
                            new FieldRule("customerId", analyst, FieldTransform.MASK, 3, 3600),
                            new FieldRule("amount", analyst, FieldTransform.NONE)
                    ),
                    1.0d, 3, false);

            MaskingPolicy v2 = new MaskingPolicy(
                    POLICY_V2, Set.of(SALES),
                    List.of(
                            new FieldRule("region", analyst, FieldTransform.GENERALIZE, 2, 3600),
                            new FieldRule("customerId", analyst, FieldTransform.HASH, 3, 3600),
                            new FieldRule("amount", analyst, FieldTransform.NONE)
                    ),
                    0.5d, 3, false);

            MaskingPolicy conflict = new MaskingPolicy(
                    POLICY_CONFLICT, Set.of(SALES),
                    List.of(
                            new FieldRule("customerId", analyst, FieldTransform.MASK, 3, 3600),
                            new FieldRule("customerId", analyst, FieldTransform.HASH, 3, 3600),
                            new FieldRule("region", analyst, FieldTransform.NONE),
                            new FieldRule("amount", analyst, FieldTransform.NONE)
                    ),
                    1.0d, 3, false);

            registry.register(v1, true);
            registry.register(v2, false);
            registry.register(conflict, false);

            // viewer 在任何版本下都无权访问 sales，验证越权归类。
            MaskingPolicy unused = new MaskingPolicy(
                    "policy-unused-v1", Set.of("__none__"),
                    List.of(new FieldRule("x", viewer, FieldTransform.NONE)),
                    1.0d, 1, true);
            registry.register(unused, false);
        };
    }
}
