package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.LockedSpan;
import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import com.politeai.infrastructure.ai.PromptBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds prompts for the multi-model pipeline (3 analysis + 1 final):
 * - Model 1: Situation analysis + speaker intent (combined) — prose output
 * - Model 2: Locked expression extraction (semantic spans AI identifies) — line-per-item output
 * - Model 3 (buildModel4*): Source text deconstruction (원문 해체) — numbered list output
 * - Final Model: Transform using intermediate analysis (deconstruct → reassemble)
 */
@Component
@RequiredArgsConstructor
public class MultiModelPromptBuilder {

    private final PromptBuilder promptBuilder;

    // ===== Model 1: Situation Analysis + Speaker Intent (Combined) =====

    public String buildModel1SystemPrompt() {
        return """
                당신은 한국어 커뮤니케이션 분석 전문가입니다.
                원문과 메타데이터를 기반으로 상황과 화자의 의도를 분석합니다.

                다음 두 섹션을 자연스러운 줄글로 작성하세요:

                [상황]
                - 현재 상황에 대한 객관적 사실 기술
                - 화자와 청자의 관계 및 역학
                (2-3문장)

                [화자 의도]
                - 화자의 핵심 목적 (원문에서 직접 읽히는 것만)
                - 원문의 어조에서 드러나는 감정 상태
                - 이 메시지를 통해 얻고 싶은 구체적 결과
                (2-3문장)

                ## 금지
                - 원문에 근거 없는 심리 추측 금지
                - 모든 분석은 원문의 구체적 표현에 근거해야 합니다

                총 4-6문장 이내.""";
    }

    public String buildModel1UserMessage(Persona persona, List<SituationContext> contexts,
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
        return sb.toString();
    }

    // ===== Model 2: Locked Expression Extraction =====

    public String buildModel2SystemPrompt() {
        return """
                당신은 텍스트에서 변경 불가능한 고유 표현을 추출하는 전문가입니다.
                정규식으로 잡을 수 없는, 대체하면 의미가 달라지는 고유 식별자만 찾습니다.

                이미 마스킹된 {{LOCKED_N}} 플레이스홀더는 무시하세요.
                날짜, 시간, 전화번호, 이메일, URL, 금액 등은 이미 처리되었으므로 제외하세요.

                ## 추출 대상 (고유 식별자만)
                - 사람/회사/기관의 고유 이름 (예: 김민수, ㈜한빛소프트)
                - 프로젝트/제품/서비스 고유 명칭 (예: Project Alpha, 스터디플랜 v2)
                - 파일명, 코드명, 시스템명 (예: report_final.xlsx, ERP)

                ## 제외 대상 (절대 추출 금지)
                - 일반 명사, 보통 명사, 일상 어휘
                - 관계/역할 호칭 (학부모, 담임, 교수, 팀장, 고객, 선생님 등)
                - 메타데이터에 이미 명시된 정보 (받는 사람, 상황 등)
                - 누구나 쓸 수 있는 범용 단어

                기준: "이 단어를 다른 말로 바꾸면 지칭 대상이 달라지는가?" → Yes만 추출.

                변경 불가 표현을 한 줄에 하나씩, "- " 접두사로 작성하세요.
                예: - 김민수
                변경 불가 표현이 없으면 "없음"이라고만 작성하세요.""";
    }

    public String buildModel2UserMessage(Persona persona, String maskedText) {
        return "받는 사람: " + promptBuilder.getPersonaLabel(persona) + "\n\n원문:\n" + maskedText;
    }

    // ===== Model 3: Source Text Deconstruction (원문 해체) =====

