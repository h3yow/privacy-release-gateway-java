package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.data.DataRepository;
import com.github.h3yow.privacyreleasegatewayjava.model.MetricSpec;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldPolicy;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyDefinition;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 策略求值器：完成所有<b>不消耗预算</b>的前置判定。
 *
 * <p>多个问题并存时按 {@link RejectionCode} 的确定性优先级归类：
 * INVALID_REQUEST &gt; UNAUTHORIZED &gt; FIELD_MISSING &gt; POLICY_CONFLICT。
 * 求值器绝不抛出业务异常（除了需要被归一化为 INTERNAL_ERROR 的真正内部故障），
 * 所有拒绝都以 {@link PolicyEvaluation#reject} 表达。</p>
 */
public class PolicyEvaluator {

    private static final Set<String> METRIC_TYPES = Set.of("COUNT", "SUM");

    private final PolicyRegistry registry;
    private final DataRepository repository;

    public PolicyEvaluator(PolicyRegistry registry, DataRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    public PolicyEvaluation evaluate(QueryRequest request) {
        // 1) 基础合法性
        PolicyEvaluation invalid = validateShape(request);
        if (invalid != null) {
            return invalid;
        }

        // 2) 数据集存在性（无效请求优先于其它判断）
        if (!repository.datasetExists(request.dataset())) {
            return reject(RejectionCode.INVALID_REQUEST,
                    "数据集不存在: " + request.dataset(), null);
        }

        // 3) 策略版本解析：显式版本必须存在；为空则取当前版本
        PolicyDefinition policy = request.strategyVersion() == null
                ? registry.current()
                : registry.get(request.strategyVersion());
        if (policy == null) {
            int v = request.strategyVersion() == null ? -1 : request.strategyVersion();
            return reject(RejectionCode.POLICY_CONFLICT,
                    "策略版本不存在: " + v, null);
        }

        List<String> datasetFields = repository.getFields(request.dataset());
        String role = request.role() == null ? "" : request.role();

        // 4) 字段检查：先缺失（数据集中不存在/策略未覆盖），再越权/冲突。
        //    fields 与 dimensions 是两类用途，同一字段同时出现是合法的，仅在各自列表内查重。
        Set<String> seenFields = new HashSet<>();
        Set<String> seenDims = new HashSet<>();
        for (String field : request.fields()) {
            PolicyEvaluation r = checkField(field, role, policy, datasetFields, seenFields, false);
            if (r != null) {
                return r;
            }
        }
        for (String dim : request.dimensions()) {
            PolicyEvaluation r = checkField(dim, role, policy, datasetFields, seenDims, true);
            if (r != null) {
                return r;
            }
        }
        for (MetricSpec metric : request.metrics()) {
            if (metric.type() == null || !METRIC_TYPES.contains(metric.type())) {
                return reject(RejectionCode.INVALID_REQUEST,
                        "不支持的聚合类型: " + metric.type(), policy);
            }
            if ("SUM".equals(metric.type())) {
                if (metric.field() == null || metric.field().isBlank()) {
                    return reject(RejectionCode.INVALID_REQUEST,
                            "SUM 聚合必须指定 field", policy);
                }
                PolicyEvaluation r = checkMetricField(metric.field(), role, policy, datasetFields);
                if (r != null) {
                    return r;
                }
            }
        }

        // 5) 策略一致性：策略字段集合必须覆盖数据集字段集合，否则判定为策略冲突，不静默放行
        for (String df : datasetFields) {
            if (policy.field(df) == null) {
                return reject(RejectionCode.POLICY_CONFLICT,
                        "策略 v" + policy.getVersion() + " 未覆盖数据集字段: " + df, policy);
            }
        }

        String basis = "策略求值通过: version=v" + policy.getVersion()
                + ", epsilon=" + policy.getEpsilon()
                + ", minGroupSize=" + policy.getMinGroupSize()
                + ", role=" + role
                + ", fields=" + request.fields()
                + ", dimensions=" + request.dimensions();
        return PolicyEvaluation.pass(policy, basis, false);
    }

    private PolicyEvaluation validateShape(QueryRequest request) {
        if (request == null) {
            return reject(RejectionCode.INVALID_REQUEST, "请求体为空", null);
        }
        if (request.dataset() == null || request.dataset().isBlank()) {
            return reject(RejectionCode.INVALID_REQUEST, "dataset 不能为空", null);
        }
        if (request.metrics() == null || request.metrics().isEmpty()) {
            return reject(RejectionCode.INVALID_REQUEST, "metrics 不能为空", null);
        }
        if (request.dimensions() == null || request.fields() == null) {
            return reject(RejectionCode.INVALID_REQUEST, "dimensions/fields 不能为 null", null);
        }
        return null;
    }

    /**
     * 检查被请求字段。asDimension=true 时额外要求允许作为分组维度。
     */
    private PolicyEvaluation checkField(String field, String role, PolicyDefinition policy,
                                        List<String> datasetFields, Set<String> seen,
                                        boolean asDimension) {
        if (field == null || field.isBlank()) {
            return reject(RejectionCode.INVALID_REQUEST, "字段名不能为空", policy);
        }
        if (!seen.add(field)) {
            return reject(RejectionCode.POLICY_CONFLICT,
                    "同一字段在请求中重复出现，策略口径冲突: " + field, policy);
        }
        if (!datasetFields.contains(field)) {
            return reject(RejectionCode.FIELD_MISSING,
                    "数据集中不存在字段: " + field, policy);
        }
        FieldPolicy fp = policy.field(field);
        if (fp == null) {
            return reject(RejectionCode.FIELD_MISSING,
                    "策略 v" + policy.getVersion() + " 未定义字段规则: " + field, policy);
        }
        if (fp.allowedRoles() == null || !fp.allowedRoles().contains(role)) {
            return reject(RejectionCode.UNAUTHORIZED,
                    "角色 '" + role + "' 无权访问字段: " + field + " (策略 v"
                            + policy.getVersion() + ")", policy);
        }
        if (fp.transform() == com.github.h3yow.privacyreleasegatewayjava.policy.FieldTransform.SUPPRESS
                && !asDimension) {
            // 被抑制字段只能用于分组，不得作为明细字段请求
            return reject(RejectionCode.POLICY_CONFLICT,
                    "字段已被策略抑制，禁止发布: " + field, policy);
        }
        if (asDimension && !fp.dimensionAllowed()) {
            return reject(RejectionCode.POLICY_CONFLICT,
                    "字段不允许作为分组维度: " + field, policy);
        }
        return null;
    }

    private PolicyEvaluation checkMetricField(String field, String role, PolicyDefinition policy,
                                              List<String> datasetFields) {
        if (!datasetFields.contains(field)) {
            return reject(RejectionCode.FIELD_MISSING,
                    "SUM 字段在数据集中不存在: " + field, policy);
        }
        FieldPolicy fp = policy.field(field);
        if (fp == null) {
            return reject(RejectionCode.FIELD_MISSING,
                    "策略 v" + policy.getVersion() + " 未定义字段规则: " + field, policy);
        }
        if (fp.allowedRoles() == null || !fp.allowedRoles().contains(role)) {
            return reject(RejectionCode.UNAUTHORIZED,
                    "角色 '" + role + "' 无权对字段做聚合: " + field, policy);
        }
        if (!fp.metricAllowed()) {
            return reject(RejectionCode.POLICY_CONFLICT,
                    "字段不允许作为聚合度量: " + field, policy);
        }
        return null;
    }

    private static PolicyEvaluation reject(RejectionCode code, String detail, PolicyDefinition policy) {
        return PolicyEvaluation.reject(code, detail, policy);
    }
}
