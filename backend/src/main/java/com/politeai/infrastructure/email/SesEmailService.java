package com.politeai.infrastructure.email;

import com.politeai.domain.auth.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

@Service
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class SesEmailService implements EmailService {

    private final SesClient sesClient;

    @Value("${app.email.sender}")
    private String senderEmail;

    @Override
    public void sendVerificationEmail(String to, String code) {
        String subject = "[PoliteAi] 이메일 인증코드";
        String body = String.format("""
                안녕하세요, PoliteAi입니다.

                이메일 인증코드: %s

                인증코드는 5분간 유효합니다.
                본인이 요청하지 않은 경우 이 메일을 무시해주세요.""", code);

        SendEmailRequest request = SendEmailRequest.builder()
                .source(senderEmail)
                .destination(Destination.builder().toAddresses(to).build())
                .message(Message.builder()
                        .subject(Content.builder().data(subject).charset("UTF-8").build())
                        .body(Body.builder()
                                .text(Content.builder().data(body).charset("UTF-8").build())
                                .build())
                        .build())
                .build();

        sesClient.sendEmail(request);
        log.info("Verification email sent to {}", to);
    }
}
