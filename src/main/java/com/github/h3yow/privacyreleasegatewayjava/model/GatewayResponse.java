package com.github.h3yow.privacyreleasegatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * 网关统一结论。ALLOWED / DEGRADED 携带结果与可解释元数据；REJECTED 携带可区分的错误类型。
 * 该对象同时作为重复请求的幂等快照，保证同一 requestId 永远返回同一结论。
 */
public class GatewayResponse {

    /** 脱敏后的维度投影。 */
    private Map<String, String> maskedDimensions;

    private String requestId;
    private ReleaseStatus status;
    private String dataset;
    private String dimensionKey;
    private String metric;
    private AggregationType aggregation;
    private String policyVersion;

    /** 加噪后的对外结果；拒绝时为 null。 */
    private Double value;
    /** 实际参与计算的分组行数（范围校验依据）。 */
    private Integer groupSize;
    /** 本次使用的噪声尺度。 */
    private Double noiseScale;

    /** 扣减前预算。 */
    private Double budgetBefore;
    /** 本次扣减量。 */
    private Double budgetDelta;
    /** 扣减后剩余预算。 */
    private Double budgetRemaining;

    /** 降级说明，例如被最小化剔除的字段。 */
    private List<String> notices;
    /** 拒绝错误码（稳定对外类型）。 */
    private String errorCode;
    /** 对外安全错误信息，不含原始异常。 */
    private String errorMessage;
    /** 是否命中重复请求缓存。 */
    private boolean deduplicated;
    /** 判定依据的可读说明，用于审计与测试日志。 */
    private String decisionBasis;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public ReleaseStatus getStatus() { return status; }
    public void setStatus(ReleaseStatus status) { this.status = status; }

    public String getDataset() { return dataset; }
    public void setDataset(String dataset) { this.dataset = dataset; }

    public String getDimensionKey() { return dimensionKey; }
    public void setDimensionKey(String dimensionKey) { this.dimensionKey = dimensionKey; }

    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }

    public AggregationType getAggregation() { return aggregation; }
    public void setAggregation(AggregationType aggregation) { this.aggregation = aggregation; }

    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }

    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }

    public Integer getGroupSize() { return groupSize; }
    public void setGroupSize(Integer groupSize) { this.groupSize = groupSize; }

    public Double getNoiseScale() { return noiseScale; }
    public void setNoiseScale(Double noiseScale) { this.noiseScale = noiseScale; }

    public Double getBudgetBefore() { return budgetBefore; }
    public void setBudgetBefore(Double budgetBefore) { this.budgetBefore = budgetBefore; }

    public Double getBudgetDelta() { return budgetDelta; }
    public void setBudgetDelta(Double budgetDelta) { this.budgetDelta = budgetDelta; }

    public Double getBudgetRemaining() { return budgetRemaining; }
    public void setBudgetRemaining(Double budgetRemaining) { this.budgetRemaining = budgetRemaining; }

    public List<String> getNotices() { return notices; }
    public void setNotices(List<String> notices) { this.notices = notices; }

    public Map<String, String> getMaskedDimensions() { return maskedDimensions; }
    public void setMaskedDimensions(Map<String, String> maskedDimensions) {
        this.maskedDimensions = maskedDimensions;
    }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean isDeduplicated() { return deduplicated; }
    public void setDeduplicated(boolean deduplicated) { this.deduplicated = deduplicated; }

    public String getDecisionBasis() { return decisionBasis; }
    public void setDecisionBasis(String decisionBasis) { this.decisionBasis = decisionBasis; }
}