    public String buildModel4SystemPrompt() {
        return """
                당신은 한국어 텍스트 구조 분석 전문가입니다.
                원문을 개별 전달 단위(메시지 항목)로 분리한 뒤, 부적절하거나 불필요한 항목을 제거하고,
                남은 항목을 가치 중립적·객관적으로 요약합니다.

                ## 프로세스
                1. **해체**: 원문을 의미 단위별로 분리 (인사, 사실 전달, 요청, 감정 표현 등)
                2. **필터링**: 다음에 해당하는 항목은 제거
                   - 책임 전가/남 탓 (예: "영업팀이 늦게 줘서 저도 어쩔 수 없었다" → 제거)
                   - 변명/선제 방어 (예: "저는 분명히 미리 말씀드렸는데" → 제거)
                     ※ 사실에 기반한 이유 설명은 유지 (예: "데이터 수급이 지연되어" → 유지)
                   - 비난, 도발, 공격적 표현 (예: "매번 이러시면 곤란합니다" → 제거)
                   - 사적 사유 TMI (예: "어제 회식 때문에 컨디션이 안 좋아서" → 제거)
                   - 체념/투덜거림 (예: "어쩔 수 없다", "억울하다" → 제거)
                3. **중립 요약**: 남은 항목을 객관적·사실 중심으로 한 줄씩 요약

                ## 판단 기준
                핵심 질문: "이 내용이 빠지면 받는 사람이 용건을 이해하는 데 문제가 생기는가?"
                - Yes → 유지 (사실 전달, 요청, 기한, 대안 등)
                - No → 제거 대상 검토 (감정 표현, 사적 사유, 변명 등)

                ## 출력 형식
                번호 매긴 리스트로 출력하세요. 각 항목은 가치 판단 없이 객관적으로 서술.
                예시:
                1. 보고서 기한 내 완료가 어렵다는 사실 전달
                2. 필요한 데이터 수급이 지연되고 있다는 상황 설명
                3. 수요일까지 완료하겠다는 대안 기한 제시

                원문의 모든 의미 단위를 빠짐없이 검토하되, 필터링 후 남는 항목만 출력하세요.""";
    }

    public String buildModel4UserMessage(String maskedText) {
        return "원문:\n" + maskedText;
    }

    // ===== Final Model: Transform with intermediate analysis =====

