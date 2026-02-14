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

            ## 라벨링의 목적 — 2축 분석

            각 세그먼트를 **내용 기능**(무엇을 전달하는가)과 **태도 문제**(전달 방식에 문제가 있는가)의 2축으로 분석합니다.
            라벨 = 재작성 전략입니다. 라벨이 결정되면 서버/최종 모델이 라벨별 차등 재작성을 수행합니다.

            - **GREEN**: 메시지에 꼭 필요한 내용. 원문 그대로 전달해도 되는 내용 — 문체만 다듬기
            - **YELLOW**: 필요한 내용이지만 원문 그대로 전달하면 안 됨 — 라벨별 차등 재작성 전략 적용
            - **RED**: 내용 자체가 불필요하고 해로움 — 완전 삭제

            ## 15개 라벨 체계

            ### GREEN (보존) — "이걸 빼면 받는 사람이 용건을 모른다"
            - CORE_FACT: 핵심 사실 (기한, 수치, 상태, 결과, 구체적 행동 계획). 어조가 거칠어도 내용 자체가 필요하면 GREEN
            - CORE_INTENT: 핵심 요청/목적 (부탁, 제안, 대안, 질문)
            - REQUEST: 명시적 요청/부탁 ("~해 주세요", "~하면 좋겠습니다")
            - APOLOGY: 사과/양해 구하기 ("죄송합니다", "양해 부탁드립니다")
            - COURTESY: 관례적 예의 (인사, 감사, 호칭)
            주의: 표현이 무례해도 내용이 필요하면 GREEN. 표현 교정은 최종 변환 모델의 몫

            ### YELLOW (수정) — "필요한 내용이지만 원문 그대로 전달하면 안 됨"
            각 YELLOW 라벨은 서로 다른 재작성 전략을 유발합니다:
            - ACCOUNTABILITY: 책임 소재 지적 — "네가 잘못했다"류. 사실 정보(누가, 무엇을, 왜)는 보존하되 주어 완화/수동형/사실 중심 중립 표현으로 재작성
            - SELF_EXPLAIN: 상황 설명/해명 — "제가 ~라서"류. 핵심 사유만 간결하게, 변명 어조 제거
            - NEGATIVE_FEEDBACK: 부정적 평가/피드백 — "퀄리티가 떨어진다"류. 문제→개선 방향 프레임 전환
            - EMOTIONAL: 감정 토로 — "너무 화가 난다", "멘탈이 나간다"류. 감정어 제거, 영향/결과 중심 서술
            - SPECULATION: 추측/가정 — "아마 ~일 것 같다"류. 단정 제거, 조건부/가능성 표현
            - OVER_EXPLANATION: 장황한 설명/불필요한 맥락 — 핵심 사유만 남기고 축약

            ### RED (삭제) — "내용 자체가 불필요하고 해로움"
            - AGGRESSION: 공격/비꼬기/도발 — "제대로 하는 게 있나", "그것도 모르나"
            - PERSONAL_ATTACK: 인신공격 — 상대 인격/능력 비하. "네가 무능해서", "내 탓하려고 시동건다"
            - PRIVATE_TMI: 받는 사람이 전혀 알 필요 없고 빠져도 맥락이 유지되는 사적 정보
            - PURE_GRUMBLE: 순수 넋두리/체념/한탄 — 용건과 완전히 무관한 불만

            ## ACCOUNTABILITY vs PERSONAL_ATTACK 분리 기준 (핵심)

            같은 "남 탓"이라도:
            - "고객님 서버 설정이 잘못되어 오류가 발생했습니다" → **ACCOUNTABILITY**: 사실 정보(원인) 포함. 삭제하면 상대가 원인을 모름
            - "고객님이 매번 이러니까 문제가 생기는 거예요" → **PERSONAL_ATTACK**: 사실 정보 없이 인격/행동 패턴 비난만

            핵심 테스트: "이 문장에서 책임 지적을 빼고 사실 정보만 남기면 뭐가 남는가?"
            - 남는 게 있으면 → ACCOUNTABILITY (YELLOW, 사실 보존 재작성)
            - 남는 게 없으면 → PERSONAL_ATTACK (RED, 삭제)

            ## SELF_EXPLAIN vs RED 판단

            같은 "자기 변호"라도:
            - "디자인팀 자료가 3일 늦게 와서 일정이 밀렸습니다" → **SELF_EXPLAIN**: 원인 정보 포함, 간결히 재작성 가능
            - "제 잘못이 아니에요 다들 그런 상황이면 똑같았을 거예요" → **PURE_GRUMBLE**: 사실 정보 없는 순수 자기방어/넋두리

            ## RED vs YELLOW 핵심 판단 기준

            **RED는 극도로 보수적으로 적용하세요.** 조금이라도 의심되면 YELLOW입니다.

            세그먼트 안에 다음 중 하나라도 포함되면 RED가 아닌 GREEN 또는 YELLOW:
            - **원인/이유 설명**: 왜 문제가 생겼는지, 왜 지연되었는지 등 원인 정보
            - **기술적/구체적 정보**: 파일명, 설정, 절차, 조치 내역 등 상대가 참고할 수 있는 구체적 정보
            - **행동 지침**: 상대가 앞으로 뭘 해야 하는지 또는 뭘 하면 안 되는지
            - **맥락 연결 고리**: 이걸 빼면 앞뒤 세그먼트의 연결이 끊기거나 "왜?"라는 질문이 생기는 내용

            불필요한 내용 + 필요한 정보가 섞인 세그먼트 → **YELLOW** (RED 불가)

            ## 판단 프로세스

            각 세그먼트마다:
            1. 이 세그먼트의 **내용**이 메시지에 필요한가?
               - YES → 2단계로
               - NO → 3단계로
            2. 원문 그대로 전달해도 되는 내용인가?
               - YES → **GREEN** (CORE_FACT/CORE_INTENT/REQUEST/APOLOGY/COURTESY 중 해당)
               - NO → **YELLOW** (ACCOUNTABILITY/SELF_EXPLAIN/NEGATIVE_FEEDBACK/EMOTIONAL/SPECULATION/OVER_EXPLANATION 중 해당)
            3. 이 세그먼트를 통째로 빼면 논리적 빈 구멍이 생기는가?
               - YES → **YELLOW**
               - NO → **RED** (AGGRESSION/PERSONAL_ATTACK/PRIVATE_TMI/PURE_GRUMBLE 중 해당)

            ## 예시 1

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
            T2|ACCOUNTABILITY
            T3|OVER_EXPLANATION
            T4|CORE_INTENT
            T5|PURE_GRUMBLE
            T6|APOLOGY

            [판단 근거]
            T2: "디자인팀 자료가 늦었다"는 지연 원인 정보 포함 → 사실 보존, 주어 완화 재작성 → ACCOUNTABILITY (YELLOW)
            T3: 사적 사유(이사)가 맥락 설명 역할을 하지만 장황 → 핵심만 남기고 축약 → OVER_EXPLANATION (YELLOW)
            T5: 순수 불만. 용건(보고서 제출)과 무관한 넋두리 → PURE_GRUMBLE (RED)
            T6: 사과 → APOLOGY (GREEN)

            ## 예시 2

            받는 사람: 고객

            [세그먼트]
            T1: 고객님
            T2: 솔직히 말씀드리면 이번 오류는 저희 쪽 문제라기보단 귀사 서버 설정이 이상해서 생긴거고 제가 지난번에도 config.yaml 건드리지 말라고 했는데
            T3: 또 수정하셔서 이 사단이 난거 같습니다
            T4: 아무튼 현재 연락처로 연락주셔도 되고 로그파일 보내주시면 제가 보겠습니다
            T5: 근데 저도 사람이라 하루종일 이런 대응하면 멘탈 좀 나가긴 합니다

            [정답]
            T1|COURTESY
            T2|ACCOUNTABILITY
            T3|NEGATIVE_FEEDBACK
            T4|CORE_INTENT
            T5|PURE_GRUMBLE

            [판단 근거]
            T2: 비난 어조이지만 오류 원인("서버 설정")과 행동 지침("config.yaml 건드리지 말 것")이라는 핵심 사실 정보 포함 → 사실 보존, 주어 완화 재작성 → ACCOUNTABILITY (YELLOW)
            T3: "또 수정하셔서 이 사단이 난거"는 부정적 평가이지만, T2의 경고와 현재 상황을 잇는 내용 → 문제→개선 프레임 전환 → NEGATIVE_FEEDBACK (YELLOW)
            T5: 순수 넋두리. 용건 무관, 사실 정보 없음 → PURE_GRUMBLE (RED)

            ## 예시 3

            받는 사람: 학부모

            [세그먼트]
            T1: 안녕하세요 어머니
            T2: 님 애가 시험을 망해서 놀라셨죠
            T3: 님 애가 안 쳐한거임
            T4: 솔직히 수업 시간에 만날 떠들어서 다른 애들한테도 피해줌
            T5: 내 탓하려고 시동건다
            T6: 아무튼 수학이랑 영어는 보충수업 들어야 할 것 같고요
            T7: 다음 주에 상담 한번 잡으면 좋겠습니다

            [정답]
            T1|COURTESY
            T2|CORE_FACT
            T3|NEGATIVE_FEEDBACK
            T4|NEGATIVE_FEEDBACK
            T5|PERSONAL_ATTACK
            T6|CORE_INTENT
            T7|REQUEST

            [판단 근거]
            T2: "시험을 망해서 놀라셨죠"는 거친 표현이지만, 시험 결과 + 학부모 반응이라는 내용 자체가 서론으로 필요 → GREEN (CORE_FACT)
            T3: "안 쳐한거임"은 학습 부진이라는 부정적 평가 → 문제→개선 프레임 전환 필요 → NEGATIVE_FEEDBACK (YELLOW)
            T4: 수업 태도에 대한 부정적 피드백 → 재구성 필요 → NEGATIVE_FEEDBACK (YELLOW)
            T5: "내 탓하려고 시동건다"는 사실 정보 없는 인신공격 → PERSONAL_ATTACK (RED)
            T7: "상담 잡으면 좋겠습니다"는 명시적 요청 → REQUEST (GREEN)

            선택사항(마지막 줄): SUMMARY: 핵심 용건을 1-2문장으로 요약

            ## 필수 규칙
            - **모든 세그먼트에 반드시 라벨을 부여하세요.** 빠뜨리는 세그먼트가 없어야 합니다.
            - **RED는 극도로 보수적으로.** 내용이 조금이라도 필요하면 GREEN 또는 YELLOW. 빼서 맥락이 끊기면 YELLOW.
            - **GREEN vs YELLOW**: 내용 자체를 바꿀 필요 없으면 GREEN, 재구성이 필요하면 YELLOW.
            - **YELLOW 라벨 선택**: 어떤 재작성 전략이 필요한지에 따라 6개 YELLOW 라벨 중 가장 적합한 것 선택.
            - RED 전 반드시 자문: "이걸 통째로 삭제하면 메시지가 여전히 이해되는가?"

            금지: {{TYPE_N}} 형식 플레이스홀더(예: {{DATE_1}}, {{PHONE_1}}) 수정""";

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

            // Both attempts failed — fallback: label all segments as COURTESY (GREEN)
            log.warn("[StructureLabel] Both attempts failed, falling back to all-COURTESY labels for {} segments", segments.size());
            List<LabeledSegment> fallback = segments.stream()
                    .map(seg -> new LabeledSegment(seg.id(), SegmentLabel.COURTESY, seg.text()))
                    .toList();
            return new StructureLabelResult(fallback, retrySummary, totalPrompt, totalCompletion);
        }

        // Check for suspiciously all-GREEN labeling (when enough segments exist)
        if (segments.size() >= 4 && isAllGreen(labeled)) {
            log.warn("[StructureLabel] All {} segments labeled GREEN with {}+ segments — retrying with diversity nudge",
                    labeled.size(), segments.size());
            String diversityMessage = userMessage + "\n\n[시스템 경고] 모든 세그먼트를 GREEN으로 분류했습니다. " +
                    "각 세그먼트의 내용을 받는 사람과의 관계에서 다시 평가하세요: " +
                    "완전히 불필요한 내용(공격, 인신공격, 순수 넋두리, 불필요한 TMI)이 정말 없는지, " +
                    "내용은 필요하지만 재구성이 필요한 것(책임 소재, 해명, 부정적 평가, 감정 토로, 추측, 장황한 설명)은 없는지 확인하세요.";
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
                    // Merge: use diversity labels for segments it labeled, fall back to original for the rest
                    diversityLabeled = fillMissingFromOriginal(diversityLabeled, labeled, segments);
                    return new StructureLabelResult(diversityLabeled,
                            diversitySummary != null ? diversitySummary : summary,
                            totalPrompt, totalCompletion);
                }
            }
            log.info("[StructureLabel] Diversity retry also all-GREEN — accepting original result");
        }

        // Fill any missing segments with COURTESY default (even if validation passed)
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

        // Track seen segment IDs to deduplicate (keep first occurrence only)
        Set<String> seenSegIds = new java.util.HashSet<>();

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

            // Deduplicate: skip if this segment ID was already labeled
            if (!seenSegIds.add(segId)) {
                log.debug("[StructureLabel] Duplicate segment ID '{}', keeping first occurrence", segId);
                continue;
            }

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
     * Fill in missing segments with COURTESY (GREEN) default label.
     * COURTESY is the safest GREEN label — model only adjusts style, no special preservation needed.
     * Ensures every segment from MeaningSegmenter has a corresponding label.
     */
    private List<LabeledSegment> fillMissingLabels(List<LabeledSegment> labeled, List<Segment> segments) {
        Set<String> labeledIds = labeled.stream()
                .map(LabeledSegment::segmentId)
                .collect(Collectors.toSet());

        List<LabeledSegment> missing = segments.stream()
                .filter(seg -> !labeledIds.contains(seg.id()))
                .map(seg -> new LabeledSegment(seg.id(), SegmentLabel.COURTESY, seg.text()))
                .toList();

        if (!missing.isEmpty()) {
            log.warn("[StructureLabel] {} segments missing labels, defaulting to COURTESY: {}",
                    missing.size(), missing.stream().map(LabeledSegment::segmentId).toList());
            List<LabeledSegment> combined = new ArrayList<>(labeled);
            combined.addAll(missing);
            return combined;
        }

        return labeled;
    }

    /**
     * Fill missing segments in diversity result using original labels first, COURTESY as last resort.
     * Prevents diversity retry from being defeated by auto-fill to all-GREEN.
     */
    private List<LabeledSegment> fillMissingFromOriginal(List<LabeledSegment> diversityLabeled,
                                                          List<LabeledSegment> originalLabeled,
                                                          List<Segment> segments) {
        Set<String> diversityIds = diversityLabeled.stream()
                .map(LabeledSegment::segmentId)
                .collect(Collectors.toSet());

        java.util.Map<String, LabeledSegment> originalMap = originalLabeled.stream()
                .collect(Collectors.toMap(LabeledSegment::segmentId, ls -> ls, (a, b) -> a));

        java.util.Map<String, Segment> segmentMap = segments.stream()
                .collect(Collectors.toMap(Segment::id, s -> s, (a, b) -> a));

        List<LabeledSegment> combined = new ArrayList<>(diversityLabeled);
        for (Segment seg : segments) {
            if (!diversityIds.contains(seg.id())) {
                // Prefer original label over default
                LabeledSegment original = originalMap.get(seg.id());
                if (original != null) {
                    combined.add(original);
                } else {
                    combined.add(new LabeledSegment(seg.id(), SegmentLabel.COURTESY, seg.text()));
                }
            }
        }
        return combined;
    }

    private boolean validateResult(List<LabeledSegment> labeled, String maskedText, List<Segment> segments) {
        if (labeled.isEmpty()) return false;
        if (segments.isEmpty()) return false;

        // Check segment count coverage (not text length)
        double coverage = (double) labeled.size() / segments.size();
        if (coverage < MIN_COVERAGE) {
            log.warn("[StructureLabel] Low segment coverage: {} of {} ({}%, min: {}%)",
                    labeled.size(), segments.size(), Math.round(coverage * 100), Math.round(MIN_COVERAGE * 100));
            return false;
        }

        // Check at least one CORE_FACT, CORE_INTENT, or REQUEST
        boolean hasCoreGreen = labeled.stream().anyMatch(s ->
                s.label() == SegmentLabel.CORE_FACT || s.label() == SegmentLabel.CORE_INTENT || s.label() == SegmentLabel.REQUEST);
        if (!hasCoreGreen) {
            log.warn("[StructureLabel] No CORE_FACT, CORE_INTENT, or REQUEST found");
            return false;
        }

        return true;
    }
}
