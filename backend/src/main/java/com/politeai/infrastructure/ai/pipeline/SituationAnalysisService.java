package com.politeai.infrastructure.ai.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.ResponseFormatJsonObject;
import com.politeai.domain.transform.model.*;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import com.politeai.infrastructure.ai.PromptBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Situation analysis: extracts objective facts and core intent from unmasked original text.
 * Outputs structured JSON (facts + intent).
 * Provides Final model with accurate context to reduce hallucination from segmented input.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SituationAnalysisService {

    private final AiTransformService aiTransformService;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    private static final String MODEL = "gpt-4o-mini";
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 500;

    public record Fact(String content, String source) {}

    public record SituationAnalysisResult(
            List<Fact> facts,
            String intent,
            long promptTokens,
            long completionTokens
    ) {}

    private static final String SYSTEM_PROMPT = """
            당신은 한국어 메시지 상황 분석 전문가입니다.
            원문과 메타데이터를 분석하여 객관적 사실(facts)과 화자의 핵심 목적(intent)을 추출합니다.

            ## 규칙
            1. facts: 원문에서 직접 읽히는 객관적 사실만 추출 (최대 5개)
            2. 각 fact의 content: 사실을 명확한 1문장으로 요약
            3. 각 fact의 source: 해당 사실의 근거가 되는 원문 구절을 **정확히 인용** (변형 금지)
            4. intent: 화자의 핵심 전달 목적을 1~2문장으로 요약
            5. 지시대명사("그거", "이것", "저기") → 원문 맥락에서 해석하여 구체적 대상으로 복원
            6. 생략된 주어 → 문맥에서 추론하여 복원
            7. `{{TYPE_N}}` 형식 플레이스홀더(예: {{DATE_1}}, {{PHONE_1}})는 그대로 유지
            8. 근거 없는 추측 금지. 원문에서 직접 읽히는 것만

            ## 출력 형식 (JSON만, 다른 텍스트 금지)
            {
              "facts": [
                {"content": "사실 요약", "source": "원문 그대로 인용"},
                ...
              ],
              "intent": "화자의 핵심 목적"
            }

            ## 예시

            입력:
            받는 사람: 학부모
            상황: 피드백
            원문:
            아이가 수학 시험에서 {{UNIT_NUMBER_1}} 맞았는데 그거 반 평균보다 낮은 거잖아요. 선생님이 보충수업 해주신다고 했는데 아직 연락이 없어서요.

            출력:
            {
              "facts": [
                {"content": "아이의 수학 시험 점수가 {{UNIT_NUMBER_1}}이다", "source": "아이가 수학 시험에서 {{UNIT_NUMBER_1}} 맞았는데"},
                {"content": "아이의 점수가 반 평균보다 낮다", "source": "그거 반 평균보다 낮은 거잖아요"},
                {"content": "선생님이 보충수업을 해주기로 했으나 아직 연락이 없다", "source": "선생님이 보충수업 해주신다고 했는데 아직 연락이 없어서요"}
              ],
              "intent": "보충수업 일정을 확인하고, 아이의 성적 개선을 위한 후속 조치를 요청하려는 목적"
            }""";

    public SituationAnalysisResult analyze(Persona persona, List<SituationContext> contexts,
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

        try {
            LlmCallResult result = aiTransformService.callOpenAIWithModel(
                    MODEL, SYSTEM_PROMPT, sb.toString(), TEMPERATURE, MAX_TOKENS,
                    ResponseFormatJsonObject.builder().build());

            return parseResult(result);
        } catch (Exception e) {
            log.warn("[SituationAnalysis] LLM call failed, returning empty result: {}", e.getMessage());
            return new SituationAnalysisResult(List.of(), "", 0, 0);
        }
    }

    private static final Pattern KOREAN_WORD_PATTERN = Pattern.compile("[가-힣]{2,}");
    private static final Set<String> STOPWORDS = Set.of(
            "그리고", "하지만", "그래서", "때문에", "그런데", "그러나", "또한", "이런", "저런", "그런",
            "이것", "저것", "그것", "여기", "거기", "저기", "우리", "너희", "이번", "다음"
    );

    /**
     * Normalize text for fuzzy matching: keep only Korean, alphanumeric, lowercase.
     */
    private static String normalizeForMatch(String text) {
        return text.replaceAll("[^가-힣a-zA-Z0-9]", "").toLowerCase();
    }

    /**
     * Extract meaningful Korean words (2+ chars, excluding stopwords).
     */
    private static List<String> extractMeaningWords(String text) {
        List<String> words = new ArrayList<>();
        Matcher m = KOREAN_WORD_PATTERN.matcher(text);
        while (m.find()) {
            String word = m.group();
            if (!STOPWORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    /**
     * Filter out facts whose source overlaps with RED-labeled segments.
     * Prevents RED content from leaking into the Final model via situation analysis.
     *
     * Matching strategy (3-tier fallback):
     * 1. Exact indexOf → position-based overlap check
     * 2. Normalized contains → RED segment text contains normalized fact source
     * 3. Semantic word overlap → 2+ meaningful words from fact.source found in RED segment text
     */
    public SituationAnalysisResult filterRedFacts(SituationAnalysisResult original,
                                                   String maskedText,
                                                   List<LabeledSegment> labeledSegments) {
        List<LabeledSegment> redSegments = labeledSegments.stream()
                .filter(ls -> ls.label().tier() == SegmentLabel.Tier.RED)
                .toList();

        if (redSegments.isEmpty()) {
            return original;
        }

        List<Fact> filteredFacts = new ArrayList<>();
        for (Fact fact : original.facts()) {
            if (fact.source() == null || fact.source().isBlank()) {
                filteredFacts.add(fact);
                continue;
            }

            // Strategy 1: Exact indexOf with position-based overlap
            int factStart = maskedText.indexOf(fact.source());
            if (factStart >= 0) {
                int factEnd = factStart + fact.source().length();
                boolean overlapsRed = redSegments.stream()
                        .anyMatch(red -> factStart < red.end() && factEnd > red.start());
                if (overlapsRed) {
                    log.info("[SituationAnalysis] Filtered RED-overlapping fact (exact): {}", fact.content());
                    continue;
                }
                filteredFacts.add(fact);
                continue;
            }

            // Strategy 2: Normalized contains — check if any RED segment text contains the fact source
            String normalizedSource = normalizeForMatch(fact.source());
            if (!normalizedSource.isEmpty()) {
                boolean normalizedMatch = redSegments.stream()
                        .anyMatch(red -> normalizeForMatch(red.text()).contains(normalizedSource));
                if (normalizedMatch) {
                    log.info("[SituationAnalysis] Filtered RED-overlapping fact (normalized): {}", fact.content());
                    continue;
                }
            }

            // Strategy 3: Semantic word overlap — 2+ meaningful words from source found in RED segment
            List<String> sourceWords = extractMeaningWords(fact.source());
            if (sourceWords.size() >= 2) {
                boolean semanticMatch = redSegments.stream().anyMatch(red -> {
                    String redText = red.text();
                    long matchCount = sourceWords.stream().filter(redText::contains).count();
                    return matchCount >= 2;
                });
                if (semanticMatch) {
                    log.info("[SituationAnalysis] Filtered RED-overlapping fact (semantic): {}", fact.content());
                    continue;
                }
            }

            // No RED overlap detected — keep the fact
            filteredFacts.add(fact);
        }

        return new SituationAnalysisResult(
                filteredFacts, original.intent(),
                original.promptTokens(), original.completionTokens());
    }

    private SituationAnalysisResult parseResult(LlmCallResult result) {
        try {
            JsonNode root = objectMapper.readTree(result.content());

            List<Fact> facts = new ArrayList<>();
            JsonNode factsNode = root.path("facts");
            if (factsNode.isArray()) {
                for (JsonNode factNode : factsNode) {
                    String content = factNode.path("content").asText("");
                    String source = factNode.path("source").asText("");
                    if (!content.isEmpty()) {
                        facts.add(new Fact(content, source));
                    }
                }
            }

            String intent = root.path("intent").asText("");

            return new SituationAnalysisResult(facts, intent,
                    result.promptTokens(), result.completionTokens());
        } catch (Exception e) {
            log.warn("[SituationAnalysis] Parse failed: {}", e.getMessage());
            return new SituationAnalysisResult(List.of(), "",
                    result.promptTokens(), result.completionTokens());
        }
    }
}
