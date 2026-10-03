package com.github.h3yow.privacyreleasegatewayjava.model;

/**
 * 批量整体状态：仅汇总本批各分组结论，不代表额外判定。
 */
public enum BatchStatus {
    /** 全部分组都出了结果（ALLOWED 或 DEGRADED）。 */
    ALL_SUCCEEDED,
    /** 部分分组出结果，部分被拒绝或失败。 */
    PARTIAL_SUCCESS,
    /** 没有任何分组出结果。 */
    ALL_FAILED
}
