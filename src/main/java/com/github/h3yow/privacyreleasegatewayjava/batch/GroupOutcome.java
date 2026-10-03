package com.github.h3yow.privacyreleasegatewayjava.batch;

import com.github.h3yow.privacyreleasegatewayjava.model.AggregationType;
import com.github.h3yow.privacyreleasegatewayjava.model.ReleaseStatus;

import java.util.List;
import java.util.Map;

/**
 * 批量响应中单个分组的结论。字段语义与单发响应一一对应：
 * ALLOWED / DEGRADED 携带加噪结果与预算扣减快照；REJECTED 携带稳定错误类型且预算扣减恒为 0。
 */
public class GroupOutcome {

    /** 请求中给定的分组标识（缺省时为下标）。 */
    private String groupId;
    /** 该分组的维度条件（原样回显，便于对账）。 */
    private Map<String, String> dimensions;

    private ReleaseStatus status;
    private String dimensionKey;
    private String policyVersion;

    /** 加噪后的对外结果；拒绝时为 null。 */
    private Double value;
    /** 实际参与计算的分组行数。 */
    private Integer groupSize;
    /** 本次使用的噪声尺度。 */
    private Double noiseScale;

    /** 扣减前该预算键余额。 */
    private Double budgetBefore;
    /** 本分组实际扣减量；未出结果（拒绝 / 失败）时恒为 0。 */
    private Double budgetDelta;
    /** 扣减后该预算键余额。 */
    private Double budgetRemaining;

    /** 降级说明。 */
    private List<String> notices;
    /** 脱敏后的维度投影。 */
    private Map<String, String> maskedDimensions;
    /** 拒绝错误码（稳定对外类型）。 */
    private String errorCode;
    /** 对外安全错误信息，不含内部细节。 */
    private String errorMessage;
    /** 是否命中幂等缓存（同 requestId+分组 重发）。 */
    private boolean deduplicated;
    /** 判定依据的可读说明。 */
    private String decisionBasis;

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public Map<String, String> getDimensions() { return dimensions; }
    public void setDimensions(Map<String, String> dimensions) { this.dimensions = dimensions; }

    public ReleaseStatus getStatus() { return status; }
    public void setStatus(ReleaseStatus status) { this.status = status; }

    public String getDimensionKey() { return dimensionKey; }
    public void setDimensionKey(String dimensionKey) { this.dimensionKey = dimensionKey; }

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
