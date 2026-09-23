package com.github.h3yow.privacyreleasegatewayjava.policy;

import java.util.Collections;
import java.util.Map;

/**
 * 不可变的策略版本定义。
 *
 * <ul>
 *   <li>version：策略版本号，一经发布不可修改；</li>
 *   <li>noiseSeed：该版本的加噪种子，保证同版本、同输入得到可复现的噪声；</li>
 *   <li>epsilon：该版本下每次发布的隐私预算单价；</li>
 *   <li>minGroupSize：最小聚合组大小（k-匿名下限），不足的分组被抑制（降级而非拒绝）；</li>
 *   <li>fields：各字段的脱敏/鉴权规则。</li>
 * </ul>
 */
public final class PolicyDefinition {

    private final int version;
    private final long noiseSeed;
    private final double epsilon;
    private final int minGroupSize;
    private final Map<String, FieldPolicy> fields;

    public PolicyDefinition(int version, long noiseSeed, double epsilon, int minGroupSize,
                            Map<String, FieldPolicy> fields) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("epsilon must be positive");
        }
        if (minGroupSize < 1) {
            throw new IllegalArgumentException("minGroupSize must be >= 1");
        }
        this.version = version;
        this.noiseSeed = noiseSeed;
        this.epsilon = epsilon;
        this.minGroupSize = minGroupSize;
        this.fields = Map.copyOf(fields);
    }

    public int getVersion() { return version; }
    public long getNoiseSeed() { return noiseSeed; }
    public double getEpsilon() { return epsilon; }
    public int getMinGroupSize() { return minGroupSize; }
    public Map<String, FieldPolicy> fields() { return fields; }

    public FieldPolicy field(String name) {
        return fields.get(name);
    }
}
