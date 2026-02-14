package com.politeai.infrastructure.email;

import com.politeai.domain.auth.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!prod & !resend")
@Slf4j
public class ConsoleEmailService implements EmailService {

    @Override
    public void sendVerificationEmail(String to, String code) {
        log.info("""
                ========================================
                [DEV] 이메일 인증코드 발송
                수신: {}
                인증코드: {}
                ========================================""", to, code);
    }
}
