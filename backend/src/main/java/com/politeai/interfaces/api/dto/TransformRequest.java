package com.politeai.interfaces.api.dto;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TransformRequest(
        @NotNull(message = "Persona is required")
        Persona persona,

        @NotEmpty(message = "At least one context is required")
        List<SituationContext> contexts,

        @NotNull(message = "Tone level is required")
        ToneLevel toneLevel,

        @NotBlank(message = "Original text is required")
        @Size(max = 2000, message = "Original text must not exceed 2000 characters")
        String originalText,

        @Size(max = 500, message = "User prompt must not exceed 500 characters")
        String userPrompt,

        @Size(max = 100, message = "Sender info must not exceed 100 characters")
        String senderInfo,

        Boolean identityBoosterToggle
) {}
