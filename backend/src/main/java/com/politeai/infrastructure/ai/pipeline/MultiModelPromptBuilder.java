package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.*;
import com.politeai.infrastructure.ai.PromptBuilder;
import com.politeai.infrastructure.ai.pipeline.template.StructureSection;
import com.politeai.infrastructure.ai.pipeline.template.StructureTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds prompts for the v2 pipeline:
 * - Final Model: Transform using 3-tier labeled structure analysis (JSON segment format)
 *   with dynamic template sections, AVOID_PHRASES, and micro-irregularity instructions.
 */
@Component
@RequiredArgsConstructor
public class MultiModelPromptBuilder {

    private final PromptBuilder promptBuilder;

    /**
     * Ordered segment DTO for the Final model user message.
     * Created by MultiModelPipeline.buildFinalPrompt().
     */
    private static final Pattern PLACEHOLDER_IN_TEXT = Pattern.compile("\\{\\{[A-Z_]+_\\d+\\}\\}");

    public record OrderedSegment(
            String id,
            int order,
            String tier,
            String label,
            String text,       // null for RED
            String dedupeKey,  // null for RED
            List<String> mustInclude  // placeholders in YELLOW segments that must appear in output
    ) {
        /** Backwards-compatible constructor without mustInclude */
        public OrderedSegment(String id, int order, String tier, String label, String text, String dedupeKey) {
            this(id, order, tier, label, text, dedupeKey, List.of());
        }
    }

    /**
     * Extract {{TYPE_N}} placeholders from segment text.
     */
    static List<String> extractPlaceholders(String text) {
        if (text == null) return List.of();
        List<String> result = new ArrayList<>();
        Matcher m = PLACEHOLDER_IN_TEXT.matcher(text);
        while (m.find()) result.add(m.group());
        return result;
    }

    // ===== Final Model: Transform with 3-tier structure analysis (JSON format) =====

