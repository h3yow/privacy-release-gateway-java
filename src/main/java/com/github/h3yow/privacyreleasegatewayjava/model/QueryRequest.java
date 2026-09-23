package com.github.h3yow.privacyreleasegatewayjava.model;

import java.util.Map;

public record QueryRequest(
        String requestId,
        String dataset,
        Map<String, String> dimensions,
        String metric,
        AggregationType aggregation,
        String role,
        String policyVersion) {
}
