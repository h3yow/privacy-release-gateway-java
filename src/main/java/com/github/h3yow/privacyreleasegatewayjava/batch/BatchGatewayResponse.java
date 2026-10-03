package com.github.h3yow.privacyreleasegatewayjava.batch;

import com.github.h3yow.privacyreleasegatewayjava.model.AggregationType;

import java.util.List;

/**
 * 批量发布的统一结论。每个分组独立出结论（{@link GroupOutcome}），
 * 部分分组失败 / 被拒绝不影响其它分组；预算汇总 {@link #budgetUsage} 可与账本直接对账。
 */
public class BatchGatewayResponse {

    private String requestId;
    private String dataset;
    private String metric;
    private AggregationType aggregation;

    private int totalGroups;
    private int allowedGroups;
    private int degradedGroups;
    private int rejectedGroups;

    /** 每个分组的独立结论，顺序与请求中的 groups 一致。 */
    private List<GroupOutcome> results;

    /** 按预算键汇总的本批净扣减；只统计出了结果（ALLOWED / DEGRADED）的分组。 */
    private List<BudgetUsageSummary> budgetUsage;

    /** 批量级错误（如整批报文不合法）；正常处理时为 null。 */
    private String batchError;
    /** 批量级错误的安全说明。 */
    private String batchErrorMessage;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getDataset() { return dataset; }
    public void setDataset(String dataset) { this.dataset = dataset; }

    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }

    public AggregationType getAggregation() { return aggregation; }
    public void setAggregation(AggregationType aggregation) { this.aggregation = aggregation; }

    public int getTotalGroups() { return totalGroups; }
    public void setTotalGroups(int totalGroups) { this.totalGroups = totalGroups; }

    public int getAllowedGroups() { return allowedGroups; }
    public void setAllowedGroups(int allowedGroups) { this.allowedGroups = allowedGroups; }

    public int getDegradedGroups() { return degradedGroups; }
    public void setDegradedGroups(int degradedGroups) { this.degradedGroups = degradedGroups; }

    public int getRejectedGroups() { return rejectedGroups; }
    public void setRejectedGroups(int rejectedGroups) { this.rejectedGroups = rejectedGroups; }

    public List<GroupOutcome> getResults() { return results; }
    public void setResults(List<GroupOutcome> results) { this.results = results; }

    public List<BudgetUsageSummary> getBudgetUsage() { return budgetUsage; }
    public void setBudgetUsage(List<BudgetUsageSummary> budgetUsage) { this.budgetUsage = budgetUsage; }

    public String getBatchError() { return batchError; }
    public void setBatchError(String batchError) { this.batchError = batchError; }

    public String getBatchErrorMessage() { return batchErrorMessage; }
    public void setBatchErrorMessage(String batchErrorMessage) {
        this.batchErrorMessage = batchErrorMessage;
    }
}