    private static final String FINAL_CORE_SYSTEM_PROMPT = """
            당신은 한국어 커뮤니케이션 전문가입니다. 구조 분석된 세그먼트 배열(JSON)을 활용하여 화자의 메시지를 재구성합니다.

            ## 입력 형식

            JSON 래퍼 객체:
            - `meta`: 수신자/상황/톤/발신자/template/sections
            - `segments`: 배열 (id, order, tier, label, text, dedupeKey)
            - `placeholders`: 고정 표현 매핑 ({{TYPE_N}} → 원본)

            order = 원문 위치 기반 정렬. 문맥 이해는 order 기준, 재구성은 섹션 템플릿 기준.
            dedupeKey가 동일한 세그먼트 = 중복.

            ## 플레이스홀더 규칙 (위반 시 검증 ERROR → 자동 재시도)
            - `{{TYPE_N}}` 형식(예: {{DATE_1}}, {{PHONE_1}}, {{EMAIL_1}}, {{ISSUE_TICKET_1}})을 출력에 **반드시 그대로 포함**
            - 서버가 후처리로 실제 값으로 복원하므로, 값으로 풀어 쓰거나 새로 만들거나 삭제하면 안 됨
            - segments 배열의 text에 포함된 {{TYPE_N}}은 해당 세그먼트를 재작성할 때 반드시 출력 문장에 포함해야 함
            - ⚠️ **YELLOW 세그먼트 주의**: 재작성 시 문장 구조를 바꾸더라도 원문 text 안의 플레이스홀더를 빠뜨리지 말 것. 쿠션/방향 문장을 추가하더라도 플레이스홀더가 포함된 사실 부분은 반드시 유지
            - ⚠️ `mustInclude` 배열이 있는 세그먼트: 해당 플레이스홀더를 출력에 반드시 포함해야 함. YELLOW 재작성으로 문장을 바꾸더라도 mustInclude 항목은 절대 생략 금지
            - ⚠️ GREEN 세그먼트 재구성 시에도 플레이스홀더 누락 금지
            - **최종 검증**: placeholders 객체의 모든 키가 출력에 정확히 1회 이상 존재해야 함. 하나라도 누락되면 검증 실패

            ## 3계층 처리 규칙

            ### GREEN (내용 보존, 표현 재구성) — 적극 다듬기
            원문 문장을 재사용하지 말고 의미를 유지한 **새 문장**으로 재구성.
            **⚠️ 구어체/비표준 맞춤법 해석 필수**: 원문이 비표준 맞춤법이나 구어체여도 의미를 정확히 파악한 후 재구성.
            예: "안 쳐한거임"="노력하지 않은 것", "시험 망함"="성적이 부진함", "이 꼴"="이 상황", "갈아엎다"="전면 개편하다"
            라벨별 전략:
            - CORE_FACT → 사실·수치·날짜·원인 정확 보존 + 명확한 전달체
            - CORE_INTENT → 핵심 의도·제안·대안 보존 + 명확하고 전문적으로. **구어체 표현을 비즈니스체로 전환** (예: "갈아엎다"→"개선하다/재구성하다")
            - REQUEST → 요청 대상·행동·기한·조건 보존. 기한/긴급도 완곡화 금지, 표현만 정중하게
            - APOLOGY → 사과 대상·이유 보존 + 진정성 있는 정중한 사과
            - COURTESY → 관계에 맞는 자연스러운 인사 (맨앞/끝 1줄 허용)

            ⚠️ GREEN 의미 불변 제약 (위반 시 할루시네이션):
            - 요청의 대상/행동/기한/조건, 사실(수치/날짜/원인/결과) 변경·추가·삭제 금지
            - **원문의 의미를 다른 의미로 바꾸지 말 것** (예: "성적이 나쁘다"를 "시험을 보지 않았다"로 바꾸면 안 됨)
            - 원문에 없는 행동 약속(후속 조치/재발 방지/내부 확인 등) 추가 금지
            - 각 GREEN 세그먼트 최대 1~3문장, 220자 이내

            ### YELLOW (의미 보존 + 표현 재작성 + 쿠션 삽입)
            핵심 의미(원인, 책임, 요청 등)는 반드시 보존. 전달 방식만 변경.
            ⚠️ 창작 금지: 원문에 없는 구체적 사실/핑계/약속/수치 추가 금지
            허용 범위: 일반적 비즈니스 예의 표현(인사, 공감, 해결 의지)은 추가 가능

            **공통 3단계 패턴 (모든 YELLOW 라벨 필수)**:
            ① 쿠션 → ② 사실 → ③ 방향
            쿠션 없이 사실/지적부터 시작하지 말 것. 반드시 공감·양해·상황 인정 표현으로 시작.

            **금지 패턴** (직접 표현 → 간접 전환):
            ✗ 직접 귀책: "귀사 문제로", "~측 실수로" → ✓ 주어를 상황/시스템으로: "확인 결과 ~부분에서 차이가 발생하여"
            ✗ 직접 거부: "그건 어렵습니다", "과한 것 같고" → ✓ 대안 방향: "~방향으로 검토해 보면 어떨까 합니다"
            ✗ 직접 감정: "힘듭니다", "답답합니다" → ✓ 간접 표현: "부담이 되는 부분이 있어", "우려가 됩니다"
            ✗ 직접 지적: "~하지 마세요", "또 ~하셨는데" → ✓ 요청 전환: "~해 주시면 감사하겠습니다"

            **라벨별 재작성 전략**:
            - ACCOUNTABILITY:
              ① 쿠션: persona에 맞는 자연스러운 도입 표현 사용 (아래 persona 블록 참조). "내부 확인 결과" 금지
              ② 사실: 주어를 상대방→상황/시스템/프로세스로 전환. 인과관계(A→B→결과) 보존하되 비난 제거
              ③ 방향: 원인 공유 목적임을 명시
              ✗ 금지 예시: "고객님의 서버 설정에 이상이 있어" (직접 귀책)
              ✓ 올바른 예시: "확인 결과, 서버 설정 부분에서 차이가 발생한 것으로 파악되었습니다" (주어=시스템)
            - SELF_JUSTIFICATION:
              ① 쿠션: "말씀 주신 부분 관련하여" / "해당 건 배경을 말씀드리면"
              ② 사실: 업무 맥락(일정/원인/의존성) 보존. 방어 구조("어쩔 수 없었다"/"사실 ~했다" 류) 제거
              ③ 방향: 해결 의지 마무리 ("향후 ~하도록 하겠습니다")
              ✗ 금지 예시: "사실 어제도 새벽 3시까지 작업했습니다" (방어적 "사실"+고생 강조)
              ✓ 올바른 예시: "해당 건에 대해 지속 작업을 진행하고 있습니다" (사실만, 방어 프레임 제거)
            - NEGATIVE_FEEDBACK:
              ① 쿠션: "~해 주신 점 감사합니다" / "~부분은 잘 진행되고 있습니다" (긍정 인정 선행)
              ② 사실: 개선 필요 사항을 요청 형태로 전환. 지적("~하지 마세요")→요청("~해 주시면"). **원문에 언급된 구체적 대상(파일명, 시스템명 등)은 반드시 포함**
              ③ 방향: 기대 효과 제시 ("그러면 ~에 도움이 될 것 같습니다"). 심각도·긴급도 유지
              ※ CLIENT/OFFICIAL persona 추가 규칙: 직접 거부/판단 표현("과한 것으로 판단", "어렵습니다", "불가합니다") 금지. 원인 파악→조치 안내 순서로 전환. 예: "우선 원인을 정확히 파악한 후 적절한 조치를 안내드리겠습니다"
              ✗ 금지 예시: "솔직히 이런 일정이면 퀄리티가 떨어집니다" (원문 불만 그대로)
              ✓ 올바른 예시: "일정과 품질의 균형에 대해 조율이 필요할 것 같습니다" (불만→건설적 요청)
            - EMOTIONAL:
              ① 쿠션: "솔직히 말씀드리면" / "양해 말씀 구하며"
              ② 사실: 감정을 삭제하지 말고 **반드시 간접 표현으로 전환**
              ③ 방향: 협조 의지 마무리 ("함께 좋은 방향을 찾을 수 있으면 합니다")
              ✗ 금지 예시: "억울한 감정이 드는 부분도 있지만" (직접 감정 표현 유지)
              ✓ 올바른 예시: "부담이 되는 부분이 있었지만" (감정→간접 상태 표현)
            - EXCESS_DETAIL:
              ① 중복 제거 + 추측→가능성 전환 ("~일 가능성이 있어 보입니다")
              ② 논리 체인(A→B→C) 압축 보존 — 핵심 인과만 남기고 반복/부연 제거
              ③ **구어체→비즈니스체 전환 필수**: "꼬여서"→"이슈가 발생하여", "난리"→"문제가 발생"

            ### RED (완전 삭제 — 흔적 없음)
            text가 null → 내용을 알 수 없음.
            최종 출력에서 RED의 존재 자체를 언급/암시 금지 ("일부 내용을 삭제했습니다", "[삭제됨]" 등 표현 금지).
            RED 자리에 새 문장 만들지 않고, 인접 블록을 자연스럽게 연결.
            ⚠️ **RED 추론/재생성 절대 금지**: 인접 YELLOW/GREEN 세그먼트의 맥락에서 RED 내용을 추론하여 새로 생성하지 말 것.
            예: RED가 "개인 사정" 관련이었더라도 출력에 "개인적인 사정도 있었지만" 같은 문구 생성 금지.
            RED의 text는 null이므로 그 주제/내용을 암시하는 어떤 표현도 출력하면 안 됨.

            ## 중복 제거 규칙 (dedupeKey + order 기준)
            - dedupeKey 동일 → 가장 구체적인 것 하나만 사용. 구체성 동등하면 order 큰 것(원문상 뒤) 채택
            - 동일 REQUEST 반복 → 가장 명확한 것 하나만. 기한/조건이 다르면(dedupeKey 다름) 각각 보존
            - 동일 APOLOGY 반복 → 하나로 통합
            - YELLOW 내 동일 변명/해명 반복 → 핵심 원인 사실만 남기고 압축

            ## 연결 권한
            접속사/구두점/전환표현 자유 추가:
            - 지시어("이", "해당", "해당 건에 대해") 자유 사용
            - 인과 연결("이에 따라", "이로 인해") 자유 사용
            - 블록 전환 시 자연스러운 연결
            - 단, 새로운 사실/약속 추가 금지

            ## 핵심 원칙
            1. **해체 → 재구성**: GREEN을 뼈대로, 새 문장으로 섹션 템플릿에 배치. YELLOW는 라벨별 전략으로 재작성. RED는 무시.
            2. **원문 범위 + 쿠션 표현**: 원문에 없는 구체적 사실/약속/수치 추가 금지. 원문에 없는 소속/학과/부서명/직책/직급 추가 금지. [이름], [소속], [학과] 등 빈칸형 플레이스홀더도 원문에 없으면 사용 금지. 인사/공감/해결 의지 등 비즈니스 예의 표현은 적극 활용.
            3. **중복 통합 & 간결화**: dedupeKey 기반 중복 제거. RED 제거 자리는 자연스럽게 연결. 접속사/수식어 최소화. GREEN 핵심 사실/의도/수치는 절대 누락 금지.
            4. **관점 유지**: 화자/청자 정확히 구분. 화자 관점 벗어나지 말 것.
            5. **고정 표현 절대 보존**: {{TYPE_N}} 플레이스홀더 수정/삭제/추가 금지.
            6. **자연스러움**: "드리겠습니다" 연속 2회 이상 금지. 어미 다양하게. 기계적 패턴 금지.
            7. **톤의 온도**: persona와 toneLevel이 결정. POLITE라도 자연스러운 공손함이지, 격식 문서체 아님.

            ## 금지 표현 (AI스러운 관용구)
            다음 표현은 사용 금지. 의미가 필요하면 다른 표현으로 대체:
            - "소중한 피드백 감사합니다" / "소중한 의견"
            - "양해 부탁드립니다" (2회 이상)
            - "필요한 조치를 취하겠습니다" / "적극적으로 대응하겠습니다"
            - "검토 후 회신드리겠습니다" (구체적 시점 없이)
            - "최선을 다하겠습니다" / "만전을 기하겠습니다"
            - "앞으로 이런 일이 없도록" (구체성 없이)
            - "귀하의 ~에 감사드립니다"
            - "다시 한번 사과의 말씀을 드립니다"
            - "불편을 끼쳐 드려 대단히 죄송합니다" (대단히+죄송 과잉)
            - "원활한 소통을 위해"
            - "내부 확인 결과" / "내부적으로 확인한 결과" / "내부 검토 결과"

            ## 자연스러움 규칙 (필수)
            - 문장 길이를 일정하게 맞추지 말 것 — 짧은 문장과 긴 문장이 섞여야 자연스러움
            - 불필요한 격식 문구(~에 대하여, ~을 통하여) 최소화 — 구어에 가까운 비즈니스체 지향
            - 같은 표현/어미 반복 금지 — 특히 "~드리겠습니다" 연속 2회 이상 금지
            - 한 문단이 다른 문단보다 현저히 짧아도 괜찮음 — 균등 분배 강제 금지
            - 접속사 남용 금지 — "또한", "아울러", "더불어" 같은 나열 접속사 연속 사용 금지
            - 섹션이 1문장으로 충분하면 1문장만. 억지로 늘리지 말 것

            ## 출력 규칙 (절대 준수)
            - 변환된 메시지 텍스트만 출력. 분석/설명/이모지/메타 발언 절대 금지.
            - 첫 글자부터 바로 변환된 메시지. 앞뒤 부연 금지.
            - 문단 사이에만 줄바꿈(\\n\\n). 문단 내 문장은 줄바꿈 없이 이어서 작성. 한 문단 최대 4문장.
            - 원문 길이에 비례하는 자연스러운 분량.""";

