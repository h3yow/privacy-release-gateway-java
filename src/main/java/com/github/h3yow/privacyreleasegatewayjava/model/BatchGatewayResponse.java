package com.github.h3yow.privacyreleasegatewayjava.model;

import java.util.List;

/**
 * 批量发布统一结论。每个分组一条 {@link GatewayResponse}，顺序与请求 groups 一致；
 * 部分失败不影响其它分组结论。
 * <p>
 * {@code totalBudgetDelta} 的口径是<strong>本趟请求对账本造成的实际净扣减</strong>：
 * 只累加本趟真正新算出结论并完成扣减的分组（{@code deduplicated=false} 的
 * ALLOWED/DEGRADED）。逐组结果里的 {@code budgetDelta} 是该分组历史首算时的快照，
 * 重发时逐字复现、不代表本趟变化；命中缓存的分组本趟零扣减，不计入合计。
 */
public class BatchGatewayResponse {

    private String requestId;
    private BatchStatus batchStatus;
    private int totalGroups;
    private int succeeded;
    private int failed;
    /** 本趟整批对账本的实际净扣减 = 本趟新扣分组（deduplicated=false）budgetDelta 之和；重发命中缓存时为 0。 */
    private double totalBudgetDelta;
    /** 逐组结论，下标与请求 groups 一一对应。 */
    private List<GatewayResponse> results;
    /** 批量信封级错误（如报文缺必填项）；逐组处理时为空。 */
    private String errorCode;
    private String errorMessage;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public BatchStatus getBatchStatus() { return batchStatus; }
    public void setBatchStatus(BatchStatus batchStatus) { this.batchStatus = batchStatus; }

    public int getTotalGroups() { return totalGroups; }
    public void setTotalGroups(int totalGroups) { this.totalGroups = totalGroups; }

    public int getSucceeded() { return succeeded; }
    public void setSucceeded(int succeeded) { this.succeeded = succeeded; }

    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }

    public double getTotalBudgetDelta() { return totalBudgetDelta; }
    public void setTotalBudgetDelta(double totalBudgetDelta) { this.totalBudgetDelta = totalBudgetDelta; }

    public List<GatewayResponse> getResults() { return results; }
    public void setResults(List<GatewayResponse> results) { this.results = results; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
