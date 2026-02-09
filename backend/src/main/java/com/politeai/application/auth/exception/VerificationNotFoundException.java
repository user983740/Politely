package com.politeai.application.auth.exception;

public class VerificationNotFoundException extends RuntimeException {
    public VerificationNotFoundException() {
        super("인증 요청 내역이 없습니다.");
    }
}
