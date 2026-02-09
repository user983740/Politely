package com.politeai.application.auth.exception;

public class VerificationExpiredException extends RuntimeException {
    public VerificationExpiredException() {
        super("인증코드가 만료되었습니다. 다시 발송해주세요.");
    }
}
