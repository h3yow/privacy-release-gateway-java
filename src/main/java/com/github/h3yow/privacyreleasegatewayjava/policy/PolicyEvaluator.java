package com.github.h3yow.privacyreleasegatewayjava.policy;

import com.github.h3yow.privacyreleasegatewayjava.data.DataRow;
import com.github.h3yow.privacyreleasegatewayjava.engine.GatewayException;
import com.github.h3yow.privacyreleasegatewayjava.model.ErrorCode;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 版本化策略求值器。对一次请求涉及的字段（维度键 + 指标）执行：
 * 越权检查（默认拒绝）、规则冲突检查、字段缺失处理（严格拒绝 / 非严格降级），
 * 并按该字段规则生成脱敏后的维度投影。所有派生量（含 HASH 盐）均以策略版本为输入，保证可复现。
 */
@Component
public class PolicyEvaluator {

    /**
     * 求值结果。
     *
     * @param usableRows       指标字段存在、可参与聚合的行
     * @param maskedDimensions 脱敏后的维度值投影（随结果返回）
     * @param notices          降级说明
     */
    public record Evaluation(List<DataRow> usableRows,
                             Map<String, String> maskedDimensions,
                             List<String> notices) {
    }

    public Evaluation evaluate(MaskingPolicy policy, QueryRequest request, List<DataRow> matchedRows) {
        List<String> required = new ArrayList<>(request.dimensions().keySet());
        required.add(request.metric());

        Map<String, List<FieldRule>> rulesByField = policy.rulesByField();

        // 先遍历全部必需字段收集所有问题，再按 ErrorCode 优先级取最严重者抛出，
        // 保证“多个问题并存”时归类结果与字段顺序无关、完全确定。
        GatewayException firstIssue = null;
        for (String field : required) {
            List<FieldRule> rules = rulesByField.get(field);
            if (rules == null || rules.isEmpty()) {
                // 默认拒绝：没有任何规则授予访问，绝不静默放行。
                firstIssue = higherPriority(firstIssue,
                        new GatewayException(ErrorCode.ACCESS_DENIED, "字段未被策略授权: " + field));
                continue;
            }
            long distinctTransforms = rules.stream().map(FieldRule::transform).distinct().count();
            if (distinctTransforms > 1) {
                firstIssue = higherPriority(firstIssue,
                        new GatewayException(ErrorCode.POLICY_CONFLICT,
                                "字段存在相互冲突的规则: " + field));
                continue;
            }
            FieldRule rule = rules.get(0);
            if (!rule.allowedRoles().contains(request.role())) {
                firstIssue = higherPriority(firstIssue,
                        new GatewayException(ErrorCode.ACCESS_DENIED, "角色无权访问字段: " + field));
            }
        }
        if (firstIssue != null) {
            throw firstIssue;
        }

        List<DataRow> usableRows = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        int missingMetric = 0;
        for (DataRow row : matchedRows) {
            Object value = row.values().get(request.metric());
            if (value != null) {
                usableRows.add(row);
            } else {
                missingMetric++;
            }
        }
        if (!matchedRows.isEmpty() && missingMetric > 0) {
            if (policy.strictMissing()) {
                throw new GatewayException(ErrorCode.FIELD_MISSING,
                        "指标字段在 " + missingMetric + " 行中缺失，策略要求严格模式");
            }
            notices.add("降级: " + missingMetric + " 行缺失指标 " + request.metric()
                    + "，已从聚合中剔除（策略 " + policy.version() + " 允许降级）");
        }
        if (usableRows.isEmpty()) {
            throw new GatewayException(ErrorCode.FIELD_MISSING,
                    "分组内不存在指标字段可用的数据: " + request.metric());
        }

        Map<String, String> maskedDimensions = new HashMap<>();
        for (Map.Entry<String, String> dim : request.dimensions().entrySet()) {
            FieldRule rule = rulesByField.get(dim.getKey()).get(0);
            maskedDimensions.put(dim.getKey(),
                    applyTransform(policy.version(), rule, dim.getValue()));
        }

        return new Evaluation(List.copyOf(usableRows), Map.copyOf(maskedDimensions), List.copyOf(notices));
    }

    /** 按错误码优先级保留更严重者；同优先级保留先出现者（字段按请求顺序遍历，仍具确定性）。 */
    private static GatewayException higherPriority(GatewayException current, GatewayException candidate) {
        if (current == null) {
            return candidate;
        }
        return candidate.errorCode().priority() < current.errorCode().priority() ? candidate : current;
    }

    /** 按规则转换维度值；结果只依赖 (policyVersion, field, rawValue)，可复现。 */
    public String applyTransform(String policyVersion, FieldRule rule, String rawValue) {
        return switch (rule.transform()) {
            case NONE -> rawValue;
            case MASK -> {
                int keep = Math.min(rule.maskKeep(), rawValue.length());
                yield rawValue.substring(0, keep) + "***";
            }
            case HASH -> sha256Hex(policyVersion + "|" + rule.field() + "|" + rawValue).substring(0, 12);
            case GENERALIZE -> generalize(rawValue, rule.generalizeToSeconds());
            case RESTRICT -> "[restricted]";
        };
    }

    /** 数值型（epoch 秒）向下归整；非数值按长度桶归整。 */
    private String generalize(String rawValue, int bucketSeconds) {
        try {
            long epoch = Long.parseLong(rawValue);
            long bucket = Math.floorDiv(epoch, bucketSeconds) * bucketSeconds;
            return Long.toString(bucket);
        } catch (NumberFormatException e) {
            int len = rawValue.length();
            int bucket = Math.max(1, (len / 4) * 4);
            return "len:" + bucket + "-" + (bucket + 3);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new GatewayException(ErrorCode.INTERNAL_PROCESSING_ERROR, "脱敏摘要计算失败");
        }
    }
}