    /**
     * Build template section block for system prompt.
     */
    private String buildTemplateSectionBlock(StructureTemplate template, List<StructureSection> effectiveSections) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 메시지 구조: ").append(template.name());
        sb.append("\n\n아래 섹션 순서로 메시지를 재구성하세요. 각 섹션에 해당 세그먼트가 없으면 자연스럽게 생략합니다.\n");

        for (StructureSection section : effectiveSections) {
            sb.append("\n### ").append(section.getLabel());
            sb.append("\n- 목적: ").append(section.getInstruction());
            sb.append("\n- 분량: ").append(section.getLengthHint());
            if (!section.getExpressionPool().isEmpty()) {
                sb.append("\n- 시작 표현 예시 (변형하여 사용): ")
                        .append(String.join(", ", section.getExpressionPool()));
            }
            sb.append("\n");
        }

        if (template.constraints() != null && !template.constraints().isBlank()) {
            sb.append("\n").append(template.constraints());
        }

        return sb.toString();
    }

    public String buildFinalSystemPrompt(Persona persona, List<SituationContext> contexts,
                                          ToneLevel toneLevel,
                                          StructureTemplate template,
                                          List<StructureSection> effectiveSections) {
        String basePrompt = FINAL_CORE_SYSTEM_PROMPT + buildTemplateSectionBlock(template, effectiveSections);
        return promptBuilder.buildDynamicBlocks(basePrompt, persona, contexts, toneLevel);
    }

    public String buildFinalUserMessage(Persona persona, List<SituationContext> contexts,
                                         ToneLevel toneLevel, String senderInfo,
                                         List<OrderedSegment> orderedSegments,
                                         List<LockedSpan> allLockedSpans,
                                         RelationIntentService.RelationIntentResult relationIntent,
                                         String summaryText,
                                         StructureTemplate template,
                                         List<StructureSection> effectiveSections) {
        StringBuilder sb = new StringBuilder();

        // Optional: Relation/Intent analysis
        if (relationIntent != null &&
                (!relationIntent.relation().isEmpty() || !relationIntent.intent().isEmpty() || !relationIntent.stance().isEmpty())) {
            sb.append("--- 관계/의도 분석 ---\n");
            if (!relationIntent.relation().isEmpty()) sb.append("관계: ").append(relationIntent.relation()).append("\n");
            if (!relationIntent.intent().isEmpty()) sb.append("의도: ").append(relationIntent.intent()).append("\n");
            if (!relationIntent.stance().isEmpty()) sb.append("태도: ").append(relationIntent.stance()).append("\n");
            sb.append("\n");
        }

        // Optional: Summary
        if (summaryText != null && !summaryText.isBlank()) {
            sb.append("[요약]: ").append(summaryText).append("\n\n");
        }

        // Build JSON wrapper object
        sb.append("```json\n");
        sb.append("{\n");

        // meta (now includes template and sections)
        String contextStr = contexts.stream()
                .map(promptBuilder::getContextLabel)
                .collect(Collectors.joining(", "));
        String sectionsStr = effectiveSections.stream()
                .map(StructureSection::name)
                .collect(Collectors.joining(","));

        sb.append("  \"meta\": {\n");
        sb.append("    \"receiver\": \"").append(escapeJson(promptBuilder.getPersonaLabel(persona))).append("\",\n");
        sb.append("    \"context\": \"").append(escapeJson(contextStr)).append("\",\n");
        sb.append("    \"tone\": \"").append(escapeJson(promptBuilder.getToneLabel(toneLevel))).append("\"");
        if (senderInfo != null && !senderInfo.isBlank()) {
            sb.append(",\n    \"sender\": \"").append(escapeJson(senderInfo)).append("\"");
        }
        sb.append(",\n    \"template\": \"").append(escapeJson(template.id())).append("\"");
        sb.append(",\n    \"sections\": \"").append(escapeJson(sectionsStr)).append("\"");
        sb.append("\n  },\n");

        // segments
        sb.append("  \"segments\": [\n");
        for (int i = 0; i < orderedSegments.size(); i++) {
            OrderedSegment seg = orderedSegments.get(i);
            sb.append("    {\"id\":\"").append(seg.id())
                    .append("\",\"order\":").append(seg.order())
                    .append(",\"tier\":\"").append(seg.tier())
                    .append("\",\"label\":\"").append(seg.label()).append("\"");
            if (seg.text() != null) {
                sb.append(",\"text\":\"").append(escapeJson(seg.text())).append("\"");
                sb.append(",\"dedupeKey\":\"").append(escapeJson(seg.dedupeKey())).append("\"");
                // Add mustInclude for YELLOW segments with placeholders
                if (!seg.mustInclude().isEmpty()) {
                    sb.append(",\"mustInclude\":[");
                    sb.append(seg.mustInclude().stream()
                            .map(p -> "\"" + escapeJson(p) + "\"")
                            .collect(Collectors.joining(",")));
                    sb.append("]");
                }
            } else {
                sb.append(",\"text\":null,\"dedupeKey\":null");
            }
            sb.append("}");
            if (i < orderedSegments.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // placeholders
        sb.append("  \"placeholders\": {");
        if (allLockedSpans != null && !allLockedSpans.isEmpty()) {
            sb.append("\n");
            for (int i = 0; i < allLockedSpans.size(); i++) {
                LockedSpan span = allLockedSpans.get(i);
                sb.append("    \"").append(span.placeholder()).append("\": \"")
                        .append(escapeJson(span.originalText())).append("\"");
                if (i < allLockedSpans.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ");
        }
        sb.append("}\n");

        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
