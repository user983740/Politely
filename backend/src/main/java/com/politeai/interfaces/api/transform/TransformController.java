package com.politeai.interfaces.api.transform;

import com.politeai.application.transform.TransformAppService;
import com.politeai.domain.transform.model.TransformResult;
import com.politeai.infrastructure.ai.AiStreamingTransformService;
import com.politeai.interfaces.api.dto.TierInfoResponse;
import com.politeai.interfaces.api.dto.TransformRequest;
import com.politeai.interfaces.api.dto.TransformResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/transform")
@RequiredArgsConstructor
public class TransformController {

    private final TransformAppService transformAppService;
    private final AiStreamingTransformService streamingTransformService;

    @PostMapping
    public ResponseEntity<TransformResponse> transform(@Valid @RequestBody TransformRequest request) {
        TransformResult result = transformAppService.transform(
                request.persona(),
                request.contexts(),
                request.toneLevel(),
                request.originalText(),
                request.userPrompt(),
                request.senderInfo());

        return ResponseEntity.ok(new TransformResponse(
                result.getTransformedText(),
                result.getAnalysisContext()
        ));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTransform(@Valid @RequestBody TransformRequest request) {
        transformAppService.validateTransformRequest(request.originalText());

        return streamingTransformService.streamTransform(
                request.persona(),
                request.contexts(),
                request.toneLevel(),
                request.originalText(),
                request.userPrompt(),
                request.senderInfo(),
                Boolean.TRUE.equals(request.identityBoosterToggle()),
                request.topic(),
                request.purpose(),
                transformAppService.resolveFinalMaxTokens());
    }

    @GetMapping("/tier")
    public ResponseEntity<TierInfoResponse> getTierInfo() {
        int maxTextLength = transformAppService.getMaxTextLength();

        return ResponseEntity.ok(new TierInfoResponse(
                "PAID", maxTextLength, true));
    }
}
