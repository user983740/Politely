package com.politeai.infrastructure.ai.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.politeai.domain.transform.model.*;
import com.openai.models.ResponseFormatJsonObject;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContextGatingService {

    private final AiTransformService aiTransformService;
    private final ObjectMapper objectMapper;

    private static final String MODEL = "gpt-4o-mini";
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 300;
    private static final double OVERRIDE_CONFIDENCE_THRESHOLD = 0.72;

    public record ContextGatingResult(
        boolean shouldOverride,
        double confidence,
        Topic inferredTopic,
        Purpose inferredPurpose,
        SituationContext inferredPrimaryContext,
        String inferredTemplateId,
        List<String> reasons,
        List<String> safetyNotes,
        long promptTokens,
        long completionTokens
    ) {
        public boolean meetsThreshold() {
            return shouldOverride && confidence >= OVERRIDE_CONFIDENCE_THRESHOLD;
        }
    }

    private static final String SYSTEM_PROMPT = """
            당신은 한국어 메시지의 메타데이터 검증 전문가입니다.
            사용자가 선택한 메타데이터(수신자/상황/주제/목적)와 실제 텍스트 내용이 일치하는지 검증하세요.

            응답은 반드시 JSON 형식으로:
            {
              "should_override": true/false,
              "confidence": 0.0~1.0,
              "inferred": {
                "topic": "ENUM값 또는 null",
                "purpose": "ENUM값 또는 null",
                "primary_context": "ENUM값 또는 null",
                "template_id": "T01~T12 또는 null"
              },
              "reasons": ["이유1", "이유2"],
              "safety_notes": ["주의사항"]
            }

            Topic: REFUND_CANCEL, OUTAGE_ERROR, ACCOUNT_PERMISSION, DATA_FILE, SCHEDULE_DEADLINE, COST_BILLING, CONTRACT_TERMS, HR_EVALUATION, ACADEMIC_GRADE, COMPLAINT_REGULATION, OTHER
            Purpose: INFO_DELIVERY, DATA_REQUEST, SCHEDULE_COORDINATION, APOLOGY_RECOVERY, RESPONSIBILITY_SEPARATION, REJECTION_NOTICE, REFUND_REJECTION, WARNING_PREVENTION, RELATIONSHIP_RECOVERY, NEXT_ACTION_CONFIRM, ANNOUNCEMENT
            Context: REQUEST, SCHEDULE_DELAY, URGING, REJECTION, APOLOGY, COMPLAINT, ANNOUNCEMENT, FEEDBACK, BILLING, SUPPORT, CONTRACT, RECRUITING, CIVIL_COMPLAINT, GRATITUDE

            규칙:
            - 메타데이터가 텍스트 내용과 명백히 불일치할 때만 should_override=true
            - 애매한 경우 should_override=false (사용자 의도 존중)
            - inferred 값은 확신이 있을 때만 제공, 아니면 null
            """;

    public ContextGatingResult evaluate(Persona persona, List<SituationContext> contexts,
                                         Topic topic, Purpose purpose, ToneLevel toneLevel,
                                         String maskedText, List<LabeledSegment> labeledSegments) {
        String labelSummary = labeledSegments.stream()
                .map(ls -> ls.segmentId() + ":" + ls.label().name())
                .collect(Collectors.joining(", "));

        String truncatedText = maskedText.length() > 1200
                ? maskedText.substring(0, 1200) + "..."
                : maskedText;

        String userMessage = String.format("""
                사용자 메타:
                - 수신자: %s
                - 상황: %s
                - 주제: %s
                - 목적: %s
                - 톤: %s

                라벨 요약: %s

                텍스트 (마스킹):
                %s
                """,
                persona.name(),
                contexts.stream().map(Enum::name).collect(Collectors.joining(", ")),
                topic != null ? topic.name() : "미지정",
                purpose != null ? purpose.name() : "미지정",
                toneLevel.name(),
                labelSummary,
                truncatedText);

        try {
            LlmCallResult result = aiTransformService.callOpenAIWithModel(
                    MODEL, SYSTEM_PROMPT, userMessage, TEMPERATURE, MAX_TOKENS,
                    ResponseFormatJsonObject.builder().build());

            return parseResult(result);
        } catch (Exception e) {
            log.warn("[ContextGating] LLM call failed, returning no-override: {}", e.getMessage());
            return new ContextGatingResult(false, 0, null, null, null, null,
                    List.of(), List.of("LLM call failed: " + e.getMessage()), 0, 0);
        }
    }

    private ContextGatingResult parseResult(LlmCallResult result) {
        try {
            JsonNode root = objectMapper.readTree(result.content());

            boolean shouldOverride = root.path("should_override").asBoolean(false);
            double confidence = root.path("confidence").asDouble(0);

            JsonNode inferred = root.path("inferred");
            Topic inferredTopic = parseEnum(Topic.class, inferred.path("topic").asText(null));
            Purpose inferredPurpose = parseEnum(Purpose.class, inferred.path("purpose").asText(null));
            SituationContext inferredContext = parseEnum(SituationContext.class, inferred.path("primary_context").asText(null));
            String inferredTemplateId = inferred.path("template_id").asText(null);

            List<String> reasons = jsonArrayToList(root.path("reasons"));
            List<String> safetyNotes = jsonArrayToList(root.path("safety_notes"));

            return new ContextGatingResult(shouldOverride, confidence,
                    inferredTopic, inferredPurpose, inferredContext, inferredTemplateId,
                    reasons, safetyNotes, result.promptTokens(), result.completionTokens());
        } catch (Exception e) {
            log.warn("[ContextGating] Parse failed: {}", e.getMessage());
            return new ContextGatingResult(false, 0, null, null, null, null,
                    List.of(), List.of("Parse failed: " + e.getMessage()),
                    result.promptTokens(), result.completionTokens());
        }
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumClass, String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;
        try {
            return Enum.valueOf(enumClass, value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<String> jsonArrayToList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                list.add(item.asText());
            }
        }
        return list;
    }
}
