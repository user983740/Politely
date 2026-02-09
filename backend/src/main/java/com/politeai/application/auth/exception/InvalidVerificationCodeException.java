package com.politeai.application.auth.exception;

public class InvalidVerificationCodeException extends RuntimeException {
    public InvalidVerificationCodeException() {
        super("인증코드가 올바르지 않습니다.");
    }
}
