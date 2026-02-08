package com.politeai.interfaces.api.dto;

public record AuthResponse(
        String token,
        String email
) {}
