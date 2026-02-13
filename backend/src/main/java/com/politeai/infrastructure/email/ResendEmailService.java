package com.politeai.infrastructure.email;

import com.politeai.domain.auth.service.EmailService;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class ResendEmailService implements EmailService {

    private final Resend resend;

    @Value("${app.email.sender}")
    private String senderEmail;

    @Override
    public void sendVerificationEmail(String to, String code) {
        String subject = "[Politely] 이메일 인증코드";
        String body = String.format("""
                안녕하세요, Politely입니다.

                이메일 인증코드: %s

                인증코드는 5분간 유효합니다.
                본인이 요청하지 않은 경우 이 메일을 무시해주세요.""", code);

        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(senderEmail)
                .to(to)
                .subject(subject)
                .text(body)
                .build();

        try {
            resend.emails().send(options);
            log.info("Verification email sent to {}", to);
        } catch (ResendException e) {
            log.error("Failed to send verification email to {}: {}", to, e.getMessage());
            throw new RuntimeException("이메일 발송에 실패했습니다.", e);
        }
    }
}
