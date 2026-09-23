package com.github.h3yow.privacyreleasegatewayjava.noise;

import java.util.Random;

/**
 * 确定性加噪器。
 *
 * <p>使用双端几何分布（离散拉普拉斯）噪声，对计数/求和等灵敏度为
 * {@code sensitivity} 的整数量化结果满足 (epsilon)-差分隐私。噪声流由
 * {@code (策略噪声种子, 上下文键)} 经字符串混合哈希确定，因此：</p>
 * <ul>
 *   <li>同一策略版本 + 相同上下文（数据集/维度/指标/分组键/请求语义）+ 相同真实值
 *       =&gt; 永远得到相同发布值，结论可复现；</li>
 *   <li>策略升级（新 noiseSeed）后噪声分布重新确定，历史版本用旧种子仍可复现；</li>
 *   <li>加噪后结果非负截断。</li>
 * </ul>
 */
public class DeterministicNoise {

    public DeterministicNoise() {
    }

    /**
     * 为真实值加噪。
     *
     * @param seed        策略版本噪声种子
     * @param contextKey  确定性上下文键（同一逻辑请求每次计算都相同）
     * @param trueValue   真实聚合值
     * @param sensitivity 聚合灵敏度（COUNT 为 1）
     * @param epsilon      隐私预算参数
     */
    public double addNoise(long seed, String contextKey, double trueValue,
                           double sensitivity, double epsilon) {
        long mixed = mix(seed, contextKey);
        Random rng = new Random(mixed);
        double alpha = Math.exp(-epsilon / Math.max(sensitivity, 1e-9));
        int noise = twoSidedGeometric(rng, alpha);
        double released = trueValue + noise;
        if (released < 0) {
            released = 0;
        }
        return released;
    }

    /**
     * 双端几何采样：G = G1 - G2，G1/G2 为成功概率 1-alpha 的几何分布（从 0 起）。
     */
    private static int twoSidedGeometric(Random rng, double alpha) {
        return geometric(rng, alpha) - geometric(rng, alpha);
    }

    private static int geometric(Random rng, double alpha) {
        double u = rng.nextDouble();
        // P(X=k) = (1-alpha) * alpha^k；对 u in (0,1] 的逆变换采样
        double v = 1.0 - u * (1.0 - 1e-12);
        int k = (int) Math.floor(Math.log(v) / Math.log(alpha));
        return Math.max(k, 0);
    }

    /** 将 seed 与上下文字符串混合为稳定的 64 位种子（Murmur-like finalizer + 串哈希）。 */
    static long mix(long seed, String contextKey) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < contextKey.length(); i++) {
            h ^= contextKey.charAt(i);
            h *= 0x100000001B3L;
        }
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
