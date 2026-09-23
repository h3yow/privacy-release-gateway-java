package com.github.h3yow.privacyreleasegatewayjava.web;

public record ApiError(String requestId, String errorCode, String message) {
}
