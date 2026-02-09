package com.politeai.application.auth.exception;

public class InvalidPasswordFormatException extends RuntimeException {
    public InvalidPasswordFormatException() {
        super("비밀번호는 영문, 숫자, 특수문자를 모두 포함하여 8자 이상이어야 합니다.");
    }
}
