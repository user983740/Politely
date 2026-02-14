package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.infrastructure.ai.PromptBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds prompts for the v2 pipeline:
 * - Final Model: Transform using 3-tier labeled structure analysis
 */
@Component
@RequiredArgsConstructor
public class MultiModelPromptBuilder {

    private final PromptBuilder promptBuilder;

    // ===== Final Model: Transform with 3-tier structure analysis =====

    private static final String FINAL_CORE_SYSTEM_PROMPT = """
            당신은 한국어 커뮤니케이션 전문가입니다. 구조 분석 결과를 활용하여 화자의 메시지를 재구성합니다.

            ## 3계층 처리 규칙 (절대 준수)

            ### GREEN (보존) — 메시지의 뼈대
            - 구조 분석에서 CORE_FACT, CORE_INTENT, COURTESY로 분류된 항목
            - 반드시 포함하되, 경어/문체만 다듬으세요
            - 사실, 수치, 요청, 대안 등 핵심 정보를 변형하지 마세요

            ### YELLOW (수정) — [SOFTEN: ...] 마커로 표시된 부분
            - 담고 있는 사실과 상황 설명은 보존하되, 감정적/무례한/부적절한 표현만 제거하세요
            - 감정을 완전히 삭제하지 마세요. 전문적이고 절제된 어조로 바꾸세요 (예: "정신이 없었고" → "여유가 부족했습니다")
            - 말투는 받는 사람/상황/말투 강도 설정에 자연스럽게 맞추세요
            - [SOFTEN: ...] 마커는 출력에 포함하지 마세요

            ### RED (삭제) — [REDACTED:...] 마커로 표시된 부분
            - 서버에서 제거된 부적절한 내용. 원래 내용을 추측/복원하지 마세요
            - [REDACTED:...] 마커 자체도 출력에 포함하지 마세요
            - 제거된 내용을 우회하여 표현하거나 암시하지 마세요
            - RED 자리에 새 문장을 만들지 말고, 앞뒤 GREEN/YELLOW 항목을 자연스럽게 연결하세요
            - 연결 시 접속사("또한", "아울러")를 최소화하고, 문장 순서만 매끄럽게 이어주세요

            ## 핵심 원칙
            1. **해체 → 재조립**: 원문 그대로 다듬기가 아니라, GREEN 항목을 뼈대로 재조립. YELLOW는 감정/태도를 중화하되 담긴 사실 보존. RED는 무시.
            2. **원문 범위 엄수**: 원문에 없는 사실/약속/제안 추가 금지. 인사/호칭/서명은 허용.
            3. **간결한 연결**: RED 제거로 생긴 빈 자리를 자연스럽게 메우되 새 내용 추가 없이 앞뒤만 연결. GREEN 항목 사이의 접속사/중복 표현/수식어를 줄여 간결하게. 단, GREEN 핵심 사실/의도 자체는 생략 금지.
            4. **관점 유지**: 화자/청자 정확히 구분. 화자 관점 벗어나지 마세요.
            5. **고정 표현 절대 보존**: {{LOCKED_N}} 플레이스홀더 절대 수정/삭제/추가 금지.
            6. **자연스러움**: "드리겠습니다" 연속 2회 이상 금지. 어미 다양하게. 기계적 패턴 금지.
            7. **톤의 온도**: 말투는 persona와 toneLevel이 결정합니다. POLITE라도 관계에 맞는 자연스러운 공손함이지, 격식 문서체가 아닙니다.

            ## 출력 규칙 (절대 준수)
            - 변환된 메시지 텍스트만 출력. 분석/설명/이모지/메타 발언 절대 금지.
            - 첫 글자부터 바로 변환된 메시지. 앞뒤 부연 금지.
            - 문단 사이에만 줄바꿈(\\n\\n). 문단 내 문장은 줄바꿈 없이 이어서 작성. 한 문단 최대 4문장.
            - 원문 길이에 비례하는 자연스러운 분량.""";

    public String buildFinalSystemPrompt(Persona persona, List<SituationContext> contexts,
                                          ToneLevel toneLevel) {
        return promptBuilder.buildDynamicBlocks(FINAL_CORE_SYSTEM_PROMPT, persona, contexts, toneLevel);
    }

    public String buildFinalUserMessage(Persona persona, List<SituationContext> contexts,
                                         ToneLevel toneLevel, String senderInfo,
                                         List<LabeledSegment> labeledSegments,
                                         String processedText,
                                         List<LockedSpan> allLockedSpans,
                                         RelationIntentService.RelationIntentResult relationIntent,
                                         String summaryText) {
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

        // Optional: Relation/Intent analysis
        if (relationIntent != null && !relationIntent.relation().isEmpty()) {
            sb.append("\n--- 관계/의도 분석 ---\n");
            sb.append("관계: ").append(relationIntent.relation()).append("\n");
            sb.append("의도: ").append(relationIntent.intent()).append("\n");
            sb.append("태도: ").append(relationIntent.stance()).append("\n");
        }

        // Structure analysis results
        sb.append("\n--- 구조 분석 결과 ---\n");

        // GREEN segments
        List<LabeledSegment> greens = labeledSegments.stream()
                .filter(s -> s.label().tier() == SegmentLabel.Tier.GREEN)
                .toList();
        if (!greens.isEmpty()) {
            sb.append("[GREEN 보존 항목]\n");
            for (LabeledSegment seg : greens) {
                sb.append(seg.segmentId()).append("|").append(seg.label().name())
                        .append("|").append(seg.text()).append("\n");
            }
        }

        // YELLOW segments
        List<LabeledSegment> yellows = labeledSegments.stream()
                .filter(s -> s.label().tier() == SegmentLabel.Tier.YELLOW)
                .toList();
        if (!yellows.isEmpty()) {
            sb.append("\n[YELLOW 수정 항목] — 사실/의미 보존, 무례한 표현만 제거\n");
            for (LabeledSegment seg : yellows) {
                sb.append(seg.segmentId()).append("|").append(seg.label().name())
                        .append("|").append(seg.text()).append("\n");
            }
        }

        // RED summary (count only)
        long redCount = labeledSegments.stream()
                .filter(s -> s.label().tier() == SegmentLabel.Tier.RED)
                .count();
        if (redCount > 0) {
            sb.append("\n[RED 제거됨] — ").append(redCount).append("건 제거 (복원 금지)\n");
        }

        // Optional: Summary
        if (summaryText != null && !summaryText.isBlank()) {
            sb.append("\n[요약]: ").append(summaryText).append("\n");
        }

        // Locked span mapping
        if (allLockedSpans != null && !allLockedSpans.isEmpty()) {
            sb.append("\n[고정 표현 매핑]\n");
            for (LockedSpan span : allLockedSpans) {
                sb.append(span.placeholder()).append(" = ").append(span.originalText()).append("\n");
            }
        }

        // Processed text (with RED=[REDACTED:...], YELLOW=[SOFTEN: ...])
        sb.append("\n[변환 대상 텍스트]\n").append(processedText).append("\n");

        return sb.toString();
    }
}
