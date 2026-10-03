package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.aggregate.AggregationEngine;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetAccount;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetLedger;
import com.github.h3yow.privacyreleasegatewayjava.data.DataRow;
import com.github.h3yow.privacyreleasegatewayjava.data.DatasetRepository;
import com.github.h3yow.privacyreleasegatewayjava.model.BatchGatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.BatchQueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.BatchStatus;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 隐私发布网关编排器，是所有入口（REST / 测试直连）共用的唯一判定路径。
 * <p>
 * 关键不变量：
 * <ol>
 *   <li>同一 requestId 的确定性结论只计算一次并快照缓存：重复请求不重复扣预算。</li>
 *   <li>任何拒绝（解析 / 越权 / 冲突 / 缺失 / 分组过小 / 预算不足 / 内部错误）都发生在预算扣减之前，
 *       被拒绝的请求绝不消耗预算。</li>
 *   <li>预算“余额检查 + 扣减”在账本账户内原子完成，且是整个判定链路中唯一的副作用点
 *       （聚合与噪声均为纯计算，先于扣减完成），因此内部处理失败的净扣减恒为零。</li>
 *   <li>内部处理失败（{@link ErrorCode#INTERNAL_PROCESSING_ERROR}）不进入幂等缓存：
 *       底层恢复后，同一 requestId 原样重发可得到新的结论；
 *       确定性业务拒绝（越权 / 分组过小 / 预算不足等）仍然幂等可复现。</li>
 *   <li>批量请求逐组独立判定：单组被拒绝或失败不影响其它分组；
 *       整批预算净变化恒等于各出结果分组 budgetDelta 之和。</li>
 *   <li>多问题命中时取 {@link ErrorCode#priority()} 最小者，归类完全确定且可复现。</li>
 * </ol>
 */
@Component
public class PrivacyGateway {

    private static final Logger log = LoggerFactory.getLogger(PrivacyGateway.class);
    private static final double DEFAULT_BUDGET_TOTAL = 3.0d;
    /** 批量分组的幂等缓存键前缀，与单请求 requestId 命名空间隔离。 */
    private static final String BATCH_CACHE_KEY_PREFIX = "batch:";

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

    /**
     * 单分组发布（原始用法，行为不变）。
     */
    public GatewayResponse submit(QueryRequest request) {
        if (request == null) {
            return reject(null, null, null, ErrorCode.MALFORMED_REQUEST, "请求体为空", null);
        }
        return processOnce(request.requestId(), request);
    }

    /**
     * 批量发布：一次请求携带多个分组条件，逐组独立判定。
     * 单组被拒绝 / 失败不影响其它分组；出结果的分组才扣预算，失败分组净扣减为零。
     * 同一批量 requestId 重发时：确定性结论（含成功与业务拒绝）命中缓存不重复扣预算；
     * 曾因内部错误失败的分组会重新计算，底层恢复后即可拿到新结论。
     */
    public BatchGatewayResponse submitBatch(BatchQueryRequest request) {
        if (request == null || isBlank(request.requestId())
                || request.groups() == null || request.groups().isEmpty()) {
            return rejectBatch(request == null ? null : request.requestId(),
                    "批量请求必填 requestId 与至少一个分组条件");
        }
        String requestId = request.requestId();
        List<Map<String, String>> groups = request.groups();
        List<GatewayResponse> results = new ArrayList<>(groups.size());

        for (int i = 0; i < groups.size(); i++) {
            QueryRequest groupRequest = new QueryRequest(
                    requestId, request.dataset(), groups.get(i),
                    request.metric(), request.aggregation(),
                    request.role(), request.policyVersion());
            // 逐组幂等键：批量内第 i 个分组的确定性结论只计算一次。
            String cacheKey = BATCH_CACHE_KEY_PREFIX + requestId + "#" + i;
            results.add(processOnce(cacheKey, groupRequest));
        }

        int succeeded = 0;
        double totalDelta = 0d;
        for (GatewayResponse r : results) {
            if (r.getStatus() == ReleaseStatus.ALLOWED || r.getStatus() == ReleaseStatus.DEGRADED) {
                succeeded++;
                totalDelta += r.getBudgetDelta() == null ? 0d : r.getBudgetDelta();
            }
        }
        int failed = results.size() - succeeded;
        BatchStatus batchStatus = failed == 0 ? BatchStatus.ALL_SUCCEEDED
                : succeeded == 0 ? BatchStatus.ALL_FAILED
                : BatchStatus.PARTIAL_SUCCESS;

        BatchGatewayResponse batch = new BatchGatewayResponse();
        batch.setRequestId(requestId);
        batch.setBatchStatus(batchStatus);
        batch.setTotalGroups(results.size());
        batch.setSucceeded(succeeded);
        batch.setFailed(failed);
        batch.setTotalBudgetDelta(roundTo(totalDelta, 6));
        batch.setResults(results);

        log.info("requestId={} 批量汇总 判定={} 分组数={} 成功={} 失败={} 整批预算净变化=-{}",
                requestId, batchStatus, results.size(), succeeded, failed, format(totalDelta));
        return batch;
    }

    /**
     * 幂等处理骨架：同一缓存键串行化；命中缓存直接返回副本；
     * 只有确定性结论才写入缓存（内部处理失败不缓存，允许底层恢复后重试）。
     */
    private GatewayResponse processOnce(String cacheKey, QueryRequest request) {
        Object lock = idempotencyLocks.computeIfAbsent(cacheKey, k -> new Object());
        try {
            synchronized (lock) {
                GatewayResponse cached = idempotencyCache.get(cacheKey);
                if (cached != null) {
                    log.info("requestId={} 判定=命中幂等缓存 预算变化=0 依据=缓存键 {} 已有结论 status={} errorCode={}",
                            request.requestId(), cacheKey, cached.getStatus(), cached.getErrorCode());
                    // 返回副本并标注命中，缓存内的原始快照保持不可变。
                    GatewayResponse copy = copyOf(cached);
                    copy.setDeduplicated(true);
                    return copy;
                }
                GatewayResponse response = processSafely(request);
                if (isCacheable(response)) {
                    idempotencyCache.put(cacheKey, response);
                } else {
                    log.info("requestId={} 判定=内部处理失败 不写入幂等缓存，同一请求标识可在恢复后重试",
                            request.requestId());
                }
                return response;
            }
        } finally {
            idempotencyLocks.remove(cacheKey, lock);
        }
    }

    /** 内部处理失败属于非确定性结论，不得缓存；其余结论（成功 / 确定性拒绝）均可缓存。 */
    private static boolean isCacheable(GatewayResponse response) {
        return !(response.getStatus() == ReleaseStatus.REJECTED
                && ErrorCode.INTERNAL_PROCESSING_ERROR.code().equals(response.getErrorCode()));
    }

    /**
     * 把任何逃出分层捕获的异常归一化为稳定错误类型，绝不透出内部细节；
     * 归一化发生在预算扣减之前（扣减是 doSubmit 中最后的副作用），失败净扣减为零。
     */
    private GatewayResponse processSafely(QueryRequest request) {
        try {
            return doSubmit(request);
        } catch (GatewayException e) {
            return reject(request, null, null, e.errorCode(), e.getMessage(), null);
        } catch (RuntimeException e) {
            log.warn("requestId={} 归一化未知内部失败: {}", request.requestId(), e.getClass().getSimpleName());
            return reject(request, null, null,
                    ErrorCode.INTERNAL_PROCESSING_ERROR,
                    ErrorCode.INTERNAL_PROCESSING_ERROR.safeMessage(), null);
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

        // 噪声与聚合均为纯函数、无副作用，先于扣减完成：
        // 这些步骤若因底层问题失败，预算尚未变动，净扣减恒为零。
        DeterministicNoise.NoiseResult noise = DeterministicNoise.generate(
                policy.version(), request.dataset(), budgetKey.dimensionKey(),
                request.metric(), request.aggregation().name(), policy.epsilon());

        double trueValue = aggregationEngine.aggregate(
                evaluation.usableRows(), request.metric(), request.aggregation());
        double released = roundTo(trueValue + noise.noise(), 6);

        // 唯一的副作用点：原子“检查 + 扣减”。此后再无任何可能失败的步骤，
        // 保证扣减与结果生成处于同一状态视图，且失败请求绝不消耗预算。
        BudgetAccount.ConsumeResult consumed =
                budgetLedger.consume(budgetKey, policy.epsilon(), DEFAULT_BUDGET_TOTAL);
        if (consumed == null) {
            throw new GatewayException(ErrorCode.BUDGET_EXHAUSTED, "隐私预算余额不足");
        }

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

    /** 批量信封级拒绝：无法逐组处理时整体返回，不消耗任何预算。 */
    private BatchGatewayResponse rejectBatch(String requestId, String detail) {
        log.info("requestId={} 判定=BATCH_REJECTED 预算变化=0 依据={} detail={}",
                requestId, ErrorCode.MALFORMED_REQUEST.code(), detail);
        BatchGatewayResponse batch = new BatchGatewayResponse();
        batch.setRequestId(requestId);
        batch.setBatchStatus(BatchStatus.ALL_FAILED);
        batch.setTotalGroups(0);
        batch.setSucceeded(0);
        batch.setFailed(0);
        batch.setTotalBudgetDelta(0d);
        batch.setResults(List.of());
        batch.setErrorCode(ErrorCode.MALFORMED_REQUEST.code());
        batch.setErrorMessage(ErrorCode.MALFORMED_REQUEST.safeMessage() + ": " + detail);
        return batch;
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
