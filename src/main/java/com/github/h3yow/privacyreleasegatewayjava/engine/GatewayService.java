package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetStore;
import com.github.h3yow.privacyreleasegatewayjava.data.DataRepository;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode;
import com.github.h3yow.privacyreleasegatewayjava.model.ResultRow;
import com.github.h3yow.privacyreleasegatewayjava.noise.DeterministicNoise;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldPolicy;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyDefinition;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关编排，保证“扣减与结果生成处于同一可解释状态视图”：
 *
 * <ol>
 *   <li>幂等：同一 idempotencyKey 的并发/重复请求只计算一次、只扣一次预算，
 *       后续请求返回同一结论（包括拒绝结论）；</li>
 *   <li>先做不消耗预算的策略判定，通过后才原子扣减预算；</li>
 *   <li>扣减成功后结果生成若发生内部故障，立即补偿退还并归一化为 INTERNAL_ERROR，
 *       任何被拒绝的请求最终都不消耗预算；</li>
 *   <li>所有内部异常一律映射为稳定错误码，原始异常仅记录在服务端日志、不外泄。</li>
 * </ol>
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final PolicyRegistry registry;
    private final DataRepository repository;
    private final BudgetStore budgetStore;
    private final DeterministicNoise noise;
    private final PolicyEvaluator evaluator;

    /** 幂等键 -> 已确定结论。computeIfAbsent 保证同键并发下只执行一次。 */
    private final ConcurrentHashMap<String, QueryResponse> idempotencyCache = new ConcurrentHashMap<>();

    public GatewayService(PolicyRegistry registry, DataRepository repository,
                          BudgetStore budgetStore, DeterministicNoise noise) {
        this.registry = registry;
        this.repository = repository;
        this.budgetStore = budgetStore;
        this.noise = noise;
        this.evaluator = new PolicyEvaluator(registry, repository);
    }

    public QueryResponse release(QueryRequest request) {
        QueryRequest normalized = normalize(request);
        String rid = normalized.requestId();
        log.info("[{}] 收到发布请求 dataset={}, dimensions={}, metrics={}, role={}, strategyVersion={}, idempotencyKey={}",
                rid, normalized.dataset(), normalized.dimensions(),
                normalized.metrics().stream().map(Aggregator::metricName).toList(),
                normalized.role(), normalized.strategyVersion(), normalized.idempotencyKey());
        try {
            String key = normalized.idempotencyKey();
            if (key == null || key.isBlank()) {
                return doRelease(normalized);
            }
            QueryResponse cached = idempotencyCache.computeIfAbsent(key, k -> doRelease(normalized));
            if (cached.requestId() != null && !cached.requestId().equals(rid)) {
                log.info("[{}] 重复请求命中幂等结论 originalRequestId={} allowed={} code/result reused",
                        rid, cached.requestId(), cached.allowed());
            }
            // 对外保留本次请求标识，其余结论（含预算快照）与首次完全一致，保证可复现
            return withRequestId(cached, rid);
        } catch (GatewayException ge) {
            log.warn("[{}] 受控拒绝 code={} detail={}", rid, ge.getCode(), ge.getMessage());
            return denied(normalized, ge.getCode(), ge.getMessage(), null, 0d, 0d, 0d);
        } catch (RuntimeException ex) {
            log.error("[{}] 内部异常，归一化为 INTERNAL_ERROR: {}", rid, ex.toString(), ex);
            return denied(normalized, RejectionCode.INTERNAL_ERROR,
                    "网关内部处理失败，请稍后重试", null, 0d, 0d, 0d);
        }
    }

    private QueryRequest normalize(QueryRequest request) {
        if (request == null) {
            return new QueryRequest(null, null, null, List.of(), List.of(), List.of(), null, null);
        }
        String rid = (request.requestId() == null || request.requestId().isBlank())
                ? "req-" + UUID.randomUUID() : request.requestId();
        return new QueryRequest(rid, request.idempotencyKey(), request.dataset(),
                request.dimensions(), request.metrics(), request.fields(),
                request.role() == null ? "anonymous" : request.role(),
                request.strategyVersion());
    }

    private QueryResponse doRelease(QueryRequest request) {
        String rid = request.requestId();

        // 1) 策略判定（不消耗预算）
        PolicyEvaluation evaluation;
        try {
            evaluation = evaluator.evaluate(request);
        } catch (RuntimeException ex) {
            log.error("[{}] 策略求值内部异常: {}", rid, ex.toString(), ex);
            return denied(request, RejectionCode.INTERNAL_ERROR,
                    "策略求值失败", null, 0d, 0d, 0d);
        }
        if (!evaluation.ok()) {
            log.info("[{}] 策略拒绝 code={} detail={}", rid, evaluation.reason(), evaluation.detail());
            return denied(request, evaluation.reason(), evaluation.detail(),
                    evaluation.policy(), 0d, 0d, 0d);
        }
        PolicyDefinition policy = evaluation.policy();
        double cost = policy.getEpsilon();

        // 2) 预算原子扣减
        BudgetKey budgetKey = new BudgetKey(request.dataset(), request.dimensions());
        BudgetStore.DeductResult deduct;
        try {
            deduct = budgetStore.deduct(budgetKey, cost, rid, request.idempotencyKey());
        } catch (RuntimeException ex) {
            log.error("[{}] 预算扣减内部异常: {}", rid, ex.toString(), ex);
            return denied(request, RejectionCode.INTERNAL_ERROR,
                    "预算处理失败", policy, budgetStore.remaining(budgetKey), 0d, 0d);
        }
        log.info("[{}] 预算判定 key={} before={} cost={} after={} success={} reason={}",
                rid, budgetKey, fmt(deduct.before()), fmt(cost), fmt(deduct.after()),
                deduct.success(), deduct.reason());
        if (!deduct.success()) {
            return denied(request, deduct.reason(), deduct.detail(), policy,
                    deduct.before(), 0d, deduct.before());
        }

        // 3) 结果生成：聚合 -> 小组抑制(降级) -> 维度脱敏 -> 确定性加噪。
        //    任何内部故障都补偿退还预算，确保拒绝请求不消耗预算。
        try {
            List<ResultRow> rows = new ArrayList<>();
            Aggregator aggregator = new Aggregator(repository);
            // 脱敏发生在分组之前：k-匿名与加噪都作用于最终发布口径（如年龄先分桶再计数）
            Aggregator.DimensionTransformer transformer = (dim, raw) -> {
                FieldPolicy fp = policy.field(dim);
                return Masking.apply(fp.transform(), dim, raw);
            };
            Aggregator.Aggregation aggregation = aggregator.aggregate(
                    request.dataset(), request.dimensions(), request.metrics(),
                    policy.getMinGroupSize(), transformer);

            for (Aggregator.Group group : aggregation.groups()) {
                // group.rawKey() 已是发布口径（脱敏后），直接返回
                Map<String, Object> groupKey = new LinkedHashMap<>(group.rawKey());
                Map<String, Double> released = new LinkedHashMap<>();
                for (Map.Entry<String, Double> e : group.trueValues().entrySet()) {
                    String metricName = e.getKey();
                    String contextKey = buildContextKey(request, policy, group.rawKey(), metricName);
                    double rv = noise.addNoise(policy.getNoiseSeed(), contextKey,
                            e.getValue(), 1.0d, policy.getEpsilon());
                    double rounded = Math.round(rv * 1000d) / 1000d;
                    released.put(metricName, rounded);
                }
                rows.add(new ResultRow(groupKey, released));
            }

            boolean degraded = aggregation.suppressedGroups() > 0;
            String basis = evaluation.basis()
                    + " | 预算扣减 key=" + budgetKey + " " + fmt(deduct.before()) + "->" + fmt(deduct.after())
                    + " | 输出组=" + rows.size()
                    + " | 抑制小组(降级)=" + aggregation.suppressedGroups()
                    + " | 噪声=双端几何(seed=" + policy.getNoiseSeed() + ")";
            log.info("[{}] 判定依据: {}", rid, basis);
            return new QueryResponse(rid, request.idempotencyKey(), true, degraded,
                    policy.getVersion(), null, null,
                    deduct.before(), cost, deduct.after(), basis,
                    List.copyOf(rows));
        } catch (RuntimeException ex) {
            log.error("[{}] 结果生成内部异常，触发预算补偿退还: {}", rid, ex.toString(), ex);
            try {
                budgetStore.refund(budgetKey, cost, rid, request.idempotencyKey());
                double remaining = budgetStore.remaining(budgetKey);
                log.info("[{}] 预算已补偿退还 key={} remaining={}", rid, budgetKey, fmt(remaining));
            } catch (RuntimeException refundEx) {
                log.error("[{}] 预算补偿退还失败: {}", rid, refundEx.toString(), refundEx);
            }
            return denied(request, RejectionCode.INTERNAL_ERROR,
                    "结果生成失败", policy, deduct.before(), 0d, budgetStore.remaining(budgetKey));
        }
    }

    /**
     * 确定性噪声上下文键：只包含与请求语义和策略版本相关的内容，
     * 不含 requestId/时间，因此同一逻辑请求（含重复请求）的噪声完全一致。
     */
    private String buildContextKey(QueryRequest request, PolicyDefinition policy,
                                   Map<String, Object> rawGroupKey, String metricName) {
        StringBuilder sb = new StringBuilder();
        sb.append("v").append(policy.getVersion())
                .append("|ds=").append(request.dataset())
                .append("|dims=");
        request.dimensions().forEach(d -> sb.append(d).append('=').append(rawGroupKey.get(d)).append(';'));
        sb.append("|metric=").append(metricName);
        return sb.toString();
    }

    private QueryResponse denied(QueryRequest request, RejectionCode code, String detail,
                                 PolicyDefinition policy, double before, double cost, double remaining) {
        int version = policy != null ? policy.getVersion()
                : (request.strategyVersion() != null ? request.strategyVersion() : -1);
        String basis = "拒绝 code=" + code + " reason=" + detail
                + " | 预算未扣减 before=" + fmt(before) + " remaining=" + fmt(remaining);
        return new QueryResponse(request.requestId(), request.idempotencyKey(), false, false,
                version, code, detail, before, cost, remaining, basis, List.of());
    }

    private QueryResponse withRequestId(QueryResponse r, String requestId) {
        return new QueryResponse(requestId, r.idempotencyKey(), r.allowed(), r.degraded(),
                r.strategyVersion(), r.rejectionCode(), r.rejectionDetail(),
                r.budgetBefore(), r.budgetCost(), r.budgetRemaining(),
                r.decisionBasis(), r.rows());
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
