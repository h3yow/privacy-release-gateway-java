package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode;

/**
 * 网关内部使用的受控异常，携带稳定错误码。外部处理器不向调用方透出原始异常。
 */
public class GatewayException extends RuntimeException {
    private final RejectionCode code;

    public GatewayException(RejectionCode code, String message) {
        super(message);
        this.code = code;
    }

    public RejectionCode getCode() { return code; }
}