    private static final String FINAL_CORE_SYSTEM_PROMPT = """
            당신은 한국어 커뮤니케이션 전문가입니다. 사전 분석 결과를 활용하여 화자의 메시지를 재구성합니다.

            ## 핵심 원칙
            1. **사전 분석 참고**: 사전 분석에서 제공된 상황 분석, 화자 의도, 보존 표현을 참고하세요.
               단, 분석 내용을 그대로 문장화하지 말고, 변환의 방향을 잡는 참고 자료로만 사용하세요.
            2. **해체 → 재조립**: 원문을 그대로 다듬는 것이 아닙니다.
               원문의 핵심 사실과 화자 의도를 기반으로 메시지를 재구성하세요.
               원문의 부적절한 표현, 구조, 어순에 얽매이지 마세요.
            3. **원문 범위 엄수**: 원문에 언급하지 않은 사실, 약속, 제안, 대안은 절대 추가하지 마세요.
               단, 인사("안녕하세요"), 호칭, 마무리 인사, 서명은 관례에 맞게 추가할 수 있습니다.
               재구성은 원문의 핵심 메시지를 정중하게 전달하는 것이지, 새로운 내용을 창작하는 것이 아닙니다.
            4. **불필요한 말 제거**: 부적절 표현뿐 아니라, 해당 관계/상황에서 굳이 하지 않아도 되는 말,
               언급할 필요가 없는 내용도 과감히 빼세요. 핵심 용건에 집중하세요.
            5. **관점 유지**: 화자/청자를 정확히 구분. 화자의 관점을 절대 벗어나지 마세요.
            6. **고정 표현 절대 보존**: {{LOCKED_N}} 플레이스홀더와 고정 표현 매핑에 명시된 모든 표현은
               절대 수정/삭제/추가하지 마세요. 위치만 자연스럽게 조정 가능.
            7. **원문 해체 기반 재조립**: [원문 해체]에 나열된 항목만을 재조립의 뼈대로 사용하세요.
               해체 결과에 없는 내용은 절대 추가하지 마세요. 각 항목을 정중한 문장으로 풀어쓰되,
               항목에 없는 새로운 의미를 덧붙이지 마세요.
            8. **자연스러움**: "드리겠습니다" 연속 2회 이상 금지. 어미를 다양하게. 기계적 패턴 금지.

            ## 사전 분석 활용법
            - [상황 분석 및 화자 의도]: 맥락·톤·화자의 목적을 파악하는 참고 자료. 분석 문장을 그대로 쓰지 마세요.
            - [보존 필수 표현]: 나열된 고유명사/이름/파일명은 반드시 원형 그대로 사용.
            - [원문 해체]: 이 항목들이 재조립의 뼈대입니다. 여기 나열된 항목만 문장화하세요.
              해체 결과에 없는 내용은 원문에 있더라도 제거된 것이므로 포함하지 마세요.

            ## 예시
            원문: "팀장님 죄송한데 보고서 내일까지 못 끝낼 것 같아요. 어제 회식 때문에 오늘 컨디션이 안 좋아서 집중이 안 됐고, 솔직히 데이터도 아직 다 안 왔는데 {{LOCKED_0}}이 늦게 줘서 저도 어쩔 수가 없었습니다. 수요일까지 해서 드리면 안 될까요?"
            [원문 해체]:
            1. 보고서 기한 내 완료가 어렵다는 사실 전달
            2. 필요한 데이터 수급이 지연되고 있다는 상황 설명
            3. 수요일까지 완료하겠다는 대안 기한 제시
            변환 결과: "팀장님, 안녕하세요. 보고서 관련하여 말씀드립니다. 예정된 기한까지 완료가 어려운 상황이 되어 먼저 사과드립니다. {{LOCKED_0}} 측 데이터 수급이 지연되어 현재 작업을 마무리하지 못한 상태입니다. 수요일까지 완성본을 제출드려도 괜찮으시겠습니까? 일정에 차질을 드려 죄송합니다."
            (회식/컨디션 = TMI 삭제, "어쩔 수가 없었다" = 책임회피 삭제, {{LOCKED_0}} 보존, 해체 항목 3개만 재조립)

            ## 출력 규칙 (절대 준수)
            - 변환된 메시지 텍스트만 출력하세요. 분석/설명/이모지/메타 발언 절대 금지.
            - 첫 글자부터 바로 변환된 메시지. 앞뒤 부연 금지.
            - 문단 사이에만 줄바꿈(\\n\\n). 문단 내 문장은 줄바꿈 없이 이어서 작성. 한 문단 최대 4문장.
            - 원문 길이에 비례하는 자연스러운 분량.""";

    public String buildFinalSystemPrompt(Persona persona, List<SituationContext> contexts,
                                          ToneLevel toneLevel) {
        return promptBuilder.buildDynamicBlocks(FINAL_CORE_SYSTEM_PROMPT, persona, contexts, toneLevel);
    }

    public String buildFinalUserMessage(Persona persona, List<SituationContext> contexts,
                                         ToneLevel toneLevel, String senderInfo,
                                         String model1Result, String model2Result,
                                         String model4Result,
                                         List<LockedSpan> allLockedSpans) {
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

        // Inject intermediate analysis
        sb.append("\n--- 사전 분석 결과 ---\n");
        sb.append("[상황 분석 및 화자 의도]\n").append(model1Result).append("\n\n");
        sb.append("[보존 필수 표현]\n").append(model2Result).append("\n\n");
        sb.append("[원문 해체]\n").append(model4Result).append("\n\n");

        // Locked span mapping table
        if (allLockedSpans != null && !allLockedSpans.isEmpty()) {
            sb.append("[고정 표현 매핑]\n");
            for (LockedSpan span : allLockedSpans) {
                sb.append(span.placeholder()).append(" = ").append(span.originalText()).append("\n");
            }
        }

        sb.append("--- 사전 분석 끝 ---\n");

        return sb.toString();
    }
}
