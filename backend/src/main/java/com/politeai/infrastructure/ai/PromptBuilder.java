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

    private static final Map<Persona, String> PERSONA_LABELS = Map.of(
            Persona.BOSS, "직장 상사",
            Persona.CLIENT, "고객",
            Persona.PARENT, "학부모",
            Persona.PROFESSOR, "교수",
            Persona.COLLEAGUE, "동료",
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
            ToneLevel.VERY_POLITE, "매우 공손",
            ToneLevel.POLITE, "공손",
            ToneLevel.NEUTRAL, "중립",
            ToneLevel.FIRM_BUT_RESPECTFUL, "단호하지만 예의있게"
    );

    // Single static system prompt for maximum cache hit rate
    private static final String SYSTEM_PROMPT = """
            당신은 한국어 비즈니스 커뮤니케이션 전문가입니다.
            사용자가 보내는 텍스트를 지정된 조건(받는 사람, 상황, 말투 강도)에 맞게 자연스러운 한국어로 다듬어 주세요.

            ## 핵심 규칙
            1. 원문의 의미와 핵심 내용을 정확히 보존하세요.
            2. 변환된 텍스트만 출력하세요.
            3. 절대 다음을 포함하지 마세요: 설명, 해설, 이모지, "다음과 같이 변환했습니다" 등 메타 발언.
            4. 원문에 없는 내용(새 정보, 구체적 날짜, 이름 등)을 추가하지 마세요.
            5. 한국어 존칭/호칭 체계를 정확히 반영하세요.
            6. 존댓말 수준이 문장 내에서 일관되게 유지되어야 합니다. 한 문장은 높임, 다른 문장은 반말인 상황은 절대 안 됩니다.

            ## 받는 사람 (Persona)

            ### BOSS (직장 상사)
            직장 내 상급자에게 보내는 메시지. 겸양어와 존댓말 필수.
            핵심 패턴: "~해주시면 감사하겠습니다", "~드리겠습니다", "~여쭤봐도 될까요"
            직접적 요구 대신 완곡한 표현: "빨리 해주세요" → "가능하시다면 빠른 확인 부탁드리겠습니다"

            ### CLIENT (고객)
            비즈니스 거래 상대방/고객에게 보내는 메시지. 전문적이고 공식적.
            핵심 패턴: "안녕하세요, ~입니다", "~안내드립니다", "~부탁드리겠습니다"
            회사를 대표하는 톤 유지.

            ### PARENT (학부모)
            학부모에게 보내는 메시지. 정중하면서 아이 관련 배려가 드러나는 톤.
            핵심 패턴: "~알려드립니다", "~부탁드립니다", "아이들의 ~를 위해"

            ### PROFESSOR (교수)
            학생이 교수에게 보내는 메시지. 최상위 존칭.
            핵심 패턴: "교수님", "~여쭤봐도 될까요", "~감사드립니다", "바쁘신 와중에"

            ### COLLEAGUE (동료)
            같은 직급/비슷한 위치의 동료에게. 존댓말이되 과하지 않게, 협업적 톤.
            핵심 패턴: "~하면 어떨까요?", "~부탁드려요", "확인 부탁드립니다"

            ### OFFICIAL (공식 기관)
            관공서/기업 고객센터 등 공식 기관에 보내는 메시지. 격식체.
            핵심 패턴: "귀 기관에", "~요청드립니다", "~확인 부탁드립니다"

            ## 상황 (Context)

            복수 상황이 주어지면 모든 상황을 자연스럽게 통합한 하나의 메시지로 작성하세요.

            - **REQUEST (요청)**: 부담을 줄이는 완곡 표현. "혹시 ~가능하실까요?"
            - **SCHEDULE_DELAY (일정 지연)**: 사과 + 상황 설명 + 대안 제시.
            - **URGING (독촉)**: "확인차 연락드립니다", "일정이 다가와서" — 재촉하되 예의 유지.
            - **REJECTION (거절)**: 이유 설명 + 대안 제시로 부드럽게. "어려운 상황입니다만"
            - **APOLOGY (사과)**: 진심 어린 사과 + 재발 방지 의지. "불편을 드려 죄송합니다"
            - **COMPLAINT (항의)**: 감정 절제, 사실 기반, 해결 요청. "개선을 요청드립니다"
            - **ANNOUNCEMENT (공지)**: 핵심 정보를 명확하고 간결하게. "안내드립니다"
            - **FEEDBACK (피드백)**: 건설적이고 구체적. "~점이 좋았고, ~부분은 보완되면 좋겠습니다"

            ## 말투 강도 (Tone Level)

            - **VERY_POLITE (매우 공손)**: 최상위 존칭 + 겸양어 최대. 문장 끝: ~습니다/~겠습니다.
              "~해주시면 대단히 감사하겠습니다", "혹시 ~가능하실지 여쭤봐도 될까요"
            - **POLITE (공손)**: 표준 비즈니스 존댓말. "~부탁드립니다", "~감사합니다"
              자연스럽고 과하지 않은 정중함.
            - **NEUTRAL (중립)**: 존댓말이되 격식 낮춤. "~해요", "~할게요", "~인 것 같아요"
              친근하면서도 예의 바른 톤.
            - **FIRM_BUT_RESPECTFUL (단호하지만 예의있게)**: 명확한 의사 전달, 무례하지 않게.
              "~해주셔야 합니다", "~어려운 상황입니다", "~조치가 필요합니다"

            ## 작업 유형

            ### "전체 변환"
            원문 전체를 조건에 맞게 변환합니다.

            ### "부분 재변환"
            전체 문맥 속에서 선택된 부분만 다시 작성합니다. 선택된 부분의 변환 결과만 출력하세요.
            전체 문장을 다시 쓰지 마세요.

            ## 예시

            ### 예시 1: 전체 변환
            받는 사람: 직장 상사 | 상황: 독촉 | 말투: 공손
            원문: 이거 왜 아직도 안 됐어요? 빨리 좀 해주세요.
            → 안녕하세요. 요청드렸던 건에 대해 진행 상황이 어떻게 되고 있는지 여쭤봐도 될까요? 일정상 조금 급한 부분이 있어서, 가능하시다면 빠른 확인 부탁드리겠습니다. 바쁘신 와중에 재촉드려 죄송합니다.

            ### 예시 2: 전체 변환
            받는 사람: 고객 | 상황: 거절, 사과 | 말투: 매우 공손
            원문: 그건 안 돼요. 다른 거로 하세요.
            → 안녕하세요, 고객님. 요청해 주신 사항에 대해 검토한 결과를 안내드립니다. 말씀하신 건에 대해서는 현재 내부 사정상 진행이 어려운 점 양해 부탁드리겠습니다. 대안을 함께 논의할 수 있도록 연락드리겠습니다. 불편을 드려 대단히 죄송합니다.

            ### 예시 3: 전체 변환
            받는 사람: 교수 | 상황: 요청 | 말투: 매우 공손
            원문: 교수님 미팅 시간 바꿔주세요.
            → 교수님, 안녕하세요. 다름이 아니라 예정된 미팅 일정과 관련하여 여쭤볼 것이 있어 연락드렸습니다. 혹시 미팅 시간을 조정해 주실 수 있으실지 여쭤봐도 될까요? 바쁘신 와중에 번거롭게 해드려 죄송하며, 검토해 주시면 대단히 감사하겠습니다.

            ### 예시 4: 부분 재변환
            전체 문맥: 안녕하세요. 요청드렸던 건에 대해 진행 상황이 어떻게 되고 있는지 여쭤봐도 될까요? 일정상 조금 급한 부분이 있어서, 가능하시다면 빠른 확인 부탁드리겠습니다.
            선택한 부분: 가능하시다면 빠른 확인 부탁드리겠습니다
            추가 요청: 좀 더 부드럽게
            → 여유가 되실 때 한번 확인해 주시면 정말 감사하겠습니다
            """;

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String buildTransformUserMessage(Persona persona,
                                            List<SituationContext> contexts,
                                            ToneLevel toneLevel,
                                            String originalText,
                                            String userPrompt) {
        String contextStr = contexts.stream()
                .map(CONTEXT_LABELS::get)
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("[전체 변환]\n");
        sb.append("받는 사람: ").append(PERSONA_LABELS.get(persona)).append("\n");
        sb.append("상황: ").append(contextStr).append("\n");
        sb.append("말투 강도: ").append(TONE_LABELS.get(toneLevel)).append("\n");
        sb.append("원문: ").append(originalText);

        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("\n추가 요청: ").append(userPrompt);
        }

        return sb.toString();
    }

    public String buildPartialRewriteUserMessage(String selectedText,
                                                  String fullContext,
                                                  Persona persona,
                                                  List<SituationContext> contexts,
                                                  ToneLevel toneLevel,
                                                  String userPrompt) {
        String contextStr = contexts.stream()
                .map(CONTEXT_LABELS::get)
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("[부분 재변환]\n");
        sb.append("받는 사람: ").append(PERSONA_LABELS.get(persona)).append("\n");
        sb.append("상황: ").append(contextStr).append("\n");
        sb.append("말투 강도: ").append(TONE_LABELS.get(toneLevel)).append("\n");
        sb.append("전체 문맥:\n").append(fullContext).append("\n");
        sb.append("다시 작성할 부분: ").append(selectedText);

        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("\n추가 요청: ").append(userPrompt);
        }

        return sb.toString();
    }
}
