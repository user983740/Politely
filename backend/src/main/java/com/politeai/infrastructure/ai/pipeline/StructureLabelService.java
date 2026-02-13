package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import com.politeai.infrastructure.ai.PromptBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM-based structure labeling service (LLM call #1 in the new pipeline).
 *
 * Sends segments + metadata to gpt-4o-mini and receives 3-tier labels
 * (GREEN/YELLOW/RED) for each segment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructureLabelService {

    private final AiTransformService aiTransformService;
    private final PromptBuilder promptBuilder;

    private static final String MODEL = "gpt-4o-mini";
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 800;
    private static final double MIN_COVERAGE = 0.6;

    public record StructureLabelResult(
            List<LabeledSegment> labeledSegments,
            String summaryText,
            long promptTokens,
            long completionTokens
    ) {}

    private static final String SYSTEM_PROMPT = """
            역할: 한국어 텍스트 구조 분석 전문가
            입력: 메타데이터 + 마스킹된 원문 + 서버 세그먼트 목록

            출력 형식: 줄당 <SEG_ID>|<LABEL>|<EXACT_SUBSTRING>

            ## 3계층 라벨 체계

            ### GREEN (보존) — 메시지의 뼈대. 반드시 포함, 문체만 다듬음.
            - CORE_FACT: 핵심 사실 전달 (기한, 수치, 상태, 결과 등)
            - CORE_INTENT: 핵심 요청/목적 (부탁, 제안, 대안, 질문 등)
            - COURTESY: 인사, 감사, 사과 등 관례적 예의 표현

            ### YELLOW (수정) — 사실/의미는 살리되 무례하거나 부적절한 표현만 제거.
            - EMOTIONAL: 감정 과잉 표현 (분노, 좌절, 불안 등이 과하게 드러남)
            - OUT_OF_SCOPE: 화자 역할/권한 범위를 벗어나는 언급 (but 맥락상 필요)
            - SPECULATION: 추측, 가정, 미확인 정보 (but 전달 의도는 유효)

            ### RED (삭제) — 정보 가치가 없는 항목만. 최종본에서 완전히 제거. 서버가 집행.
            - BLAME: 책임 전가, 남 탓 (예: "영업팀이 늦게 줘서")
            - SELF_DEFENSE: 변명, 선제 방어 (예: "저는 분명히 미리 말씀드렸는데")
              ※ 사실 기반 이유 설명은 CORE_FACT로 분류
              ※ 변명이지만 상대방이 알아야 할 객관적 조치 내역이 포함되어 있으면 YELLOW
            - PRIVATE_TMI: 사적 사유, 받는 사람이 알 필요 없는 개인 정보
            - AGGRESSION: 비난, 도발, 공격적/비꼬는 표현
            - GRUMBLE: 체념, 투덜거림, 한탄 ("어쩔 수 없다", "억울하다")

            ## 판단 기준
            1. "빠지면 용건 이해에 문제?" → GREEN (CORE_FACT/CORE_INTENT)
            2. "예의상 필요?" → GREEN (COURTESY)
            3. "의미는 필요하지만 표현이 부적절?" → YELLOW
            4. "빠져도 용건 전달에 지장 없고 위 RED 유형?" → RED
            5. "RED 후보이지만, 이 안에 빠지면 메시지에 빈 구멍이 생기는 사실이 있는가?" → YELLOW로 격하
               예: "걔가 안 쳐한거임" → BLAME이지만 "노력 부족"이라는 사실 포함 → YELLOW

            선택사항(마지막 줄): SUMMARY: 핵심 용건을 1-2문장으로 요약

            금지: EXACT_SUBSTRING 변형, {{LOCKED_N}} 수정""";

    public StructureLabelResult label(Persona persona,
                                      List<SituationContext> contexts,
                                      ToneLevel toneLevel,
                                      String userPrompt,
                                      String senderInfo,
                                      List<Segment> segments,
                                      String maskedText) {
        String userMessage = buildUserMessage(persona, contexts, toneLevel, userPrompt, senderInfo, segments, maskedText);

        LlmCallResult result = aiTransformService.callOpenAIWithModel(
                MODEL, SYSTEM_PROMPT, userMessage, TEMPERATURE, MAX_TOKENS, null);

        List<LabeledSegment> labeled = parseOutput(result.content(), maskedText, segments);
        String summary = parseSummary(result.content());

        // Validate coverage
        if (!validateResult(labeled, maskedText, segments)) {
            log.warn("[StructureLabel] Validation failed, retrying once");
            String retryMessage = userMessage + "\n\n[시스템 경고] 이전 응답의 커버리지가 부족합니다. 모든 세그먼트에 라벨을 부여해주세요.";
            LlmCallResult retryResult = aiTransformService.callOpenAIWithModel(
                    MODEL, SYSTEM_PROMPT, retryMessage, TEMPERATURE, MAX_TOKENS, null);
            List<LabeledSegment> retryLabeled = parseOutput(retryResult.content(), maskedText, segments);
            String retrySummary = parseSummary(retryResult.content());

            return new StructureLabelResult(
                    retryLabeled, retrySummary,
                    result.promptTokens() + retryResult.promptTokens(),
                    result.completionTokens() + retryResult.completionTokens()
            );
        }

        return new StructureLabelResult(labeled, summary, result.promptTokens(), result.completionTokens());
    }

    private String buildUserMessage(Persona persona, List<SituationContext> contexts,
                                     ToneLevel toneLevel, String userPrompt, String senderInfo,
                                     List<Segment> segments, String maskedText) {
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

        sb.append("\n[서버 세그먼트]\n");
        for (Segment seg : segments) {
            sb.append(seg.id()).append(": ").append(seg.text()).append("\n");
        }

        sb.append("\n[마스킹된 원문]\n").append(maskedText);
        return sb.toString();
    }

    /**
     * Parse LLM output lines: SEG_ID|LABEL|EXACT_SUBSTRING
     */
    private List<LabeledSegment> parseOutput(String output, String maskedText, List<Segment> segments) {
        List<LabeledSegment> result = new ArrayList<>();
        if (output == null || output.isBlank()) return result;

        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("SUMMARY:")) continue;
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\|", 3);
            if (parts.length < 2) continue;

            String segId = parts[0].trim();
            String labelStr = parts[1].trim();
            String exactText = parts.length >= 3 ? parts[2].trim() : "";

            try {
                SegmentLabel label = SegmentLabel.valueOf(labelStr);

                // If exact text is empty, try to find it from segments
                if (exactText.isEmpty()) {
                    exactText = segments.stream()
                            .filter(s -> s.id().equals(segId))
                            .map(Segment::text)
                            .findFirst()
                            .orElse("");
                }

                // Validate the substring exists in masked text
                if (!exactText.isEmpty() && maskedText.contains(exactText)) {
                    result.add(new LabeledSegment(segId, label, exactText));
                } else if (!exactText.isEmpty()) {
                    // Accept anyway but log warning
                    log.debug("[StructureLabel] Substring not found in masked text for {}: '{}'", segId, exactText);
                    result.add(new LabeledSegment(segId, label, exactText));
                }
            } catch (IllegalArgumentException e) {
                log.debug("[StructureLabel] Unknown label '{}' for segment {}", labelStr, segId);
            }
        }

        return result;
    }

    private String parseSummary(String output) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("SUMMARY:")) {
                return line.substring("SUMMARY:".length()).trim();
            }
        }
        return null;
    }

    private boolean validateResult(List<LabeledSegment> labeled, String maskedText, List<Segment> segments) {
        if (labeled.isEmpty()) return false;

        // Check coverage
        int totalLabeledLength = labeled.stream().mapToInt(s -> s.text().length()).sum();
        double coverage = (double) totalLabeledLength / maskedText.length();
        if (coverage < MIN_COVERAGE) {
            log.warn("[StructureLabel] Low coverage: {:.2f} (min: {})", coverage, MIN_COVERAGE);
            return false;
        }

        // Check at least one CORE_FACT or CORE_INTENT
        boolean hasCoreGreen = labeled.stream().anyMatch(s ->
                s.label() == SegmentLabel.CORE_FACT || s.label() == SegmentLabel.CORE_INTENT);
        if (!hasCoreGreen) {
            log.warn("[StructureLabel] No CORE_FACT or CORE_INTENT found");
            return false;
        }

        return true;
    }
}
