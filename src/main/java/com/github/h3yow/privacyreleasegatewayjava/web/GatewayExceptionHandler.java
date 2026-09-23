package com.github.h3yow.privacyreleasegatewayjava.web;

import com.github.h3yow.privacyreleasegatewayjava.engine.GatewayException;
import com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 异常链路归一化。
 *
 * <ul>
 *   <li>受控的 {@link GatewayException}：映射其携带的稳定错误码；</li>
 *   <li>请求体无法解析（JSON 错误等）：归一化为 INVALID_REQUEST；</li>
 *   <li>其它任何未预期异常：统一归一化为 INTERNAL_ERROR，
 *       响应中绝不包含原始异常类型、消息或堆栈（仅服务端日志留存）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiError> handleControlled(GatewayException ex) {
        log.warn("受控异常映射 code={} message={}", ex.getCode(), ex.getMessage());
        return build(ex.getCode(), ex.getMessage(), statusFor(ex.getCode()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败，归一化为 INVALID_REQUEST: {}", ex.toString());
        return build(RejectionCode.INVALID_REQUEST, "请求体格式不合法", HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAny(Exception ex) {
        log.error("未预期异常，归一化为 INTERNAL_ERROR: {}", ex.toString(), ex);
        return build(RejectionCode.INTERNAL_ERROR, "网关内部处理失败，请稍后重试",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<ApiError> build(RejectionCode code, String message, HttpStatus status) {
        return ResponseEntity.status(status).body(new ApiError(null, code.name(), message));
    }

    static HttpStatus statusFor(RejectionCode code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.FORBIDDEN;
            case FIELD_MISSING -> HttpStatus.UNPROCESSABLE_ENTITY;
            case POLICY_CONFLICT -> HttpStatus.CONFLICT;
            case BUDGET_OUT_OF_RANGE, BUDGET_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    static RejectionCode map(Throwable t) {
        return RejectionCode.INTERNAL_ERROR;
    }
}
