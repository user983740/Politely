package com.politeai.infrastructure.ai;

public class AiTransformException extends RuntimeException {

    public AiTransformException(String message) {
        super(message);
    }

    public AiTransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
