package com.github.h3yow.privacyreleasegatewayjava.data;

import com.github.h3yow.privacyreleasegatewayjava.engine.GatewayException;
import com.github.h3yow.privacyreleasegatewayjava.model.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存数据集仓库（本地化网关的演示存储）。维度过滤为精确匹配：
 * 行数据缺失某维度键时不命中，交由策略层决定缺失语义。
 */
@Component
public class DatasetRepository {

    private final Map<String, List<DataRow>> datasets = new ConcurrentHashMap<>();

    public void registerDataset(String name, List<DataRow> rows) {
        datasets.put(name, List.copyOf(rows));
    }

    public boolean exists(String dataset) {
        return dataset != null && datasets.containsKey(dataset);
    }

    public List<DataRow> rows(String dataset) {
        if (!exists(dataset)) {
            throw new GatewayException(ErrorCode.DATASET_NOT_FOUND, "数据集不存在: " + dataset);
        }
        return datasets.get(dataset);
    }

    /** 返回满足所有维度等值条件的行；数据集不存在时归一化为 DATASET_NOT_FOUND。 */
    public List<DataRow> matchingRows(String dataset, Map<String, String> dimensions) {
        List<DataRow> all = rows(dataset);
        if (dimensions == null || dimensions.isEmpty()) {
            return all;
        }
        List<DataRow> matched = new ArrayList<>();
        for (DataRow row : all) {
            boolean ok = true;
            for (Map.Entry<String, String> condition : dimensions.entrySet()) {
                Object actual = row.values().get(condition.getKey());
                if (actual == null || !condition.getValue().equals(String.valueOf(actual))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                matched.add(row);
            }
        }
        return matched;
    }
}
