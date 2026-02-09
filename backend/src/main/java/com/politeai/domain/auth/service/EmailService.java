package com.politeai.domain.auth.service;

public interface EmailService {

    void sendVerificationEmail(String to, String code);
}
