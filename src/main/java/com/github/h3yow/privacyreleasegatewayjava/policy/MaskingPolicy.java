package com.github.h3yow.privacyreleasegatewayjava.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个不可变的脱敏 / 最小化策略版本。
 * <ul>
 *   <li>同 version + 同输入必须产出完全可复现的结论（噪声也由版本派生种子）。</li>
 *   <li>epsilon 是每次查询的隐私预算单价；minGroupSize 是允许发布的最小分组。</li>
 *   <li>strictMissing=true 时字段缺失直接拒绝；false 时降级（剔除该字段）。</li>
 *   <li>同一字段允许出现多条等价规则（不同注册来源）；若同一字段出现互相矛盾的规则
 *       （转换方式不一致），构造期不报错，而由求值器在触达该字段时确定性地归类为 POLICY_CONFLICT，
 *       从而保证冲突不被静默放行。</li>
 * </ul>
 */
public class MaskingPolicy {

    private final String version;
    private final Set<String> datasets;
    private final List<FieldRule> fieldRules;
    private final double epsilon;
    private final int minGroupSize;
    private final boolean strictMissing;

    public MaskingPolicy(String version, Set<String> datasets, List<FieldRule> fieldRules,
                         double epsilon, int minGroupSize, boolean strictMissing) {
        this.version = version;
        this.datasets = Set.copyOf(datasets);
        this.fieldRules = List.copyOf(fieldRules);
        this.epsilon = epsilon;
        this.minGroupSize = minGroupSize;
        this.strictMissing = strictMissing;
    }

    /** 返回字段 -> 规则列表（等价规则可能有多条）。 */
    public Map<String, List<FieldRule>> rulesByField() {
        Map<String, List<FieldRule>> grouped = new HashMap<>();
        for (FieldRule rule : fieldRules) {
            grouped.computeIfAbsent(rule.field(), k -> new ArrayList<>()).add(rule);
        }
        return grouped;
    }

    /** 策略内部是否存在同字段转换方式互相矛盾的规则。 */
    public boolean hasConflictingRules() {
        for (List<FieldRule> rules : rulesByField().values()) {
            Set<FieldTransform> transforms = new HashSet<>();
            for (FieldRule rule : rules) {
                transforms.add(rule.transform());
            }
            if (transforms.size() > 1) {
                return true;
            }
        }
        return false;
    }

    public String version() {
        return version;
    }

    public boolean appliesTo(String dataset) {
        return datasets.contains(dataset);
    }

    public Set<String> datasets() {
        return datasets;
    }

    public List<FieldRule> fieldRules() {
        return fieldRules;
    }

    public double epsilon() {
        return epsilon;
    }

    public int minGroupSize() {
        return minGroupSize;
    }

    public boolean strictMissing() {
        return strictMissing;
    }
}
