package com.github.h3yow.privacyreleasegatewayjava.policy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略版本注册表。所有历史版本注册后均保留，策略变更不影响旧版本结论复现。
 * 每个数据集有一个当前默认版本；请求显式指定版本时按指定版本求值。
 */
@Component
public class PolicyRegistry {

    private final Map<String, MaskingPolicy> byVersion = new ConcurrentHashMap<>();
    private final Map<String, String> defaultVersionByDataset = new ConcurrentHashMap<>();

    public void register(MaskingPolicy policy, boolean makeDefault) {
        if (byVersion.putIfAbsent(policy.version(), policy) != null) {
            throw new IllegalStateException("policy version already registered: " + policy.version());
        }
        if (makeDefault) {
            for (String dataset : policy.datasets()) {
                defaultVersionByDataset.put(dataset, policy.version());
            }
        }
    }

    public void setDefaultVersion(String dataset, String version) {
        if (!byVersion.containsKey(version)) {
            throw new IllegalArgumentException("unknown policy version: " + version);
        }
        defaultVersionByDataset.put(dataset, version);
    }

    public Optional<MaskingPolicy> findVersion(String version) {
        return Optional.ofNullable(byVersion.get(version));
    }

    public Optional<MaskingPolicy> findEffective(String dataset, String requestedVersion) {
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            return findVersion(requestedVersion);
        }
        String defaultVersion = defaultVersionByDataset.get(dataset);
        return defaultVersion == null ? Optional.empty() : findVersion(defaultVersion);
    }

    public List<String> versions() {
        return new ArrayList<>(byVersion.keySet());
    }
}
