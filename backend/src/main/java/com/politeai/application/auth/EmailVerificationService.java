package com.politeai.application.auth;

import com.politeai.application.auth.exception.DuplicateEmailException;
import com.politeai.application.auth.exception.InvalidVerificationCodeException;
import com.politeai.application.auth.exception.VerificationExpiredException;
import com.politeai.application.auth.exception.VerificationNotFoundException;
import com.politeai.domain.auth.model.EmailVerification;
import com.politeai.domain.auth.repository.EmailVerificationRepository;
import com.politeai.domain.auth.service.EmailService;
import com.politeai.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final int CODE_LENGTH = 6;
    private static final int EXPIRATION_MINUTES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Transactional
    public void sendVerificationCode(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        String code = generateCode();
        EmailVerification verification = new EmailVerification(email, code, EXPIRATION_MINUTES);
        verificationRepository.save(verification);

        emailService.sendVerificationEmail(email, code);
    }

    @Transactional
    public void verifyCode(String email, String code) {
        EmailVerification verification = verificationRepository
                .findTopByEmailOrderByCreatedAtDesc(email)
                .orElseThrow(VerificationNotFoundException::new);

        if (verification.isExpired()) {
            throw new VerificationExpiredException();
        }

        if (!verification.getCode().equals(code)) {
            throw new InvalidVerificationCodeException();
        }

        verification.markVerified();
    }

    private String generateCode() {
        int number = RANDOM.nextInt(900_000) + 100_000;
        return String.valueOf(number);
    }
}
