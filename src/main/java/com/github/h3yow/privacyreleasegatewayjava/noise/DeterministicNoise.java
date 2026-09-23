package com.github.h3yow.privacyreleasegatewayjava.noise;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 确定性加噪：噪声值是 (policyVersion, dataset, dimensionKey, metric, aggregation) 的纯函数，
 * 不依赖任何运行时随机源，因此同一策略版本对相同输入必然得到可复现结论；
 * 版本升级后种子空间改变，噪声也随之改变（新旧版本各自可复现）。
 *
 * 采用 SHA-256 派生两个均匀分量 + Box-Muller 得到标准高斯，再按尺度 scale 缩放，
 * 模拟 Laplace/Gaussian 机制中的标定噪声（尺度 = 1/epsilon）。
 */
public final class DeterministicNoise {

    private DeterministicNoise() {
    }

    public record NoiseResult(double noise, double scale) {
    }

    public static NoiseResult generate(String policyVersion, String dataset, String dimensionKey,
                                       String metric, String aggregation, double epsilon) {
        String base = String.join("|", policyVersion, dataset, dimensionKey,
                metric, aggregation, "v1");
        double[] u = twoUniforms(base);
        double standardGaussian = gaussian(u[0], u[1]);
        double scale = 1.0d / epsilon;
        double noise = standardGaussian * scale;
        return new NoiseResult(noise, scale);
    }

    /** 将 [0,1) 均匀分量经 Box-Muller 转换为标准高斯。 */
    static double gaussian(double u1, double u2) {
        double safe1 = Math.nextUp(u1);
        double safe2 = Math.nextUp(u2);
        double r = Math.sqrt(-2.0d * Math.log(safe1));
        return r * Math.cos(2.0d * Math.PI * safe2);
    }

    /** 由种子的 SHA-256 摘要派生出两个相互独立的 [0,1) 双精度数。 */
    static double[] twoUniforms(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            return new double[]{uniformFromBytes(digest, 0), uniformFromBytes(digest, 8)};
        } catch (Exception e) {
            throw new IllegalStateException("digest unavailable", e);
        }
    }

    private static double uniformFromBytes(byte[] bytes, int offset) {
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits = (bits << 8) | (bytes[offset + i] & 0xffL);
        }
        // 取 53 位尾数 => [0,1)
        return (bits >>> 11) * 0x1.0p-53;
    }
}
