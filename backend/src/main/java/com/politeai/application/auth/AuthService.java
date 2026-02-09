package com.politeai.application.auth;

import com.politeai.application.auth.exception.DuplicateEmailException;
import com.politeai.application.auth.exception.DuplicateLoginIdException;
import com.politeai.application.auth.exception.EmailNotVerifiedException;
import com.politeai.application.auth.exception.InvalidCredentialsException;
import com.politeai.domain.auth.model.EmailVerification;
import com.politeai.domain.auth.repository.EmailVerificationRepository;
import com.politeai.domain.user.model.User;
import com.politeai.domain.user.repository.UserRepository;
import com.politeai.infrastructure.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final EmailVerificationRepository verificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional
    public AuthResult signup(String email, String loginId, String name, String password) {
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        if (userRepository.existsByLoginId(loginId)) {
            throw new DuplicateLoginIdException();
        }

        EmailVerification verification = verificationRepository
                .findTopByEmailOrderByCreatedAtDesc(email)
                .orElseThrow(EmailNotVerifiedException::new);

        if (!verification.isVerified()) {
            throw new EmailNotVerifiedException();
        }

        User user = User.builder()
                .email(email)
                .loginId(loginId)
                .name(name)
                .password(passwordEncoder.encode(password))
                .build();

        userRepository.save(user);

        String token = jwtProvider.generateToken(user.getEmail());
        return new AuthResult(token, user.getEmail(), user.getLoginId(), user.getName());
    }

    @Transactional(readOnly = true)
    public AuthResult login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtProvider.generateToken(user.getEmail());
        return new AuthResult(token, user.getEmail(), user.getLoginId(), user.getName());
    }

    @Transactional(readOnly = true)
    public boolean checkLoginIdAvailability(String loginId) {
        return !userRepository.existsByLoginId(loginId);
    }
}
