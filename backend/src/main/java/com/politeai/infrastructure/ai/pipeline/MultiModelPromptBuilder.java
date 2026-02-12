package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.LockedSpan;
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
 * - Model 1: Situation/context analysis (objective fact decomposition)
 * - Model 2: Locked expression extraction (semantic spans AI identifies)
 * - Model 3: Speaker intent/purpose analysis (subjective perspective)
 * - Final Model: Transform using intermediate analysis (deconstruct → reassemble)
 */
@Component
@RequiredArgsConstructor
public class MultiModelPromptBuilder {

    private final PromptBuilder promptBuilder;

    // ===== Model 1: Situation/Context Analysis (Objective) =====

    public String buildModel1SystemPrompt() {
        return """
                당신은 한국어 커뮤니케이션 상황 분석 전문가입니다.
                주어진 원문과 메타데이터를 기반으로 상황을 객관적으로 분해합니다.
                감정, 의도, 주관적 해석은 하지 마세요. 사실만 분석하세요.

                반드시 아래 JSON 형식으로만 응답하세요:
                {
                  "situation": "현재 상황에 대한 객관적 사실 기술 2-3문장",
                  "relationship": "화자와 청자의 관계 및 역학 1-2문장",
                  "factualContext": ["사실1", "사실2", "사실3"],
                  "communicationChannel": "추정되는 소통 채널 (이메일/메시지/공식문서 등)"
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

    // ===== Model 2: Locked Expression Extraction =====

    public String buildModel2SystemPrompt() {
        return """
                당신은 텍스트에서 변경 불가능한 고유 표현을 추출하는 전문가입니다.
                정규식으로 잡을 수 없는 고유명사, 파일명, 프로젝트명, 사람 이름, 주소, 제품명 등을 찾습니다.

                이미 마스킹된 {{LOCKED_N}} 플레이스홀더는 무시하세요.
                날짜, 시간, 전화번호, 이메일, URL, 금액 등은 이미 처리되었으므로 제외하세요.

                반드시 아래 JSON 형식으로만 응답하세요:
                {
                  "lockedExpressions": [
                    {"text": "원문 속 정확한 표현", "type": "NAME|FILENAME|PROJECT|PRODUCT|ADDRESS|ORGANIZATION|CUSTOM", "reason": "변경 불가 이유"}
                  ]
                }
                변경 불가 표현이 없으면 {"lockedExpressions": []} 을 반환하세요.""";
    }

    public String buildModel2UserMessage(Persona persona, String maskedText) {
        return "받는 사람: " + promptBuilder.getPersonaLabel(persona) + "\n\n원문:\n" + maskedText;
    }

    // ===== Model 3: Speaker Intent/Purpose Analysis (Subjective) =====

    public String buildModel3SystemPrompt() {
        return """
                당신은 화자의 심리와 의도를 파악하는 커뮤니케이션 분석가입니다.
                원문에서 화자가 진정으로 전달하고 싶은 것, 감정 상태, 숨겨진 의도를 분석합니다.
                이것은 객관적 사실 분석이 아닌, 화자의 주관적 관점에서의 해석입니다.

                반드시 아래 JSON 형식으로만 응답하세요:
                {
                  "primaryIntent": "화자의 핵심 목적/의도 1문장",
                  "emotionalState": ["현재 감정1", "감정2"],
                  "underlyingNeeds": "표면적으로 드러나지 않는 화자의 진짜 욕구/필요 1-2문장",
                  "desiredOutcome": "화자가 이 메시지를 통해 얻고 싶은 결과 1문장",
                  "toneImplications": "원문의 어투가 암시하는 화자의 심리 상태 1문장"
                }""";
    }

    public String buildModel3UserMessage(Persona persona, List<SituationContext> contexts,
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

    // ===== Final Model: Transform with intermediate analysis =====

    private static final String FINAL_CORE_SYSTEM_PROMPT = """
            당신은 한국어 커뮤니케이션 전문가입니다. 사전 분석 결과를 활용하여 화자의 메시지를 재구성합니다.

            ## 핵심 원칙
            1. **사전 분석 신뢰**: 사전 분석에서 제공된 상황 분석, 화자 의도, 보존 표현을 그대로 반영하세요.
               자체적으로 상황을 재해석하거나 추가 분석하지 마세요.
            2. **해체 → 재조립**: 원문을 그대로 다듬는 것이 아닙니다.
               사전 분석의 사실 관계(상황 분석)와 화자 의도를 기반으로 메시지를 처음부터 새로 구성하세요.
               원문의 부적절한 표현, 구조, 어순에 얽매이지 마세요.
            3. **관점 유지**: 화자/청자를 정확히 구분. 화자의 관점을 절대 벗어나지 마세요.
            4. **고정 표현 절대 보존**: {{LOCKED_N}} 플레이스홀더와 고정 표현 매핑에 명시된 모든 표현은
               절대 수정/삭제/추가하지 마세요. 위치만 자연스럽게 조정 가능.
            5. **부적절 내용 완전 제거**: 원문의 부적절한 표현은 재조립 과정에서 자연스럽게 제외하세요.
               - 음주/유흥/개인 일탈 → "개인 사정"/"불가피한 사유" 등으로 대체하거나 삭제
               - 체념/불만("어쩔 수 없다") → 삭제하고 건설적 표현으로 전환
               - TMI → 핵심 사실만 남기고 삭제
            6. **자연스러움**: "드리겠습니다" 연속 2회 이상 금지. 어미를 다양하게. 기계적 패턴 금지.

            ## 사전 분석 활용법
            - [상황 분석]: 메시지의 전체 맥락과 톤을 결정하는 기초. 여기서 파악된 관계/상황에 맞게 재구성.
            - [보존 필수 표현]: 나열된 고유명사/이름/파일명은 반드시 원형 그대로 사용.
            - [화자 의도]: 화자가 진정 전달하고 싶은 핵심. 이 의도가 메시지의 중심이 되어야 함.
              표면적 표현보다 이 의도를 정확히 전달하는 것이 우선.

            ## 출력 규칙 (절대 준수)
            - 변환된 메시지 텍스트만 출력하세요. 분석/설명/이모지/메타 발언 절대 금지.
            - 첫 글자부터 바로 변환된 메시지. 앞뒤 부연 금지.
            - 문단 사이에만 줄바꿈(\\n\\n). 문단 내 문장은 줄바꿈 없이 이어서 작성. 한 문단 최대 4문장.
            - 원문 길이에 비례하는 자연스러운 분량.""";

    public String buildFinalSystemPrompt(Persona persona, List<SituationContext> contexts,
                                          ToneLevel toneLevel) {
        return promptBuilder.buildDynamicBlocks(FINAL_CORE_SYSTEM_PROMPT, persona, contexts, toneLevel);
    }

    public String buildFinalUserMessage(Persona persona, List<SituationContext> contexts,
                                         ToneLevel toneLevel, String maskedText,
                                         String userPrompt, String senderInfo,
                                         String model1Result, String model2Result,
                                         String model3Result,
                                         List<LockedSpan> allLockedSpans) {
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
        sb.append("[상황 분석 (객관적 사실)]\n").append(model1Result).append("\n\n");
        sb.append("[보존 필수 표현]\n").append(model2Result).append("\n\n");
        sb.append("[화자 의도 (주관적)]\n").append(model3Result).append("\n\n");

        // Locked span mapping table
        if (allLockedSpans != null && !allLockedSpans.isEmpty()) {
            sb.append("[고정 표현 매핑]\n");
            for (LockedSpan span : allLockedSpans) {
                sb.append(span.placeholder()).append(" = ").append(span.originalText()).append("\n");
            }
        }

        sb.append("--- 사전 분석 끝 ---\n\n");

        sb.append("원문: ").append(maskedText);

        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("\n참고 맥락: ").append(userPrompt);
        }

        return sb.toString();
    }
}
