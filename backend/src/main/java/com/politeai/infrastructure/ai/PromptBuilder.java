package com.politeai.infrastructure.ai;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import org.springframework.stereotype.Component;

import java.util.*;
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

    // ===== Core system prompt (~300 tokens) =====

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

            ## 출력 규칙 (절대 준수)
            - **변환된 메시지 텍스트만 출력하세요.** 분석, 설명, 해설, 이모지, "화자는~", "전체적으로~" 같은 메타 발언을 절대 포함하지 마세요.
            - 첫 글자부터 바로 변환된 메시지여야 합니다. 앞뒤에 어떤 부연도 붙이지 마세요.
            - 의미 단위로 줄바꿈(\\n\\n). 한 문단 최대 4문장.
            - 원문 길이에 비례하는 자연스러운 분량. 짧은 원문은 짧게.""";

    // ===== Dynamic persona blocks (each ~50 tokens) =====

    private static final Map<Persona, String> PERSONA_BLOCKS = Map.of(
            Persona.BOSS, """

                    ## 받는 사람: 직장 상사
                    겸양어와 존댓말 필수. "~해주시면 감사하겠습니다", "~여쭤봐도 될까요".
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
                    최상위 존칭. "교수님 안녕하세요. [학과] [이름]입니다."
                    서명 "[이름] 올림". 캐주얼 표현 절대 금지.
                    음주/유흥/사적 일탈 사유는 절대 언급하지 말 것. "개인 사정"/"불가피한 사유"로 대체.""",

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
                    - **요청**: 부담을 줄이는 완곡 표현. 상대의 바쁜 상황 배려. 기한은 구체적 날짜로.""",
            SituationContext.SCHEDULE_DELAY, """
                    - **일정 지연**: 사과 + 원인 설명(변명 아닌 설명) + 구체적 대안("~까지 제출하겠습니다").""",
            SituationContext.URGING, """
                    - **독촉**: 재촉하되 예의 유지. 이전 요청 상기 + 구체적 회신 기한. "확인차 연락드립니다" 패턴.""",
            SituationContext.REJECTION, """
                    - **거절**: 이유 설명 + 대안 제시로 부드럽게. 아쉬움 표현.""",
            SituationContext.APOLOGY, """
                    - **사과**: 진심 어린 사과 + 공감 + 원인 + 해결/재발 방지 의지. 부적절한 사유(음주 등)는 생략하고 "불가피한 사정"으로 대체. 체념("어쩔 수 없다")은 삭제하고 개선 의지로 전환.""",
            SituationContext.COMPLAINT, """
                    - **항의**: 감정 절제, 사실 기반, 건설적 해결 요청. 구체적 근거 제시.""",
            SituationContext.ANNOUNCEMENT, """
                    - **공지**: 핵심 정보를 명확하고 간결하게. 날짜·장소·대상 두괄식.""",
            SituationContext.FEEDBACK, """
                    - **피드백**: 건설적이고 구체적. 긍정적 면 먼저, 개선점 제시. 함께 노력 자세."""
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

    // ===== Examples =====

    record TransformExample(
            String id,
            Set<Persona> personas,
            Set<SituationContext> contexts,
            String text
    ) {
        int matchScore(Persona persona, List<SituationContext> reqContexts) {
            int score = 0;
            if (personas.contains(persona)) score += 2;
            for (SituationContext ctx : reqContexts) {
                if (contexts.contains(ctx)) score += 1;
            }
            return score;
        }
    }

    private static final List<TransformExample> EXAMPLES = List.of(
            new TransformExample("ex1",
                    Set.of(Persona.BOSS),
                    Set.of(SituationContext.URGING),
                    """
                    받는 사람: 직장 상사 | 상황: 독촉 | 말투: 공손
                    보내는 사람: 마케팅팀 김민수 대리
                    원문: 이거 왜 아직도 안 됐어요? 빨리 좀 해주세요.
                    →
                    안녕하세요. 마케팅팀 김민수입니다.

                    앞서 요청드렸던 건 혹시 진행 상황이 어떻게 될까요? 일정이 좀 촉박해져서 확인차 여쭤봅니다.

                    바쁘신 중에 재촉드려 죄송합니다.
                    김민수 드림"""
            ),
            new TransformExample("ex2",
                    Set.of(Persona.CLIENT),
                    Set.of(SituationContext.REJECTION, SituationContext.APOLOGY),
                    """
                    받는 사람: 고객 | 상황: 거절, 사과 | 말투: 매우 공손
                    원문: 그건 안 돼요. 다른 거로 하세요.
                    →
                    고객님, 요청해 주신 사항을 검토해 보았습니다. 아쉽게도 해당 건은 현재 내부 사정상 진행이 어려운 상황입니다.

                    대신 다른 방향으로 함께 논의해 볼 수 있을까요? 불편을 드려 죄송합니다."""
            ),
            new TransformExample("ex3",
                    Set.of(Persona.PARENT),
                    Set.of(SituationContext.APOLOGY, SituationContext.FEEDBACK),
                    """
                    받는 사람: 학부모 | 상황: 사과, 피드백 | 말투: 공손
                    보내는 사람: 3학년 2반 담임 박지영
                    원문: 여~ 학부모! 이번에 애 성적이 많이 안 좋아서 나도 유감데쓰! ...
                    →
                    학부모님, 안녕하세요. 3학년 2반 담임 박지영입니다.

                    이번 성적 결과를 보시고 답답하신 마음이 크실 거라 생각합니다. 저도 아이가 더 좋은 결과를 받았으면 하는 마음이 컸기에 아쉬움이 큽니다.

                    다만 숙제와 복습 부분에서 아직 보완이 필요한 상황이라, 가정에서도 이 부분을 함께 챙겨주시면 큰 도움이 될 것 같습니다. 저도 아이에게 맞는 방식을 더 고민하면서 꾸준히 이끌어 보겠습니다.

                    박지영 드림"""
            ),
            new TransformExample("ex4",
                    Set.of(Persona.CLIENT, Persona.OTHER),
                    Set.of(SituationContext.APOLOGY),
                    """
                    받는 사람: 고객 | 상황: 사과 | 말투: 매우 공손
                    원문: 안녕하세요. 펜션 사장님 맞으신가요? 2주전 사장님 펜션에서 불미스러운 사건을 저지른 피의자입니다. ...
                    →
                    사장님, 안녕하십니까.
                    지난 1월 1일, 사장님의 소중한 영업장에서 불미스러운 일로 큰 폐를 끼치고 소란을 일으켰던 당사자입니다.

                    사건 직후, 저 스스로도 너무나 부끄럽고 경황이 없어 감히 연락드릴 엄두를 내지 못했습니다. 본의 아니게 연락이 많이 늦어진 점, 머리 숙여 깊이 사죄드립니다.

                    지인을 통해 숙소 세탁비와 청소비 등 금전적인 피해가 발생했다는 이야기를 전해 들었습니다. 늦었지만 지금이라도 제가 책임지고 마땅히 배상해 드리는 것이 도리라고 생각하여 조심스럽게 연락드립니다.

                    당시 놀라셨을 사장님께 다시 한번 죄송한 마음을 전하며, 너그러운 양해를 부탁드립니다."""
            ),
            new TransformExample("ex5",
                    Set.of(Persona.OTHER),
                    Set.of(SituationContext.REQUEST),
                    """
                    받는 사람: 기타 | 상황: 요청 | 말투: 중립
                    원문: 이거 좀 봐줘
                    → 이거 한번 봐줄 수 있어요? 시간 될 때 확인 부탁해요."""
            ),
            new TransformExample("ex6",
                    Set.of(Persona.OTHER),
                    Set.of(SituationContext.REJECTION),
                    """
                    받는 사람: 기타 | 상황: 거절 | 말투: 중립
                    원문: 내가 그 주에는 민태님 대타 나가서 못 나갈거 같음~
                    → 그 주에는 민태님 대타로 나가야 해서 못 나갈 것 같아요. 미안해요!"""
            ),
            new TransformExample("ex7",
                    Set.of(Persona.PROFESSOR),
                    Set.of(SituationContext.REQUEST),
                    """
                    받는 사람: 교수 | 상황: 요청 | 말투: 매우 공손
                    보내는 사람: 경영학과 20221234 홍길동
                    원문: 교수님 시험 못 봤는데 따로 볼 수 있나요? 그날 아파서 못 갔어요
                    →
                    교수님 안녕하세요. 경영학과 20221234 홍길동입니다.

                    다름이 아니라, 지난 시험에 불가피하게 불참하게 된 건으로 조심스럽게 여쭤볼 것이 있어 연락드립니다. 시험 당일 갑작스러운 건강 문제로 응시하지 못했습니다.

                    혹시 별도로 시험을 볼 수 있는 기회가 있을지 여쭤봐도 될까요? 바쁘신 중에 번거로운 부탁 드려 죄송합니다.

                    감사합니다.
                    경영학과 홍길동 올림"""
            ),
            new TransformExample("ex8",
                    Set.of(Persona.BOSS),
                    Set.of(SituationContext.SCHEDULE_DELAY, SituationContext.APOLOGY),
                    """
                    받는 사람: 직장 상사 | 상황: 일정 지연, 사과 | 말투: 공손
                    보내는 사람: 기획팀 이서연 사원
                    원문: 팀장님 보고서 늦었습니다 죄송합니다 외부 업체 때문에 좀 밀렸어요 오늘 중으로 보내겠습니다
                    →
                    팀장님, 안녕하세요. 기획팀 이서연입니다.
                    보고서 제출 건으로 연락드립니다.

                    기한을 지키지 못해 죄송합니다. 외부 업체 측 자료 수급이 지연되면서 일정이 밀린 상황입니다. 해당 업체 담당자에게는 이미 재촉하여 확인받은 상태이고, 오늘 오후까지 보고서를 송부드리겠습니다.

                    다시 한번 죄송합니다.
                    이서연 드림"""
            )
    );

    private static final List<String> ANTI_PATTERNS = List.of(
            """
            [안티 패턴: 원문을 표면적으로만 다듬은 경우]
            ❌ "피의자"를 그대로 사용, 보상 의지 소극적, 감정적 맥락 없음 → 말투만 바꾼 전형적 실패""",
            """
            [안티 패턴: 화자/청자 관점 혼동]
            ❌ 화자가 방문하는 상황을 "방문하신다니 잘 알겠습니다"로 청자 시점 전환, "12~3시"→"12~1시" 사실 왜곡, 화자가 확인 요청하는데 "확인해 드리겠습니다"로 청자 응답""",
            """
            [안티 패턴: 맥락 왜곡]
            ❌ "못 나갈거 같음"을 "참석이 어려울 것 같습니다"로 추상화 → 구체적 맥락 소실""",
            """
            [안티 패턴: 부적절한 내용 미필터링 + 메타 텍스트 누출]
            ❌ "술을 마셔서 못 갔다"를 그대로 존댓말로만 바꿈 → 교수님께 음주 사유 노출은 화자에게 치명적
            ❌ "어쩔 수 없다", "억울하다" 같은 체념/불만 표현을 그대로 둠 → 받는 사람 입장에서 무례
            ❌ 변환 결과 앞에 "화자는~하고 있습니다" 같은 분석/해설 텍스트를 붙임 → 메시지가 아닌 해설"""
    );

    // Pro JSON output section
    private static final String PRO_JSON_RULES = """

            ## Pro 출력 (JSON만)
            {
              "analysis": "상황 분석 3~5문장",
              "result": "변환된 텍스트",
              "checks": {"perspectiveVerified":bool,"factsPreserved":bool,"toneConsistent":bool},
              "edits": [{"original":"원문","changed":"변경","reason":"이유"}],
              "riskFlags": ["위험 요소"]
            }
            JSON 외 텍스트 금지.""";

    // ===== Public methods =====

    /**
     * Build dynamic system prompt: core + persona block + context blocks + tone block.
     * Total ~430-550 tokens (vs ~7500 before).
     */
    public String buildSystemPrompt(Persona persona, List<SituationContext> contexts, ToneLevel toneLevel) {
        StringBuilder sb = new StringBuilder(CORE_SYSTEM_PROMPT);

        // Persona block
        sb.append(PERSONA_BLOCKS.getOrDefault(persona, ""));

        // Context blocks
        if (!contexts.isEmpty()) {
            sb.append("\n\n## 상황");
            for (SituationContext ctx : contexts) {
                sb.append("\n").append(CONTEXT_BLOCKS.getOrDefault(ctx, ""));
            }
        }

        // Tone block
        sb.append(TONE_BLOCKS.getOrDefault(toneLevel, ""));

        return sb.toString();
    }

    /**
     * Build Pro system prompt: dynamic system prompt + JSON rules.
     */
    public String buildProSystemPrompt(Persona persona, List<SituationContext> contexts, ToneLevel toneLevel) {
        return buildSystemPrompt(persona, contexts, toneLevel) + PRO_JSON_RULES;
    }

    /**
     * Legacy method for backward compatibility — returns core prompt only.
     * Prefer buildSystemPrompt(persona, contexts, toneLevel) for dynamic prompts.
     */
    public String getSystemPrompt() {
        return CORE_SYSTEM_PROMPT;
    }

    /**
     * Legacy method — returns core + JSON rules.
     */
    public String getProSystemPrompt() {
        return CORE_SYSTEM_PROMPT + PRO_JSON_RULES;
    }

    /**
     * Select top-N examples matching the given persona and contexts,
     * filtered by minimum score threshold.
     */
    List<TransformExample> selectRelevantExamples(Persona persona, List<SituationContext> contexts,
                                                   int maxCount, int minScore) {
        return EXAMPLES.stream()
                .filter(e -> e.matchScore(persona, contexts) >= minScore)
                .sorted(Comparator.comparingInt((TransformExample e) -> e.matchScore(persona, contexts)).reversed())
                .limit(maxCount)
                .toList();
    }

    /**
     * Build examples block for user message (Free tier: 0~2 examples with score >= 2).
     */
    private String buildExamplesBlock(Persona persona, List<SituationContext> contexts) {
        List<TransformExample> selected = selectRelevantExamples(persona, contexts, 2, 2);
        if (selected.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n--- 참고 예시 ---\n");
        for (int i = 0; i < selected.size(); i++) {
            sb.append("예시 ").append(i + 1).append(":\n");
            sb.append(selected.get(i).text().strip()).append("\n\n");
        }
        // Add one anti-pattern
        if (!ANTI_PATTERNS.isEmpty()) {
            sb.append(ANTI_PATTERNS.getFirst().strip()).append("\n");
        }
        sb.append("--- 참고 예시 끝 ---");
        return sb.toString();
    }

    public String buildTransformUserMessage(Persona persona,
                                            List<SituationContext> contexts,
                                            ToneLevel toneLevel,
                                            String originalText,
                                            String userPrompt,
                                            String senderInfo) {
        return buildTransformUserMessage(persona, contexts, toneLevel, originalText, userPrompt, senderInfo, null);
    }

    public String buildTransformUserMessage(Persona persona,
                                            List<SituationContext> contexts,
                                            ToneLevel toneLevel,
                                            String originalText,
                                            String userPrompt,
                                            String senderInfo,
                                            String analysisContext) {
        String contextStr = contexts.stream()
                .map(CONTEXT_LABELS::get)
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("[전체 변환]\n");
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

        sb.append("원문: ").append(originalText);

        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("\n참고 맥락: ").append(userPrompt);
        }

        // Append relevant examples (in user message, not system prompt)
        sb.append(buildExamplesBlock(persona, contexts));

        return sb.toString();
    }

    /**
     * Build user message for Pro 1-pass transform (JSON mode).
     * No examples — Pro uses JSON 1-pass, examples are unnecessary.
     */
    public String buildProTransformUserMessage(Persona persona,
                                               List<SituationContext> contexts,
                                               ToneLevel toneLevel,
                                               String originalText,
                                               String userPrompt,
                                               String senderInfo) {
        String contextStr = contexts.stream()
                .map(CONTEXT_LABELS::get)
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("[Pro 전체 변환 — 분석+변환 통합]\n");
        sb.append("받는 사람: ").append(PERSONA_LABELS.get(persona)).append("\n");
        sb.append("상황: ").append(contextStr).append("\n");
        sb.append("말투 강도: ").append(TONE_LABELS.get(toneLevel)).append("\n");

        if (senderInfo != null && !senderInfo.isBlank()) {
            sb.append("보내는 사람: ").append(senderInfo).append("\n");
        }

        sb.append("원문: ").append(originalText);

        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("\n참고 맥락: ").append(userPrompt);
        }

        // Pro: 0 examples (JSON 1-pass), 0-1 anti-pattern
        if (!ANTI_PATTERNS.isEmpty()) {
            sb.append("\n\n--- 참고 ---\n");
            sb.append(ANTI_PATTERNS.getFirst().strip());
            sb.append("\n--- 참고 끝 ---");
        }

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

    /**
     * Build the retry prompt for Pro pipeline when validation fails.
     */
    public String buildProRetryUserMessage(String previousResult,
                                           List<String> issueDescriptions,
                                           String originalUserMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("[재시도 요청]\n");
        sb.append("이전 변환 결과:\n").append(previousResult).append("\n\n");
        sb.append("발견된 문제:\n");
        for (String issue : issueDescriptions) {
            sb.append("- ").append(issue).append("\n");
        }
        sb.append("\n위 문제를 수정하여 동일 JSON 형식으로 다시 변환하세요.\n\n");
        sb.append("--- 원래 요청 ---\n").append(originalUserMessage);

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
