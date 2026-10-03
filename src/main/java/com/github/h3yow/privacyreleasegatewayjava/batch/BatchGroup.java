package com.github.h3yow.privacyreleasegatewayjava.batch;

import java.util.Map;

/**
 * 批量请求中的单个分组条件。
 *
 * @param groupId    调用方给定的分组标识（可空，缺省时以下标代替），用于在响应中对号入座
 * @param dimensions 该分组的维度等值条件，语义与单发请求的 dimensions 完全一致
 */
public record BatchGroup(String groupId, Map<String, String> dimensions) {
}
