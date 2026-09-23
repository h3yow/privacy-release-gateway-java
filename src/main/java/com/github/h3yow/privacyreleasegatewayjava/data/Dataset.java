package com.github.h3yow.privacyreleasegatewayjava.data;

import java.util.List;
import java.util.Map;

/**
 * 一个数据集：名称 + 行记录（每行为 字段名->值）。
 */
public class Dataset {
    private final String name;
    private final List<Map<String, Object>> rows;

    public Dataset(String name, List<Map<String, Object>> rows) {
        this.name = name;
        this.rows = rows;
    }

    public String getName() { return name; }
    public List<Map<String, Object>> getRows() { return rows; }
}
