package com.politeai.interfaces.api.transform;

import com.politeai.application.transform.TransformAppService;
import com.politeai.domain.transform.model.TransformResult;
import com.politeai.domain.user.model.User;
import com.politeai.domain.user.model.UserTier;
import com.politeai.domain.user.repository.UserRepository;
import com.politeai.interfaces.api.dto.PartialRewriteRequest;
import com.politeai.interfaces.api.dto.PartialRewriteResponse;
import com.politeai.interfaces.api.dto.TierInfoResponse;
import com.politeai.interfaces.api.dto.TransformRequest;
import com.politeai.interfaces.api.dto.TransformResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transform")
@RequiredArgsConstructor
public class TransformController {

    private final TransformAppService transformAppService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<TransformResponse> transform(@Valid @RequestBody TransformRequest request) {
        UserTier tier = resolveUserTier();

        TransformResult result = transformAppService.transform(
                request.persona(),
                request.contexts(),
                request.toneLevel(),
                request.originalText(),
                request.userPrompt(),
                tier);

        return ResponseEntity.ok(new TransformResponse(result.getTransformedText()));
    }

    @PostMapping("/partial")
    public ResponseEntity<PartialRewriteResponse> partialRewrite(@Valid @RequestBody PartialRewriteRequest request) {
        UserTier tier = resolveUserTier();

        TransformResult result = transformAppService.partialRewrite(
                request.selectedText(),
                request.fullContext(),
                request.persona(),
                request.contexts(),
                request.toneLevel(),
                request.userPrompt(),
                tier);

        return ResponseEntity.ok(new PartialRewriteResponse(result.getTransformedText()));
    }

    @GetMapping("/tier")
    public ResponseEntity<TierInfoResponse> getTierInfo() {
        UserTier tier = resolveUserTier();
        int maxTextLength = transformAppService.getMaxTextLength(tier);
        boolean partialRewriteEnabled = tier == UserTier.PAID;
        boolean promptEnabled = tier == UserTier.PAID;

        return ResponseEntity.ok(new TierInfoResponse(
                tier.name(), maxTextLength, partialRewriteEnabled, promptEnabled));
    }

    private UserTier resolveUserTier() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return UserTier.FREE;
        }

        String email = (String) auth.getPrincipal();
        return userRepository.findByEmail(email)
                .map(User::getTier)
                .orElse(UserTier.FREE);
    }
}
