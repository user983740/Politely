package com.politeai.interfaces.api.auth;

import com.politeai.application.auth.AuthResult;
import com.politeai.application.auth.AuthService;
import com.politeai.application.auth.EmailVerificationService;
import com.politeai.interfaces.api.dto.AuthResponse;
import com.politeai.interfaces.api.dto.CheckLoginIdRequest;
import com.politeai.interfaces.api.dto.LoginRequest;
import com.politeai.interfaces.api.dto.SendVerificationRequest;
import com.politeai.interfaces.api.dto.SignupRequest;
import com.politeai.interfaces.api.dto.VerifyCodeRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/email/send-code")
    public ResponseEntity<Map<String, String>> sendVerificationCode(
            @Valid @RequestBody SendVerificationRequest request) {
        emailVerificationService.sendVerificationCode(request.email());
        return ResponseEntity.ok(Map.of("message", "인증코드가 발송되었습니다."));
    }

    @PostMapping("/email/verify-code")
    public ResponseEntity<Map<String, String>> verifyCode(
            @Valid @RequestBody VerifyCodeRequest request) {
        emailVerificationService.verifyCode(request.email(), request.code());
        return ResponseEntity.ok(Map.of("message", "이메일 인증이 완료되었습니다."));
    }

    @PostMapping("/check-login-id")
    public ResponseEntity<Map<String, Boolean>> checkLoginId(
            @Valid @RequestBody CheckLoginIdRequest request) {
        boolean available = authService.checkLoginIdAvailability(request.loginId());
        return ResponseEntity.ok(Map.of("available", available));
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        AuthResult result = authService.signup(
                request.email(), request.loginId(), request.name(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(result.token(), result.email(), result.loginId(), result.name()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request.email(), request.password());
        return ResponseEntity.ok(
                new AuthResponse(result.token(), result.email(), result.loginId(), result.name()));
    }
}
