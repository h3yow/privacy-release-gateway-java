package com.github.h3yow.privacyreleasegatewayjava.model;

import java.util.List;

/**
 * 批量发布统一结论。每个分组一条 {@link GatewayResponse}，顺序与请求 groups 一致；
 * 部分失败不影响其它分组结论。
 * totalBudgetDelta 只汇总本趟真正新扣预算的分组（不含命中幂等缓存的分组），
 * 因此恒等于本趟请求对预算账本的实际净变化，可直接与预算查询结果对账。
 */
public class BatchGatewayResponse {

    private String requestId;
    private BatchStatus batchStatus;
    private int totalGroups;
    private int succeeded;
    private int failed;
    /** 本趟请求真正新扣的预算合计 = 各非幂等命中（本次真正扣减）出结果分组 budgetDelta 之和；
     *  重发时命中缓存的分组不计入，故恒等于本趟对账本的实际净变化。 */
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
