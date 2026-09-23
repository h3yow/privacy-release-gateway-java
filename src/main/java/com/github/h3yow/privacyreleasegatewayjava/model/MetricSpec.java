package com.github.h3yow.privacyreleasegatewayjava.model;

/**
 * 聚合指标：type 目前支持 COUNT / SUM；SUM 时 field 必填。
 */
public record MetricSpec(String type, String field) {
}
