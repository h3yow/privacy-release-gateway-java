package com.github.h3yow.privacyreleasegatewayjava.batch;

/**
 * 批量请求对单个预算键的汇总影响。
 * totalDelta 等于本批中该键下所有“出了结果”的分组扣减之和，
 * 必须与账本中该键的实际余额变化完全一致（对账依据）。
 *
 * @param budgetKey  预算键（dataset#维度串）
 * @param totalDelta 本批对该键的净扣减
 * @param remaining  本批处理完成后该键的剩余预算
 */
public record BudgetUsageSummary(String budgetKey, double totalDelta, double remaining) {
}
