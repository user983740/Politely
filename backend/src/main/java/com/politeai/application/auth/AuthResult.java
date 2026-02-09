package com.politeai.application.auth;

public record AuthResult(
        String token,
        String email,
        String loginId,
        String name
) {}
