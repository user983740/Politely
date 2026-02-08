package com.politeai.interfaces.api.transform;

import com.politeai.application.transform.TransformAppService;
import com.politeai.domain.transform.model.TransformResult;
import com.politeai.interfaces.api.dto.PartialRewriteRequest;
import com.politeai.interfaces.api.dto.PartialRewriteResponse;
import com.politeai.interfaces.api.dto.TransformRequest;
import com.politeai.interfaces.api.dto.TransformResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transform")
@RequiredArgsConstructor
public class TransformController {

    private final TransformAppService transformAppService;

    @PostMapping
    public ResponseEntity<TransformResponse> transform(@Valid @RequestBody TransformRequest request) {
        TransformResult result = transformAppService.transform(
                request.persona(),
                request.contexts(),
                request.toneLevel(),
                request.originalText(),
                request.userPrompt());

        return ResponseEntity.ok(new TransformResponse(result.getTransformedText()));
    }

    @PostMapping("/partial")
    public ResponseEntity<PartialRewriteResponse> partialRewrite(@Valid @RequestBody PartialRewriteRequest request) {
        TransformResult result = transformAppService.partialRewrite(
                request.selectedText(),
                request.fullContext(),
                request.persona(),
                request.contexts(),
                request.toneLevel(),
                request.userPrompt());

        return ResponseEntity.ok(new PartialRewriteResponse(result.getTransformedText()));
    }
}
