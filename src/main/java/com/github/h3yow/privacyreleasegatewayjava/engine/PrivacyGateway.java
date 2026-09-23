package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.aggregate.AggregationEngine;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetAccount;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetLedger;
import com.github.h3yow.privacyreleasegatewayjava.data.DataRow;
import com.github.h3yow.privacyreleasegatewayjava.data.DatasetRepository;
import com.github.h3yow.privacyreleasegatewayjava.model.ErrorCode;
import com.github.h3yow.privacyreleasegatewayjava.model.GatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.ReleaseStatus;
import com.github.h3yow.privacyreleasegatewayjava.noise.DeterministicNoise;
import com.github.h3yow.privacyreleasegatewayjava.policy.MaskingPolicy;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyEvaluator;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 隐私发布网关编排器，是所有入口（REST / 测试直连）共用的唯一判定路径。
 * <p>
 * 关键不变量：
 * <ol>
 *   <li>同一 requestId 的结论只计算一次并快照缓存：重复请求不重复扣预算。</li>
 *   <li>任何拒绝（解析 / 越权 / 冲突 / 缺失 / 分组过小 / 预算不足 / 内部错误）都发生在预算扣减之前，
 *       被拒绝的请求绝不消耗预算。</li>
 *   <li>预算“余额检查 + 扣减”在账本账户内原子完成；聚合与噪声在同一扣减视图下生成，结论可解释。</li>
 *   <li>多问题命中时取 {@link ErrorCode#priority()} 最小者，归类完全确定且可复现。</li>
 * </ol>
 */
@Component
public class PrivacyGateway {

    private static final Logger log = LoggerFactory.getLogger(PrivacyGateway.class);
    private static final double DEFAULT_BUDGET_TOTAL = 3.0d;

    private final DatasetRepository datasetRepository;
    private final PolicyRegistry policyRegistry;
    private final PolicyEvaluator policyEvaluator;
    private final AggregationEngine aggregationEngine;
    private final BudgetLedger budgetLedger;
    private final ConcurrentMap<String, GatewayResponse> idempotencyCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();

    public PrivacyGateway(DatasetRepository datasetRepository,
                          PolicyRegistry policyRegistry,
                          PolicyEvaluator policyEvaluator,
                          AggregationEngine aggregationEngine,
                          BudgetLedger budgetLedger) {
        this.datasetRepository = datasetRepository;
        this.policyRegistry = policyRegistry;
        this.policyEvaluator = policyEvaluator;
        this.aggregationEngine = aggregationEngine;
        this.budgetLedger = budgetLedger;
    }

    public GatewayResponse submit(QueryRequest request) {
        if (request == null) {
            return reject(null, null, null, ErrorCode.MALFORMED_REQUEST, "请求体为空", null);
        }
        String requestId = request.requestId();
        // 同一 requestId 串行化，避免并发重复请求在缓存写入前同时进入扣减。
        Object lock = idempotencyLocks.computeIfAbsent(requestId, k -> new Object());
        try {
            synchronized (lock) {
                GatewayResponse cached = idempotencyCache.get(requestId);
                if (cached != null) {
                    log.info("requestId={} 判定=命中幂等缓存 预算变化=0 依据=requestId 已有结论 status={} errorCode={}",
                            requestId, cached.getStatus(), cached.getErrorCode());
                    // 返回副本并标注命中，缓存内的原始快照保持不可变。
                    GatewayResponse copy = copyOf(cached);
                    copy.setDeduplicated(true);
                    return copy;
                }
                try {
                    GatewayResponse response = doSubmit(request);
                    idempotencyCache.put(requestId, response);
                    return response;
                } catch (GatewayException e) {
                    GatewayResponse response = reject(request, null, null, e.errorCode(), e.getMessage(), null);
                    idempotencyCache.put(requestId, response);
                    return response;
                } catch (RuntimeException e) {
                    // 任何未被分层捕获的底层失败，统一归一化，绝不透出原始异常信息。
                    log.warn("requestId={} 归一化未知内部失败: {}", requestId, e.getClass().getSimpleName());
                    GatewayResponse response = reject(request, null, null,
                            ErrorCode.INTERNAL_PROCESSING_ERROR,
                            ErrorCode.INTERNAL_PROCESSING_ERROR.safeMessage(), null);
                    idempotencyCache.put(requestId, response);
                    return response;
                }
            }
        } finally {
            idempotencyLocks.remove(requestId, lock);
        }
    }

    private static GatewayResponse copyOf(GatewayResponse source) {
        GatewayResponse target = new GatewayResponse();
        target.setRequestId(source.getRequestId());
        target.setStatus(source.getStatus());
        target.setDataset(source.getDataset());
        target.setDimensionKey(source.getDimensionKey());
        target.setMetric(source.getMetric());
        target.setAggregation(source.getAggregation());
        target.setPolicyVersion(source.getPolicyVersion());
        target.setValue(source.getValue());
        target.setGroupSize(source.getGroupSize());
        target.setNoiseScale(source.getNoiseScale());
        target.setBudgetBefore(source.getBudgetBefore());
        target.setBudgetDelta(source.getBudgetDelta());
        target.setBudgetRemaining(source.getBudgetRemaining());
        target.setNotices(source.getNotices());
        target.setMaskedDimensions(source.getMaskedDimensions());
        target.setErrorCode(source.getErrorCode());
        target.setErrorMessage(source.getErrorMessage());
        target.setDeduplicated(source.isDeduplicated());
        target.setDecisionBasis(source.getDecisionBasis());
        return target;
    }

    private GatewayResponse doSubmit(QueryRequest request) {
        validate(request);

        BudgetKey budgetKey = BudgetKey.of(request.dataset(), request.dimensions());

        if (!datasetRepository.exists(request.dataset())) {
            throw new GatewayException(ErrorCode.DATASET_NOT_FOUND, "数据集不存在: " + request.dataset());
        }

        Optional<MaskingPolicy> policyOpt =
                policyRegistry.findEffective(request.dataset(), request.policyVersion());
        if (policyOpt.isEmpty()) {
            throw new GatewayException(ErrorCode.POLICY_VERSION_NOT_FOUND,
                    "策略版本不存在: " + request.policyVersion());
        }
        MaskingPolicy policy = policyOpt.get();
        if (!policy.appliesTo(request.dataset())) {
            throw new GatewayException(ErrorCode.POLICY_VERSION_NOT_FOUND,
                    "策略版本 " + policy.version() + " 不适用于该数据集");
        }
        // 注意：冲突、越权、缺失等字段级问题统一在 PolicyEvaluator 中收集，
        // 并按 ErrorCode 优先级确定性归类，避免在此处提前以 POLICY_CONFLICT 掩盖更高优先级问题。

        List<DataRow> matched =
                datasetRepository.matchingRows(request.dataset(), request.dimensions());

        PolicyEvaluator.Evaluation evaluation =
                policyEvaluator.evaluate(policy, request, matched);

        int groupSize = evaluation.usableRows().size();
        if (groupSize < policy.minGroupSize()) {
            throw new GatewayException(ErrorCode.GROUP_TOO_SMALL,
                    "分组规模 " + groupSize + " 小于最小阈值 " + policy.minGroupSize());
        }

        // 噪声纯函数确定、无副作用；即便随后预算拒绝也不会产生任何影响。
        DeterministicNoise.NoiseResult noise = DeterministicNoise.generate(
                policy.version(), request.dataset(), budgetKey.dimensionKey(),
                request.metric(), request.aggregation().name(), policy.epsilon());

        // 唯一的副作用点：原子“检查 + 扣减”。此后的步骤均为纯计算，
        // 保证扣减与结果生成处于同一状态视图。
        BudgetAccount.ConsumeResult consumed =
                budgetLedger.consume(budgetKey, policy.epsilon(), DEFAULT_BUDGET_TOTAL);
        if (consumed == null) {
            throw new GatewayException(ErrorCode.BUDGET_EXHAUSTED, "隐私预算余额不足");
        }

        double trueValue = aggregationEngine.aggregate(
                evaluation.usableRows(), request.metric(), request.aggregation());
        double released = roundTo(trueValue + noise.noise(), 6);

        ReleaseStatus status = evaluation.notices().isEmpty()
                ? ReleaseStatus.ALLOWED : ReleaseStatus.DEGRADED;

        List<String> basis = new ArrayList<>();
        basis.add("policyVersion=" + policy.version());
        basis.add("groupSize=" + groupSize + ">=" + policy.minGroupSize());
        basis.add("epsilon=" + policy.epsilon());
        basis.add("budget " + format(consumed.before()) + "-" + format(consumed.delta())
                + "=" + format(consumed.remaining()));
        basis.add("noiseScale=" + format(noise.scale()));
        if (status == ReleaseStatus.DEGRADED) {
            basis.add("降级=" + String.join(";", evaluation.notices()));
        }

        log.info("requestId={} 判定={} 预算变化 key={} {}->{}(-{}) 依据={}",
                request.requestId(), status, budgetKey.canonical(),
                format(consumed.before()), format(consumed.remaining()),
                format(consumed.delta()), String.join(" | ", basis));

        GatewayResponse response = new GatewayResponse();
        response.setRequestId(request.requestId());
        response.setStatus(status);
        response.setDataset(request.dataset());
        response.setDimensionKey(budgetKey.dimensionKey());
        response.setMetric(request.metric());
        response.setAggregation(request.aggregation());
        response.setPolicyVersion(policy.version());
        response.setValue(released);
        response.setGroupSize(groupSize);
        response.setNoiseScale(noise.scale());
        response.setBudgetBefore(consumed.before());
        response.setBudgetDelta(consumed.delta());
        response.setBudgetRemaining(consumed.remaining());
        response.setMaskedDimensions(evaluation.maskedDimensions());
        response.setNotices(evaluation.notices());
        response.setDeduplicated(false);
        response.setDecisionBasis(String.join(" | ", basis));
        return response;
    }

    private static void validate(QueryRequest request) {
        if (isBlank(request.requestId())) {
            throw new GatewayException(ErrorCode.MALFORMED_REQUEST, "requestId 必填");
        }
        if (isBlank(request.dataset())) {
            throw new GatewayException(ErrorCode.MALFORMED_REQUEST, "dataset 必填");
        }
        if (isBlank(request.metric())) {
            throw new GatewayException(ErrorCode.MALFORMED_REQUEST, "metric 必填");
        }
        if (request.aggregation() == null) {
            throw new GatewayException(ErrorCode.MALFORMED_REQUEST, "aggregation 必填");
        }
        if (isBlank(request.role())) {
            throw new GatewayException(ErrorCode.MALFORMED_REQUEST, "role 必填");
        }
        if (request.dimensions() == null) {
            throw new GatewayException(ErrorCode.MALFORMED_REQUEST, "dimensions 不能为 null");
        }
    }

    private GatewayResponse reject(QueryRequest request, String dimensionKey,
                                   String policyVersion, ErrorCode errorCode,
                                   String detail, Double remaining) {
        String requestId = request == null ? null : request.requestId();
        log.info("requestId={} 判定=REJECTED 预算变化=0 依据={} detail={}",
                requestId, errorCode.code(), detail);
        GatewayResponse response = new GatewayResponse();
        response.setRequestId(requestId);
        response.setStatus(ReleaseStatus.REJECTED);
        if (request != null) {
            response.setDataset(request.dataset());
            response.setMetric(request.metric());
            response.setAggregation(request.aggregation());
        }
        response.setDimensionKey(dimensionKey);
        response.setPolicyVersion(policyVersion);
        response.setBudgetDelta(0d);
        response.setBudgetRemaining(remaining);
        response.setErrorCode(errorCode.code());
        response.setErrorMessage(errorCode.safeMessage());
        response.setNotices(List.of(detail));
        response.setDeduplicated(false);
        response.setDecisionBasis("拒绝类型=" + errorCode.code()
                + "(优先级=" + errorCode.priority() + "): " + detail);
        return response;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String format(double v) {
        return String.format("%.4f", v);
    }

    private static double roundTo(double v, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(v * factor) / factor;
    }
}
