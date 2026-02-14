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
            - 구조 분석에서 CORE_FACT, CORE_INTENT, REQUEST, APOLOGY, COURTESY로 분류된 항목
            - 반드시 포함하되, 경어/문체만 다듬으세요
            - 사실, 수치, 요청, 대안 등 핵심 정보를 변형하지 마세요

            ### YELLOW (수정) — [SOFTEN:LABEL: ...] 마커로 표시된 부분
            ⚠️ YELLOW는 "내용이 필요하지만 전달 방식을 바꿔야 하는" 세그먼트입니다. 내용 자체를 축소/삭제하면 안 됩니다.
            라벨별로 차등 재작성 전략을 적용하세요:
            - [SOFTEN:ACCOUNTABILITY: ...] → 책임 소재: 인과관계(A→B→결과) 체인 보존. ⚠️ 핵심 변환: 비난·지적 프레임 → 객관적 경위 보고 프레임. "귀사가 ~해서/~하셔서" → "확인 결과 ~에서 발생한 것으로 파악됩니다", "~이 변경되면서 문제가 발생한 것으로 보입니다". 사전 안내 사실("이전에 ~을 안내드렸으나")은 유지하되 "또 ~하셔서"처럼 나무라는 어조는 제거. 사실적 인과 근거는 명확히 보존.
            - [SOFTEN:SELF_EXPLAIN: ...] → 상황 설명/해명: 변명 어조만 제거. 원인 사실 체인(왜 이렇게 되었는지)은 압축하되 반드시 보존. "~때문에 ~할 수밖에 없었다"의 인과 구조를 유지하세요. 원인 설명 자체를 삭제하지 마세요.
            - [SOFTEN:NEGATIVE_FEEDBACK: ...] → 부정적 평가: ⚠️ 핵심 변환: 직접적 부정·거부("~은 과하다", "~은 안 된다") → 대안 제시 프레임("먼저 ~을 파악한 후 적절한 방안을 모색해보겠습니다", "~보다는 ~방향으로 진행하는 것이 좋을 것 같습니다"). 단순 거부가 아닌, 화자가 해결을 위해 노력하는 자세를 보이면서 방향을 제안하는 톤. 문제의 심각도와 긴급도는 보존하되 건설적으로.
            - [SOFTEN:EMOTIONAL: ...] → 감정 표현: 감정을 통째로 삭제하지 마세요. 직접적 감정 표출("너무 답답하다", "화가 난다")을 절제된 간접 표현("아쉬움이 있다", "우려가 됩니다")으로 전환. 감정의 존재와 방향은 보존하되 표출 방식만 바꾸세요.
            - [SOFTEN:SPECULATION: ...] → 추측/가정: 단정 제거, 조건부/가능성 표현으로 전환. 다만, 합리적 근거가 있는 추론("거의 확실히 이것이 원인")을 과도하게 약화시키지 마세요.
            - [SOFTEN:OVER_EXPLANATION: ...] → 장황한 설명: 중복/반복 표현만 제거. 논리 체인 구조(A→B→C→결론)는 압축 형태로 보존. 설명의 단계를 통째로 생략하여 논리적 비약을 만들지 마세요.
            - [SOFTEN:LABEL: ...] 마커는 출력에 포함하지 마세요

            ### RED (삭제) — [REDACTED:...] 마커로 표시된 부분
            - 서버에서 제거된 부적절한 내용. 원래 내용을 추측/복원하지 마세요
            - [REDACTED:...] 마커 자체도 출력에 포함하지 마세요
            - 제거된 내용을 우회하여 표현하거나 암시하지 마세요
            - RED 자리에 새 문장을 만들지 말고, 앞뒤 GREEN/YELLOW 항목을 자연스럽게 연결하세요
            - 연결 시 접속사("또한", "아울러")를 최소화하고, 문장 순서만 매끄럽게 이어주세요

            ## 메시지 구조 재배치
            원문의 나열 순서에 얽매이지 마세요. GREEN/YELLOW 항목을 비즈니스 커뮤니케이션 구조로 재배치합니다:
            ① 인사 + 공감 쿠션 (상대의 불편·상황에 대한 공감이나 사과로 부드럽게 시작)
            ② 핵심 용건 — CORE_INTENT를 앞으로. 받는 사람이 첫 문단에서 메시지 목적을 파악하도록
            ③ 배경/상세 — CORE_FACT, ACCOUNTABILITY(재작성) 등 용건 뒷받침 내용
            ④ 요청/제안/마무리
            ⑤ 해결 의지 마무리 (노력·해결 의지 표현으로 신뢰감 있게 마무리)

            중복 통합:
            - 동일한 사실/요청/사과가 반복되면 가장 구체적인 표현 하나로 통합
            - 같은 변명/해명이 반복되면 핵심 원인만 남기고 압축
            - 단, 서로 다른 구체적 사실(날짜, 금액, 이유 등)이 포함된 반복은 각각 보존

            ## 핵심 원칙
            1. **해체 → 재구성**: GREEN 항목을 뼈대로, 위 구조 재배치 규칙에 따라 재구성. YELLOW는 라벨별 전략에 따라 재작성하여 적절한 위치에 배치. RED는 무시.
            2. **원문 범위 + 쿠션 표현**: 원문에 없는 구체적 사실/약속/수치 추가 금지. 단, 아래는 적극 활용:
               - 인사/호칭/서명
               - 공감 쿠션: "불편을 드려 죄송합니다", "번거로우시겠지만" 등 상대 배려 표현
               - 노력·해결 의지: "빠른 해결을 위해 확인하겠습니다", "최선을 다하겠습니다"
               - YELLOW 세그먼트의 기존 사실을 정중하게 재진술하거나 인과관계를 명시적으로 연결하는 것
               이러한 쿠션은 비즈니스 예의이며, "새 정보 추가"가 아닙니다.
            3. **중복 통합 & 간결화**: 동일 내용의 반복은 가장 구체적인 표현 하나로 통합. RED 제거로 생긴 빈 자리는 자연스럽게 연결. 접속사/수식어 최소화. 단, GREEN 핵심 사실/의도/수치는 통합 과정에서도 절대 누락 금지.
            4. **관점 유지**: 화자/청자 정확히 구분. 화자 관점 벗어나지 마세요.
            5. **고정 표현 절대 보존**: {{TYPE_N}} 형식 플레이스홀더(예: {{DATE_1}}, {{PHONE_1}}) 절대 수정/삭제/추가 금지.
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

        // Optional: Relation/Intent analysis (include if any field is non-empty)
        if (relationIntent != null &&
                (!relationIntent.relation().isEmpty() || !relationIntent.intent().isEmpty() || !relationIntent.stance().isEmpty())) {
            sb.append("\n--- 관계/의도 분석 ---\n");
            if (!relationIntent.relation().isEmpty()) sb.append("관계: ").append(relationIntent.relation()).append("\n");
            if (!relationIntent.intent().isEmpty()) sb.append("의도: ").append(relationIntent.intent()).append("\n");
            if (!relationIntent.stance().isEmpty()) sb.append("태도: ").append(relationIntent.stance()).append("\n");
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
            sb.append("\n[YELLOW 수정 항목] — 내용은 보존, 전달 방식만 변경 (ACCOUNTABILITY=인과체인보존/비난어조제거, SELF_EXPLAIN=원인사실보존/변명어조제거, NEGATIVE_FEEDBACK=개선프레임/심각도보존, EMOTIONAL=간접표현전환/감정존재보존, SPECULATION=가능성표현, OVER_EXPLANATION=중복제거/논리체인보존)\n");
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

        // Processed text (with RED=[REDACTED:...], YELLOW=[SOFTEN:LABEL: ...])
        sb.append("\n[변환 대상 텍스트]\n").append(processedText).append("\n");

        return sb.toString();
    }
}
