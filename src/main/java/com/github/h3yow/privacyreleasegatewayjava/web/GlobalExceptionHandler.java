package com.github.h3yow.privacyreleasegatewayjava.web;

import com.github.h3yow.privacyreleasegatewayjava.engine.GatewayException;
import com.github.h3yow.privacyreleasegatewayjava.model.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 异常链路归一化：任何逃出控制器的异常都被映射为稳定错误类型 + 安全文案，
 * 响应中不包含堆栈、类名或原始 message 等内部信息。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiError> handleGateway(GatewayException e) {
        ErrorCode code = e.errorCode();
        log.warn("归一化业务失败 errorCode={} priority={}", code.code(), code.priority());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError(null, code.code(), code.safeMessage()));
    }

    /** JSON 无法解析 / 请求体缺失 => MALFORMED_REQUEST。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("归一化请求解析失败");
        return ResponseEntity.badRequest()
                .body(new ApiError(null, ErrorCode.MALFORMED_REQUEST.code(),
                        ErrorCode.MALFORMED_REQUEST.safeMessage()));
    }

    /** 一切其他未预期异常的统一兜底。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAny(Exception e) {
        log.warn("归一化未知异常 type={}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(null, ErrorCode.INTERNAL_PROCESSING_ERROR.code(),
                        ErrorCode.INTERNAL_PROCESSING_ERROR.safeMessage()));
    }
}
