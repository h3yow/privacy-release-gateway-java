package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyDefinition;

/**
 * 策略求值结论。ok=false 时 reason 给出可区分拒绝原因与说明；
 * degraded=true 表示因 k-匿名等原因发生了降级（小组抑制）。
 */
public record PolicyEvaluation(
        boolean ok,
        com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode reason,
        String detail,
        boolean degraded,
        String basis,
        PolicyDefinition policy
) {
    public static PolicyEvaluation pass(PolicyDefinition policy, String basis, boolean degraded) {
        return new PolicyEvaluation(true, null, null, degraded, basis, policy);
    }

    public static PolicyEvaluation reject(com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode reason,
                                          String detail, PolicyDefinition policy) {
        return new PolicyEvaluation(false, reason, detail, false, null, policy);
    }
}
