package com.github.h3yow.privacyreleasegatewayjava.budget;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 预算粒度：数据集 + 请求维度组合。维度以排序后的 k=v 串拼接，
 * 保证同一语义维度（不同 Map 迭代顺序）落到同一账户。
 */
public record BudgetKey(String dataset, String dimensionKey) {

    public static BudgetKey of(String dataset, Map<String, String> dimensions) {
        Map<String, String> sorted = new TreeMap<>();
        if (dimensions != null) {
            sorted.putAll(dimensions);
        }
        String key = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        return new BudgetKey(dataset, key);
    }

    public String canonical() {
        return dataset + "#" + dimensionKey;
    }
}
