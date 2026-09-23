package com.github.h3yow.privacyreleasegatewayjava.budget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 预算账户键：数据集 + 请求维度组合。
 *
 * <p>维度在构造时排序并去 null，因此 ["city","age"] 与 ["age","city"] 指向同一账户，
 * 保证不同入口对同一数据视图的预算口径一致。</p>
 */
public final class BudgetKey {

    private final String dataset;
    private final List<String> dimensions;

    public BudgetKey(String dataset, List<String> dimensions) {
        this.dataset = Objects.requireNonNull(dataset, "dataset");
        List<String> sorted = new ArrayList<>(dimensions == null ? List.of() : dimensions);
        sorted.removeIf(Objects::isNull);
        Collections.sort(sorted);
        this.dimensions = Collections.unmodifiableList(sorted);
    }

    public String dataset() { return dataset; }
    public List<String> dimensions() { return dimensions; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BudgetKey other)) {
            return false;
        }
        return dataset.equals(other.dataset) && dimensions.equals(other.dimensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataset, dimensions);
    }

    @Override
    public String toString() {
        return dataset + ":" + String.join(",", dimensions);
    }
}
