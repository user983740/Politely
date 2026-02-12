package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import com.politeai.infrastructure.ai.PromptBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds prompts for the multi-model pipeline:
 * - Model 1: Situation analysis + core intent
 * - Model 2: Inappropriate expression detection
 * - Model 3: Required expression detection
 * - Final Model: Transform using intermediate analysis
 */
@Component
@RequiredArgsConstructor
public class MultiModelPromptBuilder {

    private final PromptBuilder promptBuilder;

    // ===== Model 1: Situation Analysis =====

    public String buildModel1SystemPrompt() {
        return """
                당신은 한국어 커뮤니케이션 분석 전문가입니다.
                주어진 원문과 메타데이터를 분석하여 상황, 핵심 의도, 감정적 뉘앙스를 파악합니다.

                반드시 아래 JSON 형식으로만 응답하세요:
                {
                  "situationAnalysis": "현재 상황에 대한 2-3문장 분석",
                  "coreIntent": "화자가 전달하고자 하는 핵심 의도 1문장",
                  "emotionalNuances": ["감정1", "감정2"]
                }""";
    }

    public String buildModel1UserMessage(Persona persona, List<SituationContext> contexts,
                                          ToneLevel toneLevel, String maskedText,
                                          String userPrompt, String senderInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("받는 사람: ").append(promptBuilder.getPersonaLabel(persona)).append("\n");
        sb.append("상황: ").append(contexts.stream()
                .map(promptBuilder::getContextLabel)
                .collect(Collectors.joining(", "))).append("\n");
        sb.append("말투 강도: ").append(promptBuilder.getToneLabel(toneLevel)).append("\n");
        if (senderInfo != null && !senderInfo.isBlank()) {
            sb.append("보내는 사람: ").append(senderInfo).append("\n");
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("참고 맥락: ").append(userPrompt).append("\n");
        }
        sb.append("\n원문:\n").append(maskedText);
        return sb.toString();
    }

    // ===== Model 2: Inappropriate Expression Detection =====

    public String buildModel2SystemPrompt() {
        return """
                당신은 한국어 비즈니스 커뮤니케이션 검수 전문가입니다.
                원문에서 받는 사람에게 부적절하거나 화자에게 불리한 표현을 찾아냅니다.

                검출 대상:
                - 직접적/공격적 표현 ("못 해요", "왜 안 돼요")
                - 화자에게 불리한 사유 (음주, 유흥, 개인 일탈)
                - 체념/불만 표현 ("어쩔 수 없다", "억울하다")
                - 비꼬거나 책임회피하는 표현
                - 과도한 TMI (받는 사람이 알 필요 없는 사적 사유)

                반드시 아래 JSON 형식으로만 응답하세요:
                {
                  "inappropriate": [
                    {"keyword": "원문 속 해당 표현", "reason": "부적절한 이유", "suggestion": "대체 제안"}
                  ]
                }
                부적절한 표현이 없으면 {"inappropriate": []} 을 반환하세요.""";
    }

    public String buildModel2UserMessage(Persona persona, String maskedText) {
        return "받는 사람: " + promptBuilder.getPersonaLabel(persona) + "\n\n원문:\n" + maskedText;
    }

    // ===== Model 3: Required Expression Detection =====

    public String buildModel3SystemPrompt() {
        return """
                당신은 한국어 비즈니스 매너 전문가입니다.
                원문의 상황에 맞는 필수 표현을 적극적으로 제안합니다. 원문에 없더라도 상황상 꼭 포함되어야 할 표현을 추가하세요.

                고려 사항:
                - 비즈니스 인사/마무리 표현 (감사합니다, 부탁드립니다)
                - 상황별 필수 쿠션어 (사과 → 죄송합니다, 요청 → 혹시 가능할까요)
                - 관계별 예의 표현 (교수 → 올림, 상사 → 드림)
                - 공감/배려 표현

                반드시 아래 JSON 형식으로만 응답하세요:
                {
                  "required": [
                    {"keyword": "필수 표현", "position": "opening/middle/closing", "reason": "포함 이유"}
                  ]
                }
                필수 표현이 없으면 {"required": []} 을 반환하세요.""";
    }

    public String buildModel3UserMessage(Persona persona, List<SituationContext> contexts,
                                          ToneLevel toneLevel, String maskedText) {
        StringBuilder sb = new StringBuilder();
        sb.append("받는 사람: ").append(promptBuilder.getPersonaLabel(persona)).append("\n");
        sb.append("상황: ").append(contexts.stream()
                .map(promptBuilder::getContextLabel)
                .collect(Collectors.joining(", "))).append("\n");
        sb.append("말투 강도: ").append(promptBuilder.getToneLabel(toneLevel)).append("\n");
        sb.append("\n원문:\n").append(maskedText);
        return sb.toString();
    }

    // ===== Final Model: Transform with intermediate analysis =====

    public String buildFinalSystemPrompt(Persona persona, List<SituationContext> contexts,
                                          ToneLevel toneLevel) {
        return promptBuilder.buildSystemPrompt(persona, contexts, toneLevel);
    }

    public String buildFinalUserMessage(Persona persona, List<SituationContext> contexts,
                                         ToneLevel toneLevel, String maskedText,
                                         String userPrompt, String senderInfo,
                                         String model1Result, String model2Result,
                                         String model3Result) {
        String contextStr = contexts.stream()
                .map(promptBuilder::getContextLabel)
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("[전체 변환]\n");
        sb.append("받는 사람: ").append(promptBuilder.getPersonaLabel(persona)).append("\n");
        sb.append("상황: ").append(contextStr).append("\n");
        sb.append("말투 강도: ").append(promptBuilder.getToneLabel(toneLevel)).append("\n");

        if (senderInfo != null && !senderInfo.isBlank()) {
            sb.append("보내는 사람: ").append(senderInfo).append("\n");
        }

        // Inject intermediate analysis
        sb.append("\n--- 사전 분석 결과 ---\n");
        sb.append("[상황 분석]\n").append(model1Result).append("\n\n");
        sb.append("[부적절 표현 검출]\n").append(model2Result).append("\n\n");
        sb.append("[필수 표현 검출]\n").append(model3Result).append("\n");
        sb.append("--- 사전 분석 끝 ---\n\n");

        sb.append("원문: ").append(maskedText);

        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("\n참고 맥락: ").append(userPrompt);
        }

        return sb.toString();
    }
}
