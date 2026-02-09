package com.politeai.infrastructure.scheduling;

import com.politeai.domain.auth.repository.EmailVerificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class VerificationCleanupScheduler {

    private final EmailVerificationRepository verificationRepository;

    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupExpiredVerifications() {
        verificationRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        log.debug("Cleaned up expired email verification records");
    }
}
