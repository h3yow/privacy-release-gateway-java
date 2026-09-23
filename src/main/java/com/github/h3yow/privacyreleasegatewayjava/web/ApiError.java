package com.github.h3yow.privacyreleasegatewayjava.web;

/**
 * 对外错误体：只包含稳定 code 与脱敏后的说明，不包含堆栈/原始异常。
 */
public record ApiError(String requestId, String code, String message) {
}
