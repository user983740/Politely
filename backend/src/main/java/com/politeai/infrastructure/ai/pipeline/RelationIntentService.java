package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import com.politeai.infrastructure.ai.PromptBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Optional LLM gating component: analyzes relationship and intent for complex/ambiguous messages.
 * Returns 3 fields (relation, intent, stance) each as a single sentence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelationIntentService {

    private final AiTransformService aiTransformService;
    private final PromptBuilder promptBuilder;

    private static final String MODEL = "gpt-4o-mini";
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 200;

    public record RelationIntentResult(
            String relation,
            String intent,
            String stance,
            long promptTokens,
            long completionTokens
    ) {}

    private static final String SYSTEM_PROMPT = """
            당신은 한국어 커뮤니케이션 분석 전문가입니다.
            원문과 메타데이터를 분석하여 관계, 의도, 태도를 각각 1문장으로 요약합니다.

            ## 출력 형식 (정확히 3줄)
            관계: [화자-청자 관계 설명 1문장]
            의도: [화자의 핵심 목적 1문장]
            태도: [화자가 취하려는/취해야 할 태도 1문장]

            근거 없는 추측 금지. 원문에서 직접 읽히는 것만.""";

    public RelationIntentResult analyze(Persona persona, List<SituationContext> contexts,
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

        LlmCallResult result = aiTransformService.callOpenAIWithModel(
                MODEL, SYSTEM_PROMPT, sb.toString(), TEMPERATURE, MAX_TOKENS, null);

        return parseResult(result);
    }

    private RelationIntentResult parseResult(LlmCallResult result) {
        String relation = "";
        String intent = "";
        String stance = "";

        if (result.content() != null) {
            for (String line : result.content().split("\n")) {
                line = line.trim();
                if (line.startsWith("관계:")) {
                    relation = line.substring("관계:".length()).trim();
                } else if (line.startsWith("의도:")) {
                    intent = line.substring("의도:".length()).trim();
                } else if (line.startsWith("태도:")) {
                    stance = line.substring("태도:".length()).trim();
                }
            }
        }

        return new RelationIntentResult(relation, intent, stance,
                result.promptTokens(), result.completionTokens());
    }
}
