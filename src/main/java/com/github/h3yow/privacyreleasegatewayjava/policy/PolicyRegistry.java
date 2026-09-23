package com.github.h3yow.privacyreleasegatewayjava.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 策略版本注册表。
 *
 * <p>策略一经注册即不可变、不可覆盖；所有历史版本永久保留，因此历史结论始终能按
 * 其版本号取回定义并复现。{@link #current()} 返回版本号最大的已注册策略。</p>
 */
public class PolicyRegistry {

    private final TreeMap<Integer, PolicyDefinition> policies = new TreeMap<>();

    public synchronized void register(PolicyDefinition policy) {
        if (policies.containsKey(policy.getVersion())) {
            throw new IllegalStateException("policy version already registered: " + policy.getVersion());
        }
        policies.put(policy.getVersion(), policy);
    }

    /** 按版本号精确取回；不存在返回 null（由求值层归一化为 POLICY_CONFLICT）。 */
    public synchronized PolicyDefinition get(int version) {
        return policies.get(version);
    }

    public synchronized PolicyDefinition current() {
        Map.Entry<Integer, PolicyDefinition> e = policies.lastEntry();
        return e == null ? null : e.getValue();
    }

    public synchronized List<Integer> versions() {
        return new ArrayList<>(policies.navigableKeySet());
    }
}
