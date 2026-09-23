package com.github.h3yow.privacyreleasegatewayjava.model;

import java.util.Map;

/**
 * 单个分组的发布结果：group 为脱敏后的维度键，values 为加噪后的指标值。
 */
public record ResultRow(Map<String, Object> group, Map<String, Double> values) {
}
