package com.github.h3yow.privacyreleasegatewayjava.data;

import java.util.Map;

public record DataRow(String rowId, Map<String, Object> values) {
}
