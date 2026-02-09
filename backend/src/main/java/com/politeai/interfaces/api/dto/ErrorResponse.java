package com.politeai.interfaces.api.dto;

public record ErrorResponse(
        String error,
        String message
) {}
