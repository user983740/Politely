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
import java.util.Set;
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

            출력 형식: 줄당 <SEG_ID>|<LABEL>
            세그먼트 텍스트는 서버가 이미 보유하고 있으므로 출력하지 마세요.

            ## 라벨링의 목적

            부적절한 **내용**을 검출하는 것입니다. 단어나 표현이 아닌, **그 내용을 이 관계(받는 사람)에게 전달해도 되는가?**로 판단하세요.

            - **RED**: 내용 자체가 부적절 — 아무리 표현을 다듬어도 이 관계에서 전달하면 안 되는 것. 완전 삭제
            - **YELLOW**: 내용은 필요하지만 표현이 부적절 — 객관적 사실을 재구성해서 순화하거나 돌려 말해야 함
            - **GREEN**: 내용도 표현도 적절 — 문체만 다듬기

            ## 3계층 라벨 체계

            ### GREEN (보존) — "이걸 빼면 받는 사람이 용건을 모른다"
            - CORE_FACT: 핵심 사실 (기한, 수치, 상태, 결과, 구체적 행동 계획)
            - CORE_INTENT: 핵심 요청/목적 (부탁, 제안, 대안, 질문)
            - COURTESY: 관례적 예의 (인사, 감사, 간결한 사과)

            ### YELLOW (수정) — "내용은 필요하지만 표현을 순화해야 함"
            - EMOTIONAL: 감정 과잉. 감정의 사실적 핵심은 유지하되 절제된 표현으로
            - OUT_OF_SCOPE: 사적 정보나 범위 초과 발언이지만 빠지면 맥락이 끊김. 핵심만 남기고 순화
            - SPECULATION: 추측, 가정이지만 전달 의도는 유효

            ### RED (삭제) — "이 내용 자체를 이 관계에서 전달하면 안 됨"
            - BLAME: 빠져도 용건 전달에 지장 없는 책임 전가, 남 탓
            - SELF_DEFENSE: 잘못 인정하면서 즉시 다른 원인으로 전가하는 변명
              ※ 단순 사실 기반 사유 설명("서버 점검으로 지연됨")은 CORE_FACT
              ※ 변명이지만 빠지면 메시지 논리에 빈 구멍이 생기는 사실 포함 → YELLOW
            - PRIVATE_TMI: 받는 사람이 전혀 알 필요 없고 빠져도 맥락이 유지되는 사적 정보
              ※ 사적이지만 빠지면 이유 설명이 끊기는 맥락상 필요한 정보 → YELLOW(OUT_OF_SCOPE)
            - AGGRESSION: 비난, 도발, 공격적/비꼬는 발언
            - GRUMBLE: 이 관계에서 이 내용을 전달하는 것 자체가 부적절한 불만, 체념, 한탄

            ## 판단 프로세스

            각 세그먼트마다:
            1. 이 세그먼트의 **내용**을 받는 사람(persona)에게 전달해도 되는가?
               - 전달 자체가 부적절한 내용 (불만 표출, 공격, 책임 전가, 불필요한 TMI) → RED 후보
               - 전달은 해야 하지만 표현이 부적절 → YELLOW
               - 전달해도 되고 표현도 적절 → GREEN
            2. RED 후보 재확인: 이 내용을 통째로 빼면 메시지에 **논리적 빈 구멍**이 생기는가?
               - YES → YELLOW로 격하 (사실 기반 내용을 순화하여 보존)
               - NO → RED 확정

            ## 예시

            받는 사람: 직장 상사

            [세그먼트]
            T1: 과장님 보고서 제출이 늦어졌습니다
            T2: 사실 제 잘못도 있지만 디자인팀에서 자료를 너무 늦게 줘서요
            T3: 개인적으로 이사 준비도 있어서 정신이 없었고
            T4: 수정본은 내일 오전까지 제출하겠습니다
            T5: 솔직히 이런 일정이면 퀄리티가 나올 수가 없다고 봅니다
            T6: 늦어서 죄송합니다

            [정답]
            T1|CORE_FACT
            T2|SELF_DEFENSE
            T3|OUT_OF_SCOPE
            T4|CORE_INTENT
            T5|GRUMBLE
            T6|COURTESY

            [판단 근거]
            T2: 잘못 인정 후 즉시 타 팀 탓 전가 → 빼도 용건 전달 가능 → RED
            T3: 사적 사유(TMI)이지만 빠지면 "정신이 없었다"의 이유가 사라짐 → YELLOW로 격하. "개인 사정"으로 순화 가능
            T5: 상사에게 일정 불만을 전달하는 내용 자체가 부적절 → 표현을 바꿔도 전달하면 안 됨 → RED

            선택사항(마지막 줄): SUMMARY: 핵심 용건을 1-2문장으로 요약

            ## 필수 규칙
            - **모든 세그먼트에 반드시 라벨을 부여하세요.** 빠뜨리는 세그먼트가 없어야 합니다.
            - 대부분의 메시지에는 RED 또는 YELLOW 세그먼트가 존재합니다. 전부 GREEN이면 다시 확인하세요.
            - 판단 기준은 **단어가 아니라 내용**: "이 내용을 이 관계의 상대에게 전달해도 되는가?"
            - 정말 판단 불가능한 경우에만 CORE_FACT(GREEN)로 분류하세요.

            금지: {{LOCKED_N}} 수정""";

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

        log.debug("[StructureLabel] Raw LLM response:\n{}", result.content());

        List<LabeledSegment> labeled = parseOutput(result.content(), maskedText, segments);
        String summary = parseSummary(result.content());

        long totalPrompt = result.promptTokens();
        long totalCompletion = result.completionTokens();

        // Validate coverage
        if (!validateResult(labeled, maskedText, segments)) {
            // Identify which segments are missing labels
            Set<String> labeledIds = labeled.stream()
                    .map(LabeledSegment::segmentId)
                    .collect(Collectors.toSet());
            List<String> missingIds = segments.stream()
                    .map(Segment::id)
                    .filter(id -> !labeledIds.contains(id))
                    .toList();

            log.warn("[StructureLabel] Validation failed (parsed {} of {} labels, missing: {}), retrying once",
                    labeled.size(), segments.size(), missingIds);
            String retryMessage = userMessage + "\n\n[시스템 경고] 이전 응답에서 다음 세그먼트의 라벨이 누락되었습니다: " +
                    String.join(", ", missingIds) +
                    ". 모든 세그먼트에 라벨을 부여해주세요. 반드시 SEG_ID|LABEL 형식으로 줄마다 출력하세요. 코드블록이나 설명 없이 바로 출력하세요.";
            LlmCallResult retryResult = aiTransformService.callOpenAIWithModel(
                    MODEL, SYSTEM_PROMPT, retryMessage, TEMPERATURE, MAX_TOKENS, null);

            log.debug("[StructureLabel] Retry raw LLM response:\n{}", retryResult.content());

            List<LabeledSegment> retryLabeled = parseOutput(retryResult.content(), maskedText, segments);
            String retrySummary = parseSummary(retryResult.content());
            totalPrompt += retryResult.promptTokens();
            totalCompletion += retryResult.completionTokens();

            if (!retryLabeled.isEmpty()) {
                // Fill any still-missing segments with CORE_FACT default
                retryLabeled = fillMissingLabels(retryLabeled, segments);
                return new StructureLabelResult(retryLabeled, retrySummary, totalPrompt, totalCompletion);
            }

            // Both attempts failed — fallback: label all segments as CORE_FACT (GREEN)
            log.warn("[StructureLabel] Both attempts failed, falling back to all-GREEN labels for {} segments", segments.size());
            List<LabeledSegment> fallback = segments.stream()
                    .map(seg -> new LabeledSegment(seg.id(), SegmentLabel.CORE_FACT, seg.text()))
                    .toList();
            return new StructureLabelResult(fallback, retrySummary, totalPrompt, totalCompletion);
        }

        // Check for suspiciously all-GREEN labeling (when enough segments exist)
        if (segments.size() >= 4 && isAllGreen(labeled)) {
            log.warn("[StructureLabel] All {} segments labeled GREEN with {}+ segments — retrying with diversity nudge",
                    labeled.size(), segments.size());
            String diversityMessage = userMessage + "\n\n[시스템 경고] 모든 세그먼트를 GREEN으로 분류했습니다. " +
                    "각 세그먼트의 내용을 받는 사람과의 관계에서 다시 평가하세요: " +
                    "전달 자체가 부적절한 내용(불만, 책임 전가, 불필요한 TMI, 공격)이 정말 없는지, " +
                    "내용은 필요하지만 표현을 순화해야 할 것은 없는지 확인하세요.";
            LlmCallResult diversityResult = aiTransformService.callOpenAIWithModel(
                    MODEL, SYSTEM_PROMPT, diversityMessage, TEMPERATURE, MAX_TOKENS, null);

            log.debug("[StructureLabel] Diversity retry response:\n{}", diversityResult.content());

            List<LabeledSegment> diversityLabeled = parseOutput(diversityResult.content(), maskedText, segments);
            String diversitySummary = parseSummary(diversityResult.content());
            totalPrompt += diversityResult.promptTokens();
            totalCompletion += diversityResult.completionTokens();

            if (!diversityLabeled.isEmpty()) {
                boolean hasNonGreen = diversityLabeled.stream()
                        .anyMatch(s -> s.label().tier() != SegmentLabel.Tier.GREEN);
                if (hasNonGreen) {
                    diversityLabeled = fillMissingLabels(diversityLabeled, segments);
                    return new StructureLabelResult(diversityLabeled,
                            diversitySummary != null ? diversitySummary : summary,
                            totalPrompt, totalCompletion);
                }
            }
            log.info("[StructureLabel] Diversity retry also all-GREEN — accepting original result");
        }

        // Fill any missing segments with CORE_FACT default (even if validation passed)
        labeled = fillMissingLabels(labeled, segments);

        return new StructureLabelResult(labeled, summary, totalPrompt, totalCompletion);
    }

    private boolean isAllGreen(List<LabeledSegment> labeled) {
        return labeled.stream().allMatch(s -> s.label().tier() == SegmentLabel.Tier.GREEN);
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
     * Parse LLM output lines: SEG_ID|LABEL (2-column format).
     * Text is always looked up from the original segments by ID,
     * ensuring exact match with maskedText for reliable redaction.
     */
    private List<LabeledSegment> parseOutput(String output, String maskedText, List<Segment> segments) {
        List<LabeledSegment> result = new ArrayList<>();
        if (output == null || output.isBlank()) return result;

        // Build segment lookup map
        java.util.Map<String, Segment> segmentMap = segments.stream()
                .collect(Collectors.toMap(Segment::id, s -> s, (a, b) -> a));

        // Strip markdown code blocks (```...```)
        String cleaned = output.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.strip();
        }

        for (String line : cleaned.split("\n")) {
            line = line.trim();
            if (line.startsWith("SUMMARY:")) continue;
            if (line.isEmpty()) continue;
            if (line.startsWith("```") || line.startsWith("#") || line.startsWith("---")) continue;
            if (!line.contains("|")) continue;

            String[] parts = line.split("\\|", 3);
            if (parts.length < 2) continue;

            String rawSegId = parts[0].trim();
            String labelStr = parts[1].trim();

            // Normalize segId: strip markdown bold (**T1** → T1), leading dash/bullet
            String segId = rawSegId.replaceAll("\\*\\*", "").replaceAll("^[-•*]\\s*", "").trim();

            try {
                SegmentLabel label = SegmentLabel.valueOf(labelStr);

                // Always use text from the original segment (not LLM output)
                Segment seg = segmentMap.get(segId);
                if (seg != null) {
                    result.add(new LabeledSegment(segId, label, seg.text()));
                } else {
                    log.debug("[StructureLabel] Unknown segment ID '{}', skipping", segId);
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

    /**
     * Fill in missing segments with CORE_FACT (GREEN) default label.
     * Ensures every segment from MeaningSegmenter has a corresponding label.
     */
    private List<LabeledSegment> fillMissingLabels(List<LabeledSegment> labeled, List<Segment> segments) {
        Set<String> labeledIds = labeled.stream()
                .map(LabeledSegment::segmentId)
                .collect(Collectors.toSet());

        List<LabeledSegment> missing = segments.stream()
                .filter(seg -> !labeledIds.contains(seg.id()))
                .map(seg -> new LabeledSegment(seg.id(), SegmentLabel.CORE_FACT, seg.text()))
                .toList();

        if (!missing.isEmpty()) {
            log.warn("[StructureLabel] {} segments missing labels, defaulting to CORE_FACT: {}",
                    missing.size(), missing.stream().map(LabeledSegment::segmentId).toList());
            List<LabeledSegment> combined = new ArrayList<>(labeled);
            combined.addAll(missing);
            return combined;
        }

        return labeled;
    }

    private boolean validateResult(List<LabeledSegment> labeled, String maskedText, List<Segment> segments) {
        if (labeled.isEmpty()) return false;

        // Check segment count coverage (not text length)
        double coverage = (double) labeled.size() / segments.size();
        if (coverage < MIN_COVERAGE) {
            log.warn("[StructureLabel] Low segment coverage: {} of {} ({:.0f}%, min: {}%)",
                    labeled.size(), segments.size(), coverage * 100, MIN_COVERAGE * 100);
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
