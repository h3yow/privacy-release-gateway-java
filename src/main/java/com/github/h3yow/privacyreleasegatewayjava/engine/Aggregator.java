package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.data.DataRepository;
import com.github.h3yow.privacyreleasegatewayjava.model.MetricSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 分组聚合器（真实值计算，尚未加噪）。
 *
 * <p>按请求维度将数据行分组后计算 COUNT/SUM；分组按维度键字典序输出以保证顺序确定。
 * 组内行数小于策略 minGroupSize 时该组被抑制，并报告被抑制组数用于降级说明
 * （k-匿名最小化：降级而非静默放行，也不整体拒绝）。</p>
 */
public class Aggregator {

    /** 单个聚合组：原始（未脱敏）维度键、真实指标值、组内行数。 */
    public record Group(Map<String, Object> rawKey, Map<String, Double> trueValues, long size) {
    }

    /** 聚合结果：保留组 + 被抑制组数。 */
    public record Aggregation(List<Group> groups, int suppressedGroups) {
    }

    private static final class Acc {
        Map<String, Object> rawKey;
        long size;
        final Map<String, Double> values = new LinkedHashMap<>();
    }

    /** 分组前对维度值做的确定性变换（如年龄泛化）；null 表示原值分组。 */
    public interface DimensionTransformer {
        Object transform(String dimension, Object rawValue);
    }

    private final DataRepository repository;

    public Aggregator(DataRepository repository) {
        this.repository = repository;
    }

    public Aggregation aggregate(String dataset, List<String> dimensions,
                                 List<MetricSpec> metrics, int minGroupSize) {
        return aggregate(dataset, dimensions, metrics, minGroupSize, null);
    }

    public Aggregation aggregate(String dataset, List<String> dimensions,
                                 List<MetricSpec> metrics, int minGroupSize,
                                 DimensionTransformer transformer) {
        // TreeMap 保证组输出顺序确定
        Map<String, Acc> grouped = new TreeMap<>();

        for (Map<String, Object> row : repository.getRows(dataset)) {
            Map<String, Object> keyValues = new LinkedHashMap<>();
            StringBuilder sb = new StringBuilder();
            for (String dim : dimensions) {
                Object raw = row.get(dim);
                Object v = transformer == null ? raw : transformer.transform(dim, raw);
                keyValues.put(dim, v);
                sb.append(String.valueOf(v)).append('|');
            }
            String key = sb.toString();
            Acc acc = grouped.computeIfAbsent(key, k -> {
                Acc a = new Acc();
                a.rawKey = keyValues;
                for (MetricSpec s : metrics) {
                    a.values.put(metricName(s), 0d);
                }
                return a;
            });
            acc.size++;
            for (MetricSpec spec : metrics) {
                if ("COUNT".equals(spec.type())) {
                    acc.values.merge(metricName(spec), 1d, Double::sum);
                } else if ("SUM".equals(spec.type()) && row.get(spec.field()) instanceof Number n) {
                    acc.values.merge(metricName(spec), n.doubleValue(), Double::sum);
                }
            }
        }

        List<Group> kept = new ArrayList<>();
        int suppressed = 0;
        for (Acc acc : grouped.values()) {
            if (acc.size < minGroupSize) {
                suppressed++;
                continue;
            }
            kept.add(new Group(Map.copyOf(acc.rawKey), Map.copyOf(acc.values), acc.size));
        }
        return new Aggregation(List.copyOf(kept), suppressed);
    }

    /** 指标在结果中的稳定名称：COUNT() 或 SUM(field)。 */
    public static String metricName(MetricSpec spec) {
        return "SUM".equals(spec.type()) ? "SUM(" + spec.field() + ")" : "COUNT()";
    }
}
