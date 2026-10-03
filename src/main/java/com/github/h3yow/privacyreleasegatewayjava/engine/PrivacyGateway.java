package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.aggregate.AggregationEngine;
import com.github.h3yow.privacyreleasegatewayjava.batch.BatchGatewayResponse;
import com.github.h3yow.privacyreleasegatewayjava.batch.BatchGroup;
import com.github.h3yow.privacyreleasegatewayjava.batch.BatchQueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.batch.BudgetUsageSummary;
import com.github.h3yow.privacyreleasegatewayjava.batch.GroupOutcome;
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
 *   <li>同一 requestId 的确定性结论只计算一次并快照缓存：重复请求不重复扣预算；
 *       瞬时内部失败（{@link ErrorCode#INTERNAL_PROCESSING_ERROR}）不入缓存，
 *       底层恢复后同一 requestId 重发可得到新结论。</li>
 *   <li>任何拒绝（解析 / 越权 / 冲突 / 缺失 / 分组过小 / 预算不足 / 内部错误）都发生在预算扣减之前，
 *       被拒绝或失败的请求绝不消耗预算。</li>
 *   <li>预算“余额检查 + 扣减”在账本账户内原子完成；聚合与噪声在扣减前以纯函数完成，
 *       扣减后不再有可能失败的步骤，结论可解释。</li>
 *   <li>多问题命中时取 {@link ErrorCode#priority()} 最小者，归类完全确定且可复现。</li>
 *   <li>批量发布中每个分组独立走同一判定路径：部分失败不连累其它分组，
 *       幂等粒度为 requestId + 分组标识。</li>
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
    private final ConcurrentMap<String, GroupOutcome> batchGroupCache = new ConcurrentHashMap<>();
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
     * 批量发布入口：一次处理多个分组条件。
     * <p>
     * 语义：
     * <ol>
     *   <li>每个分组独立走与单发完全相同的判定路径（{@link #doSubmit}），互不影响：
     *       某个分组被拒绝或内部失败，其它分组照常出结论。</li>
     *   <li>幂等粒度 = requestId + 分组标识。只有确定性结论（成功 / 业务拒绝）入缓存；
     *       内部失败的分组不入缓存，底层恢复后同一 requestId 重发可拿到新结论，
     *       而已成功的分组重发时命中缓存、绝不重复扣预算。</li>
     *   <li>预算对账：响应中每个出了结果的分组携带扣减快照，budgetUsage 按预算键汇总，
     *       其 totalDelta 之和等于本批对账本的实际影响；未出结果的分组净扣减恒为 0。</li>
     * </ol>
     */
    public BatchGatewayResponse submitBatch(BatchQueryRequest request) {
        if (request == null) {
            return batchError(null, "请求体为空");
        }
        // 批量级字段缺失 => 整批 malformed，不触碰任何分组与预算。
        String validationError = validateBatch(request);
        if (validationError != null) {
            return batchError(request, validationError);
        }
        String requestId = request.requestId();
        // 同一 requestId 串行化，与单发路径共用锁表。
        Object lock = idempotencyLocks.computeIfAbsent(requestId, k -> new Object());
        try {
            synchronized (lock) {
                List<GroupOutcome> results = new ArrayList<>();
                for (int i = 0; i < request.groups().size(); i++) {
                    results.add(processGroup(request, i));
                }
                return assembleBatch(request, results);
            }
        } finally {
            idempotencyLocks.remove(requestId, lock);
        }
    }

    /** 处理批量中的单个分组：先查分组级幂等缓存，未命中则走单发判定路径并归一化异常。 */
    private GroupOutcome processGroup(BatchQueryRequest request, int index) {
        BatchGroup group = request.groups().get(index);
        String groupId = group != null && !isBlank(group.groupId())
                ? group.groupId() : "#" + index;
        java.util.Map<String, String> dimensions = group == null ? null : group.dimensions();
        String cacheKey = request.requestId() + "\n" + groupId;

        GroupOutcome cached = batchGroupCache.get(cacheKey);
        if (cached != null) {
            log.info("requestId={} 分组={} 判定=命中幂等缓存 预算变化=0 status={} errorCode={}",
                    request.requestId(), groupId, cached.getStatus(), cached.getErrorCode());
            GroupOutcome copy = copyOf(cached);
            copy.setDeduplicated(true);
            return copy;
        }

        GroupOutcome outcome;
        if (dimensions == null) {
            // 分组级报文问题只影响该分组，不连累整批。
            outcome = rejectedGroup(request, groupId, dimensions,
                    ErrorCode.MALFORMED_REQUEST, "分组 dimensions 不能为 null");
        } else {
            // 复用单发判定路径；requestId 带上分组标识，保证日志可追溯到具体分组。
            QueryRequest single = new QueryRequest(
                    request.requestId() + "/" + groupId,
                    request.dataset(), dimensions, request.metric(),
                    request.aggregation(), request.role(), request.policyVersion());
            try {
                outcome = toOutcome(groupId, dimensions, doSubmit(single));
            } catch (GatewayException e) {
                outcome = toOutcome(groupId, dimensions,
                        reject(single, null, null, e.errorCode(), e.getMessage(), null));
            } catch (RuntimeException e) {
                log.warn("requestId={} 分组={} 归一化未知内部失败: {}",
                        request.requestId(), groupId, e.getClass().getSimpleName());
                outcome = toOutcome(groupId, dimensions,
                        reject(single, null, null, ErrorCode.INTERNAL_PROCESSING_ERROR,
                                ErrorCode.INTERNAL_PROCESSING_ERROR.safeMessage(), null));
            }
        }
        outcome.setDeduplicated(false);
        cacheIfDeterministic(batchGroupCache, cacheKey, outcome);
        return outcome;
    }

    /** 汇总整批结论：分组计数 + 按预算键的净扣减对账单。 */
    private BatchGatewayResponse assembleBatch(BatchQueryRequest request, List<GroupOutcome> results) {
        BatchGatewayResponse response = new BatchGatewayResponse();
        response.setRequestId(request.requestId());
        response.setDataset(request.dataset());
        response.setMetric(request.metric());
        response.setAggregation(request.aggregation());
        response.setResults(results);
        response.setTotalGroups(results.size());

        int allowed = 0;
        int degraded = 0;
        int rejected = 0;
        // 按预算键（首次出现顺序）汇总净扣减；只统计本次调用真正出了结果的分组。
        // 命中幂等缓存的重放分组本次没有产生新的扣减，不计入，保证
        // “budgetUsage 合计 == 本次调用对账本的实际影响”严格成立。
        java.util.Map<String, BudgetUsageSummary> usageByKey = new java.util.LinkedHashMap<>();
        for (GroupOutcome outcome : results) {
            switch (outcome.getStatus()) {
                case ALLOWED -> allowed++;
                case DEGRADED -> degraded++;
                case REJECTED -> rejected++;
            }
            if (outcome.getStatus() != ReleaseStatus.REJECTED && !outcome.isDeduplicated()) {
                String budgetKey = request.dataset() + "#" + outcome.getDimensionKey();
                BudgetUsageSummary existing = usageByKey.get(budgetKey);
                if (existing == null) {
                    usageByKey.put(budgetKey, new BudgetUsageSummary(
                            budgetKey, outcome.getBudgetDelta(), outcome.getBudgetRemaining()));
                } else {
                    usageByKey.put(budgetKey, new BudgetUsageSummary(
                            budgetKey, existing.totalDelta() + outcome.getBudgetDelta(),
                            outcome.getBudgetRemaining()));
                }
            }
        }
        response.setAllowedGroups(allowed);
        response.setDegradedGroups(degraded);
        response.setRejectedGroups(rejected);
        response.setBudgetUsage(List.copyOf(usageByKey.values()));

        log.info("requestId={} 批量汇总 总数={} 正常={} 降级={} 拒绝={} 预算对账={}",
                request.requestId(), results.size(), allowed, degraded, rejected,
                usageByKey.values().stream()
                        .map(u -> u.budgetKey() + " 净扣减=" + format(u.totalDelta())
                                + " 剩余=" + format(u.remaining()))
                        .collect(java.util.stream.Collectors.joining(", ")));
        return response;
    }

    private static String validateBatch(BatchQueryRequest request) {
        if (isBlank(request.requestId())) {
            return "requestId 必填";
        }
        if (isBlank(request.dataset())) {
            return "dataset 必填";
        }
        if (isBlank(request.metric())) {
            return "metric 必填";
        }
        if (request.aggregation() == null) {
            return "aggregation 必填";
        }
        if (isBlank(request.role())) {
            return "role 必填";
        }
        if (request.groups() == null || request.groups().isEmpty()) {
            return "groups 至少包含一个分组";
        }
        return null;
    }

    /** 批量级错误：整批无法处理（报文不合法），不产生任何分组结论与预算变化。 */
    private BatchGatewayResponse batchError(BatchQueryRequest request, String detail) {
        String requestId = request == null ? null : request.requestId();
        log.info("requestId={} 判定=BATCH_REJECTED 预算变化=0 依据={} detail={}",
                requestId, ErrorCode.MALFORMED_REQUEST.code(), detail);
        BatchGatewayResponse response = new BatchGatewayResponse();
        response.setRequestId(requestId);
        if (request != null) {
            response.setDataset(request.dataset());
            response.setMetric(request.metric());
            response.setAggregation(request.aggregation());
            response.setTotalGroups(request.groups() == null ? 0 : request.groups().size());
        }
        response.setResults(List.of());
        response.setBudgetUsage(List.of());
        response.setBatchError(ErrorCode.MALFORMED_REQUEST.code());
        response.setBatchErrorMessage(ErrorCode.MALFORMED_REQUEST.safeMessage() + ": " + detail);
        return response;
    }

    /** 分组级拒绝（不经过单发路径的报文问题），净扣减恒为 0。 */
    private GroupOutcome rejectedGroup(BatchQueryRequest request, String groupId,
                                       java.util.Map<String, String> dimensions,
                                       ErrorCode errorCode, String detail) {
        log.info("requestId={} 分组={} 判定=REJECTED 预算变化=0 依据={} detail={}",
                request.requestId(), groupId, errorCode.code(), detail);
        GroupOutcome outcome = new GroupOutcome();
        outcome.setGroupId(groupId);
        outcome.setDimensions(dimensions);
        outcome.setStatus(ReleaseStatus.REJECTED);
        outcome.setBudgetDelta(0d);
        outcome.setErrorCode(errorCode.code());
        outcome.setErrorMessage(errorCode.safeMessage());
        outcome.setNotices(List.of(detail));
        outcome.setDecisionBasis("拒绝类型=" + errorCode.code()
                + "(优先级=" + errorCode.priority() + "): " + detail);
        return outcome;
    }

    /** 单发结论 => 分组结论的字段映射。 */
    private static GroupOutcome toOutcome(String groupId, java.util.Map<String, String> dimensions,
                                          GatewayResponse source) {
        GroupOutcome target = new GroupOutcome();
        target.setGroupId(groupId);
        target.setDimensions(dimensions);
        target.setStatus(source.getStatus());
        target.setDimensionKey(source.getDimensionKey());
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

    private static GroupOutcome copyOf(GroupOutcome source) {
        GroupOutcome target = new GroupOutcome();
        target.setGroupId(source.getGroupId());
        target.setDimensions(source.getDimensions());
        target.setStatus(source.getStatus());
        target.setDimensionKey(source.getDimensionKey());
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
                    cacheIfDeterministic(idempotencyCache, requestId, response);
                    return response;
                } catch (RuntimeException e) {
                    // 任何未被分层捕获的底层失败，统一归一化，绝不透出原始异常信息。
                    log.warn("requestId={} 归一化未知内部失败: {}", requestId, e.getClass().getSimpleName());
                    GatewayResponse response = reject(request, null, null,
                            ErrorCode.INTERNAL_PROCESSING_ERROR,
                            ErrorCode.INTERNAL_PROCESSING_ERROR.safeMessage(), null);
                    cacheIfDeterministic(idempotencyCache, requestId, response);
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

        // 噪声与聚合都是纯函数、无副作用，先于扣减完成：
        // 底层数据问题（如指标值无法解析）在此暴露时，请求失败且净扣减严格为零。
        DeterministicNoise.NoiseResult noise = DeterministicNoise.generate(
                policy.version(), request.dataset(), budgetKey.dimensionKey(),
                request.metric(), request.aggregation().name(), policy.epsilon());
        double trueValue = aggregationEngine.aggregate(
                evaluation.usableRows(), request.metric(), request.aggregation());

        // 唯一的副作用点：原子“检查 + 扣减”。此后仅剩纯计算，
        // 保证扣减与结果生成处于同一状态视图。
        BudgetAccount.ConsumeResult consumed =
                budgetLedger.consume(budgetKey, policy.epsilon(), DEFAULT_BUDGET_TOTAL);
        if (consumed == null) {
            throw new GatewayException(ErrorCode.BUDGET_EXHAUSTED, "隐私预算余额不足");
        }

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

    /**
     * 只把“确定性结论”写入幂等缓存：成功（ALLOWED / DEGRADED）与确定性业务拒绝
     * （越权 / 分组过小 / 预算不足等）可复现，重发必须命中缓存、不得重复扣预算；
     * 内部处理失败（{@link ErrorCode#INTERNAL_PROCESSING_ERROR}）视为瞬时故障，不入缓存，
     * 底层恢复后同一 requestId 原样重发应得到新的结论。
     */
    private static <T> void cacheIfDeterministic(ConcurrentMap<String, T> cache, String key, T response) {
        if (isDeterministic(response)) {
            cache.put(key, response);
        }
    }

    private static boolean isDeterministic(Object response) {
        String errorCode;
        if (response instanceof GatewayResponse single) {
            errorCode = single.getErrorCode();
        } else if (response instanceof GroupOutcome group) {
            errorCode = group.getErrorCode();
        } else {
            return true;
        }
        return !ErrorCode.INTERNAL_PROCESSING_ERROR.code().equals(errorCode);
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
