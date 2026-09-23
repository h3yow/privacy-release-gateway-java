package com.github.h3yow.privacyreleasegatewayjava.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存数据集仓库（演示用确定性数据）。
 *
 * <p>数据集按名称注册后不可变；字段集合为所有记录键的并集，行记录返回防御性拷贝，
 * 避免下游（聚合/脱敏）修改污染仓库状态。</p>
 */
public class DataRepository {

    private final Map<String, Dataset> datasets = new ConcurrentHashMap<>();

    public void register(Dataset dataset) {
        datasets.put(dataset.getName(), dataset);
    }

    public boolean datasetExists(String dataset) {
        return dataset != null && datasets.containsKey(dataset);
    }

    /** 返回数据集中出现过的全部字段名（保持首次出现顺序）。 */
    public List<String> getFields(String dataset) {
        Dataset ds = datasets.get(dataset);
        if (ds == null) {
            return List.of();
        }
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        for (Map<String, Object> row : ds.getRows()) {
            for (String key : row.keySet()) {
                ordered.putIfAbsent(key, Boolean.TRUE);
            }
        }
        return new ArrayList<>(ordered.keySet());
    }

    public List<Map<String, Object>> getRows(String dataset) {
        Dataset ds = datasets.get(dataset);
        if (ds == null) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>(ds.getRows().size());
        for (Map<String, Object> row : ds.getRows()) {
            copy.add(new LinkedHashMap<>(row));
        }
        return copy;
    }
}
