package com.politeai.infrastructure.ai;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PromptBuilder {

    static final Map<Persona, String> PERSONA_LABELS = Map.of(
            Persona.BOSS, "직장 상사",
            Persona.CLIENT, "고객",
            Persona.PARENT, "학부모",
            Persona.PROFESSOR, "교수",
            Persona.OTHER, "기타",
            Persona.OFFICIAL, "공식 기관"
    );

    private static final Map<SituationContext, String> CONTEXT_LABELS = Map.of(
            SituationContext.REQUEST, "요청",
            SituationContext.SCHEDULE_DELAY, "일정 지연",
            SituationContext.URGING, "독촉",
            SituationContext.REJECTION, "거절",
            SituationContext.APOLOGY, "사과",
            SituationContext.COMPLAINT, "항의",
            SituationContext.ANNOUNCEMENT, "공지",
            SituationContext.FEEDBACK, "피드백"
    );

    private static final Map<ToneLevel, String> TONE_LABELS = Map.of(
            ToneLevel.NEUTRAL, "중립",
            ToneLevel.POLITE, "공손",
            ToneLevel.VERY_POLITE, "매우 공손"
    );

    // ===== Core system prompt (used by partial rewrite) =====

    private static final String CORE_SYSTEM_PROMPT = """
            당신은 한국어 커뮤니케이션 전문가입니다. 화자의 의도와 감정을 파악하여 상대방의 마음을 움직이는 메시지로 재구성합니다.

            ## 핵심 원칙
            1. **재구성**: 존댓말 번역이 아닌, 의도를 살린 메시지 재구성. 쿠션어로 감정의 깊이를 만드세요.
            2. **관점 유지**: 화자/청자를 정확히 구분. 화자의 관점을 절대 벗어나지 마세요.
            3. **사실 보존**: {{LOCKED_N}} 플레이스홀더는 절대 수정/삭제/추가하지 마세요. 그대로 유지.
            4. **위험 표현 대체**: "피의자"→"당사자", 공격적/비하/책임회피/비꼼 표현을 사실 유지하며 건설적으로 순화.
            5. **부적절 내용 필터링**: 화자에게 불리하거나 받는 사람에게 부적절한 정보는 삭제하거나 품위 있게 대체하세요.
               - 음주/유흥/개인 일탈 언급 → 삭제하거나 "개인 사정"/"불가피한 사유" 등으로 대체
               - 체념/투덜거림("어쩔 수 없다", "억울하다") → 삭제하거나 건설적 표현으로 전환
               - TMI(받는 사람이 알 필요 없는 사적 사유) → 핵심 사실만 남기고 삭제
            6. **자연스러움**: "드리겠습니다" 연속 2회 이상 금지. 어미를 다양하게. 기계적 패턴 금지.
            7. **이전 변환 존중**: 사전 분석 결과가 제공된 경우, 이전 전체 변환에서 제거된 내용(책임 전가, 공격적 표현, TMI 등)을 다시 포함하지 마세요.

            ## 출력 규칙 (절대 준수)
            - **변환된 메시지 텍스트만 출력하세요.** 분석, 설명, 해설, 이모지, "화자는~", "전체적으로~" 같은 메타 발언을 절대 포함하지 마세요.
            - 첫 글자부터 바로 변환된 메시지여야 합니다. 앞뒤에 어떤 부연도 붙이지 마세요.
            - 문단 사이에만 줄바꿈(\\n\\n). 문단 내 문장은 줄바꿈 없이 이어서 작성. 한 문단 최대 4문장.
            - 원문 길이에 비례하는 자연스러운 분량. 짧은 원문은 짧게.""";

    // ===== Dynamic persona blocks (each ~50 tokens) =====

    private static final Map<Persona, String> PERSONA_BLOCKS = Map.of(
            Persona.BOSS, """

                    ## 받는 사람: 직장 상사
                    겸양어와 존댓말 필수. 완곡한 요청/질문 형식 사용 (예시 참고만, 그대로 복사 금지: "~해주시면 감사하겠습니다" 류).
                    보내는 사람 정보가 있으면 인사에 포함. 서명 "[이름] 드림".""",

            Persona.CLIENT, """

                    ## 받는 사람: 고객
                    전문적이고 공식적. 회사를 대표하는 톤 유지.
                    보내는 사람 정보가 있으면 소속·이름 포함. 서명 "[이름] 드림".""",

            Persona.PARENT, """

                    ## 받는 사람: 학부모
                    정중하면서 아이 관련 배려가 드러나는 톤.
                    보내는 사람 정보가 있으면 소속·직함 포함. 따뜻한 마무리.""",

            Persona.PROFESSOR, """

                    ## 받는 사람: 교수
                    최상위 존칭. 인사 → 자기소개(학과/이름) → 용건 순서.
                    서명 "[이름] 올림". 캐주얼 표현 절대 금지.""",

            Persona.OFFICIAL, """

                    ## 받는 사람: 공식 기관
                    격식체. 용건을 두괄식으로 명확하게. 필요 정보(날짜, 번호) 구체적 기재.""",

            Persona.OTHER, """

                    ## 받는 사람: 기타
                    상황과 말투 강도에 맞춰 적절히 변환. 특정 관계를 전제하지 않음."""
    );

    // ===== Dynamic context blocks (each ~30 tokens) =====

    private static final Map<SituationContext, String> CONTEXT_BLOCKS = Map.of(
            SituationContext.REQUEST, """
                    - **요청**: 부담을 줄이는 완곡 표현("~해주시면 감사하겠습니다", "혹시 가능하시다면"). 상대의 바쁜 상황 배려("바쁘신 중에 죄송합니다만"). 기한은 원문의 구체적 날짜 유지. 요청 이유를 간결히 설명.""",
            SituationContext.SCHEDULE_DELAY, """
                    - **일정 지연**: 사과를 먼저("지연되어 죄송합니다") + 원인 간결히(변명 아닌 사실 설명) + 구체적 새 일정("~까지 완료하겠습니다"). 변명성 설명은 최소화하고 대안에 집중.""",
            SituationContext.URGING, """
                    - **독촉**: 이전 요청 사실을 상기시키되 비난하지 않기("앞서 말씀드린 건으로 확인 부탁드립니다"). 구체적 회신 기한 제시. "확인차 연락드립니다" 패턴으로 부드럽게. 반복 독촉이면 단호하되 예의 유지.""",
            SituationContext.REJECTION, """
                    - **거절**: 감사/이해 표현 → 거절 이유(솔직하되 부드럽게) → 가능한 대안 제시 → 아쉬움 마무리. "어렵겠습니다", "양해 부탁드립니다" 등 완곡 표현 활용.""",
            SituationContext.APOLOGY, """
                    - **사과**: 진심 어린 사과("깊이 사과드립니다") + 상대 불편 공감 + 원인(부적절한 사유는 "불가피한 사정"으로 대체) + 해결책/재발 방지 의지. 체념("어쩔 수 없다")은 개선 의지로 전환. 사과와 함께 구체적 보상/해결안 제시.""",
            SituationContext.COMPLAINT, """
                    - **항의**: 감정을 절제하고 사실 기반으로 문제 기술. 구체적 근거(날짜, 횟수, 금액) 제시. 원하는 해결 방향 명시("~해주시면 감사하겠습니다"). 공격적 표현 대신 건설적 요청으로.""",
            SituationContext.ANNOUNCEMENT, """
                    - **공지**: 핵심 정보(일시·장소·대상·내용)를 두괄식으로. 부가 설명은 뒤에 간결히. 행동 요청("참석 부탁드립니다", "확인 부탁드립니다")으로 마무리.""",
            SituationContext.FEEDBACK, """
                    - **피드백**: 긍정적 면을 구체적으로 먼저 언급 → 개선점을 건설적으로 제시("~하면 더 좋을 것 같습니다") → 함께 노력하는 자세. 비판이 아닌 성장 지향 톤."""
    );

    // ===== Dynamic tone level blocks (each ~20 tokens) =====

    private static final Map<ToneLevel, String> TONE_BLOCKS = Map.of(
            ToneLevel.NEUTRAL, """

                    ## 말투: 중립 — 존댓말이되 격식 낮춤. "~해요", "~할게요". 친근하면서 예의 바른 톤.""",
            ToneLevel.POLITE, """

                    ## 말투: 공손 — 표준 비즈니스 존댓말. 자연스럽고 과하지 않은 정중함.""",
            ToneLevel.VERY_POLITE, """

                    ## 말투: 매우 공손 — 최상위 존칭 + 겸양어. 격식을 갖추되 진심이 느껴지는 정중함."""
    );

    // ===== Public methods =====

    /**
     * Build dynamic system prompt: core + persona block + context blocks + tone block.
     * Used by partial rewrite.
     */
    public String buildSystemPrompt(Persona persona, List<SituationContext> contexts, ToneLevel toneLevel) {
        return buildDynamicBlocks(CORE_SYSTEM_PROMPT, persona, contexts, toneLevel);
    }

    /**
     * Build dynamic blocks (persona + context + tone) appended to a custom core prompt.
     * Used by MultiModelPromptBuilder for the final model's distinct system prompt.
     */
    public String buildDynamicBlocks(String corePrompt, Persona persona, List<SituationContext> contexts, ToneLevel toneLevel) {
        StringBuilder sb = new StringBuilder(corePrompt);

        // Persona block
        sb.append(PERSONA_BLOCKS.getOrDefault(persona, ""));

        // Context blocks
        if (!contexts.isEmpty()) {
            sb.append("\n\n## 상황");
            for (SituationContext ctx : contexts) {
                sb.append("\n").append(CONTEXT_BLOCKS.getOrDefault(ctx, ""));
            }
            if (contexts.size() > 1) {
                sb.append("\n- **복합 상황 우선순위**: 첫 번째 상황이 메시지의 주된 목적입니다. 나머지 상황은 보조적으로 반영하되, 상충 시 첫 번째 상황의 톤을 우선하세요.");
            }
        }

        // Tone block
        sb.append(TONE_BLOCKS.getOrDefault(toneLevel, ""));

        return sb.toString();
    }

    public String buildPartialRewriteUserMessage(String selectedText,
                                                  String fullContext,
                                                  Persona persona,
                                                  List<SituationContext> contexts,
                                                  ToneLevel toneLevel,
                                                  String userPrompt,
                                                  String senderInfo,
                                                  String analysisContext) {
        String contextStr = contexts.stream()
                .map(CONTEXT_LABELS::get)
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("[부분 재변환]\n");
        sb.append("받는 사람: ").append(PERSONA_LABELS.get(persona)).append("\n");
        sb.append("상황: ").append(contextStr).append("\n");
        sb.append("말투 강도: ").append(TONE_LABELS.get(toneLevel)).append("\n");

        if (senderInfo != null && !senderInfo.isBlank()) {
            sb.append("보내는 사람: ").append(senderInfo).append("\n");
        }

        if (analysisContext != null && !analysisContext.isBlank()) {
            sb.append("\n--- 사전 분석 결과 ---\n");
            sb.append(analysisContext);
            sb.append("\n--- 사전 분석 끝 ---\n\n");
        }

        sb.append("전체 문맥:\n").append(fullContext).append("\n");
        sb.append("다시 작성할 부분: ").append(selectedText);

        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("\n참고 맥락: ").append(userPrompt);
        }

        return sb.toString();
    }

    // === Accessor methods for pipeline use ===

    public String getContextLabel(SituationContext ctx) {
        return CONTEXT_LABELS.getOrDefault(ctx, ctx.name());
    }

    public String getPersonaLabel(Persona persona) {
        return PERSONA_LABELS.getOrDefault(persona, persona.name());
    }

    public String getToneLabel(ToneLevel toneLevel) {
        return TONE_LABELS.getOrDefault(toneLevel, toneLevel.name());
    }
}
