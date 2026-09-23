package com.github.h3yow.privacyreleasegatewayjava.tests;

import com.github.h3yow.privacyreleasegatewayjava.noise.DeterministicNoise;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 确定性噪声：同输入恒定；版本或维度改变即改变；尺度 = 1/epsilon。
 */
class DeterministicNoiseTest {

    private static final Logger log = LoggerFactory.getLogger(DeterministicNoiseTest.class);

    @Test
    void noiseIsDeterministicAndVersionScoped() {
        DeterministicNoise.NoiseResult a =
                DeterministicNoise.generate("v1", "sales", "region=us", "amount", "SUM", 1.0d);
        DeterministicNoise.NoiseResult b =
                DeterministicNoise.generate("v1", "sales", "region=us", "amount", "SUM", 1.0d);
        DeterministicNoise.NoiseResult v2 =
                DeterministicNoise.generate("v2", "sales", "region=us", "amount", "SUM", 1.0d);
        DeterministicNoise.NoiseResult otherDim =
                DeterministicNoise.generate("v1", "sales", "region=eu", "amount", "SUM", 1.0d);

        log.info("requestId=noise-unit 判定依据 a.noise={} v2.noise={} otherDim.noise={} scale={}",
                a.noise(), v2.noise(), otherDim.noise(), a.scale());

        assertEquals(a.noise(), b.noise(), "相同输入噪声必须一致");
        assertNotEquals(a.noise(), v2.noise(), "策略版本切换后噪声种子空间不同");
        assertNotEquals(a.noise(), otherDim.noise(), "维度不同噪声不同");
        assertEquals(1.0d, a.scale(), 1e-9);
        assertEquals(2.0d, DeterministicNoise.generate("v1", "s", "k", "m", "SUM", 0.5d).scale(), 1e-9);
    }
}
