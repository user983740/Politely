package com.politeai.interfaces.api.dto;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PartialRewriteRequest(
        @NotBlank(message = "Selected text is required")
        @Size(max = 1000, message = "Selected text must not exceed 1000 characters")
        String selectedText,

        String fullContext,

        @NotNull(message = "Persona is required")
        Persona persona,

        @NotEmpty(message = "At least one context is required")
        List<SituationContext> contexts,

        @NotNull(message = "Tone level is required")
        ToneLevel toneLevel,

        @Size(max = 80, message = "User prompt must not exceed 80 characters")
        String userPrompt
) {}
