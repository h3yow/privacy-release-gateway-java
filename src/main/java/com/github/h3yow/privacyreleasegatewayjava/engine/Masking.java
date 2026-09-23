package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.policy.FieldTransform;

/**
 * 版本化脱敏/最小化变换。变换规则本身纯函数且确定性：
 * 同一策略版本（决定每个字段的 {@link FieldTransform}）对相同输入永远得到相同输出，
 * 从而保证历史结论按版本可复现。
 */
public final class Masking {

    private Masking() {
    }

    public static Object apply(FieldTransform transform, String field, Object value) {
        if (value == null) {
            return null;
        }
        return switch (transform) {
            case NONE -> value;
            case MASK -> mask(value.toString());
            case GENERALIZE -> generalize(field, value);
            case HASH -> hash(value.toString());
            case SUPPRESS -> "***";
        };
    }

    /** 保留首字符，其余以 * 替代；单字符整体掩码。 */
    static String mask(String s) {
        if (s.length() <= 1) {
            return "*";
        }
        return s.charAt(0) + "*".repeat(s.length() - 1);
    }

    /**
     * 泛化：数值（如 age）按 10 岁分桶；city/region 保留原值；
     * 其它字符串截断到前两个字符（如详细地址退化为区域前缀）。
     */
    static Object generalize(String field, Object value) {
        if (value instanceof Number n) {
            return ageBucket(n.doubleValue());
        }
        String s = value.toString();
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            return ageBucket(Double.parseDouble(s));
        }
        if ("city".equalsIgnoreCase(field) || "region".equalsIgnoreCase(field)) {
            return s;
        }
        return s.length() <= 2 ? s : s.substring(0, 2) + "**";
    }

    static String ageBucket(double age) {
        int start = (int) (Math.floor(age / 10.0) * 10);
        return "[" + start + "," + (start + 10) + ")";
    }

    /** 稳定 FNV-1a 64 位哈希（非加密用途，仅用于可复现的伪名化）。 */
    static String hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return "h" + Long.toUnsignedString(h, 16);
    }
}
