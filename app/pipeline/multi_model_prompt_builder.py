"""Builds prompts for the Final model (LLM #2).

Final Model: Transform using 3-tier labeled structure analysis (JSON segment format)
with dynamic template sections, AVOID_PHRASES, and micro-irregularity instructions.
"""

import re
from dataclasses import dataclass, field

from app.models.domain import LockedSpan
from app.models.enums import Persona, SituationContext, ToneLevel
from app.pipeline import prompt_builder
from app.pipeline.gating.situation_analysis_service import SituationAnalysisResult
from app.pipeline.template.structure_template import StructureSection, StructureTemplate

_PLACEHOLDER_IN_TEXT = re.compile(r"\{\{[A-Z_]+_\d+\}\}")


@dataclass(frozen=True)
class OrderedSegment:
    id: str
    order: int
    tier: str
    label: str
    text: str | None  # None for RED
    dedupe_key: str | None  # None for RED
    must_include: list[str] = field(default_factory=list)


def extract_placeholders(text: str | None) -> list[str]:
    """Extract {{TYPE_N}} placeholders from segment text."""
    if text is None:
        return []
    return [m.group() for m in _PLACEHOLDER_IN_TEXT.finditer(text)]


# ===== Final Model system prompt =====

FINAL_CORE_SYSTEM_PROMPT = (
    "당신은 한국어 커뮤니케이션 전문가입니다. "
    "구조 분석된 세그먼트 배열(JSON)을 활용하여 화자의 메시지를 재구성합니다.\n\n"
    "## 입력 형식\n\n"
    "JSON 래퍼 객체:\n"
    "- `meta`: 수신자/상황/톤/발신자/template/sections\n"
    "- `segments`: 배열 (id, order, tier, label, text, dedupeKey)\n"
    "- `placeholders`: 고정 표현 매핑 ({{TYPE_N}} → 원본)\n\n"
    "order = 원문 위치 기반 정렬. 문맥 이해는 order 기준, 재구성은 섹션 템플릿 기준.\n"
    "dedupeKey가 동일한 세그먼트 = 중복.\n\n"
    "## 플레이스홀더 규칙 (위반 시 검증 ERROR → 자동 재시도)\n"
    "- `{{TYPE_N}}` 형식(예: {{DATE_1}}, {{PHONE_1}}, {{EMAIL_1}}, "
    "{{ISSUE_TICKET_1}})을 출력에 **반드시 그대로 포함**\n"
    "- 서버가 후처리로 실제 값으로 복원하므로, 값으로 풀어 쓰거나 새로 만들거나 삭제하면 안 됨\n"
    "- segments 배열의 text에 포함된 {{TYPE_N}}은 해당 세그먼트를 재작성할 때 반드시 출력 문장에 포함해야 함\n"
    "- ⚠️ **YELLOW 세그먼트 주의**: 재작성 시 문장 구조를 바꾸더라도 "
    "원문 text 안의 플레이스홀더를 빠뜨리지 말 것. "
    "쿠션/방향 문장을 추가하더라도 플레이스홀더가 포함된 사실 부분은 반드시 유지\n"
    "- ⚠️ `mustInclude` 배열이 있는 세그먼트: 해당 플레이스홀더를 출력에 반드시 포함해야 함. "
    "YELLOW 재작성으로 문장을 바꾸더라도 mustInclude 항목은 절대 생략 금지\n"
    "- ⚠️ GREEN 세그먼트 재구성 시에도 플레이스홀더 누락 금지\n"
    "- **최종 검증**: placeholders 객체의 모든 키가 출력에 정확히 1회 이상 존재해야 함. 하나라도 누락되면 검증 실패\n\n"
    "## 출력 규칙 (절대 준수)\n"
    "- 변환된 메시지 텍스트만 출력. 분석/설명/이모지/메타 발언 절대 금지.\n"
    "- 첫 글자부터 바로 변환된 메시지. 앞뒤 부연 금지.\n"
    "- 문단 사이에만 줄바꿈(\\n\\n). 문단 내 문장은 줄바꿈 없이 이어서 작성. 한 문단 최대 4문장.\n"
    "- 원문 길이에 비례하는 자연스러운 분량.\n\n"
    "## 금지 표현 (AI스러운 관용구)\n"
    "다음 표현은 사용 금지. 의미가 필요하면 다른 표현으로 대체:\n"
    '- "소중한 피드백 감사합니다" / "소중한 의견"\n'
    '- "양해 부탁드립니다" (2회 이상)\n'
    '- "필요한 조치를 취하겠습니다" / "적극적으로 대응하겠습니다"\n'
    '- "검토 후 회신드리겠습니다" (구체적 시점 없이)\n'
    '- "최선을 다하겠습니다" / "만전을 기하겠습니다"\n'
    '- "앞으로 이런 일이 없도록" (구체성 없이)\n'
    '- "귀하의 ~에 감사드립니다"\n'
    '- "다시 한번 사과의 말씀을 드립니다"\n'
    '- "불편을 끼쳐 드려 대단히 죄송합니다" (대단히+죄송 과잉)\n'
    '- "원활한 소통을 위해"\n'
    '- "내부 확인 결과" / "내부적으로 확인한 결과" / "내부 검토 결과"\n\n'
    "## 자연스러움 규칙 (필수)\n"
    "- 문장 길이를 일정하게 맞추지 말 것 — 짧은 문장과 긴 문장이 섞여야 자연스러움\n"
    "- 불필요한 격식 문구(~에 대하여, ~을 통하여) 최소화 — 구어에 가까운 비즈니스체 지향\n"
    '- 같은 종결어미 반복 금지 — "~드리겠습니다" 연속 2회 금지, "~습니다" 연속 3회 금지. '
    '쿠션 삽입으로 문장이 늘면 중간을 명사형 종결 또는 연결형("~하며", "~하여")으로 전환\n'
    "- 한 문단이 다른 문단보다 현저히 짧아도 괜찮음 — 균등 분배 강제 금지\n"
    '- 접속사 남용 금지 — "또한", "아울러", "더불어" 같은 나열 접속사 연속 사용 금지\n'
    '- 구어체 접속사 금지 — "어쨌든", "아무튼", "걍", "근데"는 출력에 절대 사용 금지. '
    '대체: "어쨌든/아무튼"→생략 또는 "이에 따라"/"정리하면", "근데"→"다만"/"한편"\n'
    "- 섹션이 1문장으로 충분하면 1문장만. 억지로 늘리지 말 것\n\n"
    "## 3계층 처리 규칙\n\n"
    "### GREEN (내용 보존, 표현 재구성) — 적극 다듬기\n"
    "원문 문장을 재사용하지 말고 의미를 유지한 **새 문장**으로 재구성.\n"
    "**⚠️ 구어체/비표준 맞춤법 해석 필수**: 원문이 비표준 맞춤법이나 구어체여도 의미를 정확히 파악한 후 재구성.\n"
    '예: "안 쳐한거임"="노력하지 않은 것", "시험 망함"="성적이 부진함", "이 꼴"="이 상황", "갈아엎다"="전면 개편하다"\n'
    "라벨별 전략:\n"
    "- CORE_FACT → 사실·수치·날짜·원인 정확 보존 + 명확한 전달체\n"
    '- CORE_INTENT → 핵심 의도·제안·대안 보존 + 명확하고 전문적으로. '
    '**구어체 표현을 비즈니스체로 전환** (예: "갈아엎다"→"개선하다/재구성하다")\n'
    "- REQUEST → 요청 대상·행동·기한·조건 보존. 기한/긴급도 완곡화 금지, 표현만 정중하게\n"
    "- APOLOGY → 사과 대상·이유 보존 + 진정성 있는 정중한 사과\n"
    "- COURTESY → 관계에 맞는 자연스러운 인사 (맨앞/끝 1줄 허용)\n\n"
    "⚠️ GREEN 의미 불변 제약 (위반 시 할루시네이션):\n"
    "- 요청의 대상/행동/기한/조건, 사실(수치/날짜/원인/결과) 변경·추가·삭제 금지\n"
    '- **원문의 의미를 다른 의미로 바꾸지 말 것** (예: "성적이 나쁘다"를 "시험을 보지 않았다"로 바꾸면 안 됨)\n'
    "- 원문에 없는 행동 약속(후속 조치/재발 방지/내부 확인 등) 추가 금지\n"
    "- 각 GREEN 세그먼트 최대 1~3문장, 220자 이내\n\n"
    "### YELLOW (의미 보존 + 표현 재작성 + 쿠션 삽입)\n"
    "핵심 의미(원인, 책임, 요청 등)는 반드시 보존. 전달 방식만 변경.\n"
    "⚠️ 창작 금지: 원문에 없는 구체적 사실/핑계/약속/수치 추가 금지\n"
    "허용 범위: 일반적 비즈니스 예의 표현(인사, 공감, 해결 의지)은 추가 가능\n\n"
    "**쿠션 전략 블록이 제공되면 해당 지침을 우선 적용.** "
    "쿠션 전략의 접근법/표현/금지가 아래 일반 규칙과 상충 시 쿠션 전략이 우선.\n\n"
    "**공통 3단계 패턴 (모든 YELLOW 라벨 필수)**:\n"
    "① 쿠션 → ② 사실 → ③ 방향\n"
    "쿠션 없이 사실/지적부터 시작하지 말 것. 반드시 공감·양해·상황 인정 표현으로 시작.\n\n"
    "**금지 패턴** (직접 표현 → 간접 전환):\n"
    '✗ 직접 귀책: "귀사 문제로", "~측 실수로" → ✓ 주어를 상황/시스템으로: "확인 결과 ~부분에서 차이가 발생하여"\n'
    '✗ 직접 거부: "그건 어렵습니다", "과한 것 같고" → ✓ 대안 방향: "~방향으로 검토해 보면 어떨까 합니다"\n'
    '✗ 직접 감정: "힘듭니다", "답답합니다" → ✓ 간접 표현: "부담이 되는 부분이 있어", "우려가 됩니다"\n'
    '✗ 직접 지적: "~하지 마세요", "또 ~하셨는데" → ✓ 요청 전환: "~해 주시면 감사하겠습니다"\n\n'
    "**라벨별 재작성 전략**:\n"
    "- ACCOUNTABILITY:\n"
    '  ① 쿠션: persona에 맞는 자연스러운 도입 표현 사용 (아래 persona 블록 참조). "내부 확인 결과" 금지\n'
    "  ② 사실: 주어를 상대방→상황/시스템/프로세스로 전환. 인과관계(A→B→결과) 보존하되 비난 제거\n"
    "  ③ 방향: 원인 공유 목적임을 명시\n"
    "  ✗ 개인명 비난 금지: 특정 인물/부서를 지목하여 비난하는 표현 그대로 유지 금지. "
    "주어를 시스템/프로세스/상황으로 전환\n"
    "  ✗ 귀책 직접 지목 금지: \"~측 문제\", \"~측 실수\" 등 직접 귀책 표현 금지\n"
    "- SELF_JUSTIFICATION:\n"
    '  ① 쿠션: "말씀 주신 부분 관련하여" / "해당 건 배경을 말씀드리면"\n'
    '  ② 사실: 업무 맥락(일정/원인/의존성) 보존. 방어 구조("어쩔 수 없었다"/"사실 ~했다" 류) 제거\n'
    '  ③ 방향: 해결 의지 마무리 ("향후 ~하도록 하겠습니다")\n'
    '  ✗ 직접 감정어 금지: "억울하다", "서운하다", "답답하다" 등 감정 직접 표현 금지. '
    '간접 표현으로 전환 ("부담이 되는 부분이 있어")\n'
    '  ✗ "사실 어제도 새벽 3시까지 작업했습니다" → ✓ "해당 건에 대해 지속 작업을 진행하고 있습니다"\n'
    "- NEGATIVE_FEEDBACK:\n"
    "  ① 쿠션: 긍정 인정 선행 → ② 개선 필요 사항을 요청 형태로 전환. "
    "**원문에 언급된 구체적 대상(파일명, 시스템명 등)은 반드시 포함**\n"
    '  ③ 방향: 기대 효과 제시. 심각도·긴급도 유지\n'
    "  ✗ 원문 톤 잔존 금지: 원문의 부정적 톤/비난 어투를 그대로 유지하지 말 것. "
    "반드시 요청/개선 프레임으로 전환\n"
    '  ✗ 직접 거부 금지: "그건 어렵습니다", "과한 것 같고" 등 직접 거부/판단 표현 금지\n'
    '  ※ CLIENT/OFFICIAL: 직접 거부/판단 표현 금지. 원인 파악→조치 안내 순서로 전환\n'
    "- EMOTIONAL:\n"
    "  ① 쿠션 → ② 감정을 삭제하지 말고 **간접 표현으로 전환** → ③ 협조 의지 마무리\n"
    "- EXCESS_DETAIL:\n"
    '  ① 중복 제거 + 추측→가능성 전환 ("~일 가능성이 있어 보입니다") + persona별 확인 표현 추가\n'
    "  ② 논리 체인(A→B→C) 압축 보존 — 핵심 인과만 남기고 반복/부연 제거\n"
    '  ③ **구어체→비즈니스체 전환 필수**: "꼬여서"→"이슈가 발생하여", "난리"→"문제가 발생"\n\n'
    "**YELLOW 과확장 방지**: YELLOW 재작성 + 쿠션 결과가 원문 YELLOW 세그먼트의 2배를 넘으면 안 됨. "
    "쿠션 1문장 이내, 사실은 원문 분량 유지, 방향 1문장 이내.\n\n"
    "### RED (완전 삭제 — 흔적 없음)\n"
    "text가 null → 내용을 알 수 없음.\n"
    '최종 출력에서 RED의 존재 자체를 언급/암시 금지 ("일부 내용을 삭제했습니다", "[삭제됨]" 등 표현 금지).\n'
    "RED 자리에 새 문장 만들지 않고, 인접 블록을 자연스럽게 연결.\n"
    "⚠️ **RED 추론/재생성 절대 금지**: 인접 YELLOW/GREEN 세그먼트의 맥락에서 RED 내용을 추론하여 새로 생성하지 말 것.\n"
    '예: RED가 "개인 사정" 관련이었더라도 출력에 "개인적인 사정도 있었지만" 같은 문구 생성 금지.\n'
    "RED의 text는 null이므로 그 주제/내용을 암시하는 어떤 표현도 출력하면 안 됨.\n\n"
    "## 중복 제거 규칙 (dedupeKey + order 기준)\n"
    "- dedupeKey 동일 → 가장 구체적인 것 하나만 사용. 구체성 동등하면 order 큰 것(원문상 뒤) 채택\n"
    "- 동일 REQUEST 반복 → 가장 명확한 것 하나만. 기한/조건이 다르면(dedupeKey 다름) 각각 보존\n"
    "- 동일 APOLOGY 반복 → 하나로 통합\n"
    "- YELLOW 내 동일 변명/해명 반복 → 핵심 원인 사실만 남기고 압축\n\n"
    "## 연결 권한\n"
    "접속사/구두점/전환표현 자유 추가:\n"
    '- 지시어("이", "해당", "해당 건에 대해") 자유 사용\n'
    '- 인과 연결("이에 따라", "이로 인해") 자유 사용\n'
    "- 블록 전환 시 자연스러운 연결\n"
    "- 단, 새로운 사실/약속 추가 금지\n\n"
    "## 핵심 원칙\n"
    "1. **해체 → 재구성**: GREEN을 뼈대로, 새 문장으로 섹션 템플릿에 배치. "
    "YELLOW는 라벨별 전략으로 재작성. RED는 무시.\n"
    "2. **원문 범위 + 쿠션 표현**: 원문에 없는 구체적 사실/약속/수치 추가 금지. "
    "원문에 없는 소속/학과/부서명/직책/직급 추가 금지. "
    "[이름], [소속], [학과] 등 빈칸형 플레이스홀더도 원문에 없으면 사용 금지. "
    "인사/공감/해결 의지 등 비즈니스 예의 표현은 적극 활용.\n"
    "3. **중복 통합 & 간결화**: dedupeKey 기반 중복 제거. "
    "RED 제거 자리는 자연스럽게 연결. 접속사/수식어 최소화. "
    "GREEN 핵심 사실/의도/수치는 절대 누락 금지.\n"
    "4. **관점 유지**: 화자/청자 정확히 구분. 화자 관점 벗어나지 말 것.\n"
    "5. **고정 표현 절대 보존**: {{TYPE_N}} 플레이스홀더 수정/삭제/추가 금지.\n"
    '6. **자연스러움**: "드리겠습니다" 연속 2회 이상 금지. 어미 다양하게. 기계적 패턴 금지.\n'
    "7. **톤의 온도**: persona와 toneLevel이 결정. POLITE라도 자연스러운 공손함이지, 격식 문서체 아님."
)


def _build_template_section_block(
    template: StructureTemplate,
    effective_sections: list[StructureSection],
) -> str:
    parts: list[str] = []
    parts.append(f"\n\n## 메시지 구조: {template.name}")
    parts.append(
        "\n\n아래 섹션 순서로 메시지를 재구성하세요. "
        "각 섹션에 해당 세그먼트가 없으면 자연스럽게 생략합니다.\n"
    )

    for section in effective_sections:
        parts.append(f"\n### {section.label}")
        parts.append(f"\n- 목적: {section.instruction}")
        parts.append(f"\n- 분량: {section.length_hint}")
        if section.expression_pool:
            parts.append(
                f"\n- 시작 표현 예시 (변형하여 사용): {', '.join(section.expression_pool)}"
            )
        if section.name == "S2_OUR_EFFORT":
            parts.append(
                "\n- ⚠️ **필수**: 이 섹션은 반드시 출력에 포함해야 합니다. "
                "다음 패턴 중 하나 이상을 사용하세요: "
                '"확인한 결과", "점검해 본 결과", "검토한 바", "살펴본 결과", '
                '"파악한 바로는", "로그 기준으로 보면"'
            )
        parts.append("\n")

    if template.id == "T05_APOLOGY":
        parts.append(
            "\n⚠️ **T05 사과 필수**: 이 템플릿은 사과/수습 상황입니다. "
            '출력에 반드시 명시적 사과 표현을 포함하세요. 예: "죄송합니다", '
            '"송구합니다", "사과드립니다", "죄송한 마음입니다". '
            "사과 표현 없이 사실 전달만 하면 안 됩니다.\n"
        )

    if template.constraints:
        parts.append(f"\n{template.constraints}")

    return "".join(parts)


def _build_rag_system_block(rag_results) -> str:
    """Build RAG context block for system prompt (forbidden + expression_pool + cushion)."""
    if rag_results is None or rag_results.is_empty():
        return ""

    parts: list[str] = []

    # Forbidden — mandatory rules (must NOT appear in output)
    if rag_results.forbidden:
        parts.append("\n\n## ⛔ 금지 표현 (출력에 절대 포함 금지)")
        parts.append("\n아래 표현이 원문에 있더라도 출력에서 반드시 제거하고, 대체 표현을 사용하십시오:")
        for hit in rag_results.forbidden:
            alt = hit.alternative or "삭제"
            parts.append(f'\n- "{hit.content}" → 대체: "{alt}"')

    # Expression pool — reference expressions
    if rag_results.expression_pool:
        parts.append("\n\n## 참고 표현 (RAG)")
        parts.append("\n아래 표현을 참고하되 그대로 복사하지 말고 문맥에 맞게 변형하여 사용:")
        for hit in rag_results.expression_pool:
            parts.append(f"\n- {hit.content}")

    # Cushion — YELLOW buffer expressions
    if rag_results.cushion:
        parts.append("\n\n## YELLOW 쿠션 참고 (RAG)")
        parts.append("\nYELLOW 세그먼트 앞에 삽입할 완충 표현 참고 (변형하여 사용):")
        for hit in rag_results.cushion:
            parts.append(f"\n- {hit.content}")

    return "".join(parts)


def _build_rag_user_block(rag_results) -> str:
    """Build RAG context block for user message (policy + example + domain_context)."""
    if rag_results is None:
        return ""

    parts: list[str] = []

    # Policy references
    if rag_results.policy:
        parts.append("\n--- 정책/규정 참고 (RAG) ---\n")
        for hit in rag_results.policy:
            parts.append(f"- {hit.content}\n")

    # Domain context
    if rag_results.domain_context:
        parts.append("\n--- 도메인 배경 참고 (RAG) ---\n")
        for hit in rag_results.domain_context:
            parts.append(f"- {hit.content}\n")

    # Transform examples
    if rag_results.example:
        parts.append("\n--- 변환 예시 (RAG) ---\n")
        for hit in rag_results.example:
            if hit.original_text:
                parts.append(f"원문: {hit.original_text}\n")
            parts.append(f"변환: {hit.content}\n")

    return "".join(parts)


def build_final_system_prompt(
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
    template: StructureTemplate,
    effective_sections: list[StructureSection],
    rag_results=None,
) -> str:
    base = FINAL_CORE_SYSTEM_PROMPT + _build_template_section_block(template, effective_sections)
    base += _build_rag_system_block(rag_results)
    return prompt_builder.build_dynamic_blocks(base, persona, contexts, tone_level)


def build_final_user_message(
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
    sender_info: str | None,
    ordered_segments: list[OrderedSegment],
    all_locked_spans: list[LockedSpan] | None,
    situation_analysis: SituationAnalysisResult | None,
    summary_text: str | None,
    template: StructureTemplate,
    effective_sections: list[StructureSection],
    rag_results=None,
) -> str:
    parts: list[str] = []

    # Optional: Situation Analysis
    if situation_analysis is not None and (situation_analysis.facts or situation_analysis.intent):
        parts.append("--- 상황 분석 ---\n")
        if situation_analysis.facts:
            parts.append("사실:\n")
            for fact in situation_analysis.facts:
                parts.append(f"- {fact.content}")
                if fact.source:
                    parts.append(f' (원문: "{fact.source}")')
                parts.append("\n")
        if situation_analysis.intent:
            parts.append(f"의도: {situation_analysis.intent}\n")
        parts.append("\n")

    # Optional: Summary
    if summary_text and summary_text.strip():
        parts.append(f"[요약]: {summary_text}\n\n")

    # Optional: RAG user context (policy, domain, examples)
    rag_user_block = _build_rag_user_block(rag_results)
    if rag_user_block:
        parts.append(rag_user_block)
        parts.append("\n")

    # Build JSON wrapper object
    parts.append("```json\n")
    parts.append("{\n")

    # meta
    context_str = ", ".join(prompt_builder.get_context_label(ctx) for ctx in contexts)
    sections_str = ",".join(s.name for s in effective_sections)

    parts.append("  \"meta\": {\n")
    parts.append(f'    "receiver": "{_escape_json(prompt_builder.get_persona_label(persona))}",\n')
    parts.append(f'    "context": "{_escape_json(context_str)}",\n')
    parts.append(f'    "tone": "{_escape_json(prompt_builder.get_tone_label(tone_level))}"')
    if sender_info and sender_info.strip():
        parts.append(f',\n    "sender": "{_escape_json(sender_info)}"')
    parts.append(f',\n    "template": "{_escape_json(template.id)}"')
    parts.append(f',\n    "sections": "{_escape_json(sections_str)}"')
    parts.append("\n  },\n")

    # segments
    parts.append('  "segments": [\n')
    for i, seg in enumerate(ordered_segments):
        parts.append(f'    {{"id":"{seg.id}"')
        parts.append(f',"order":{seg.order}')
        parts.append(f',"tier":"{seg.tier}"')
        parts.append(f',"label":"{seg.label}"')
        if seg.text is not None:
            parts.append(f',"text":"{_escape_json(seg.text)}"')
            parts.append(f',"dedupeKey":"{_escape_json(seg.dedupe_key or "")}"')
            if seg.must_include:
                joined = ",".join(f'"{_escape_json(p)}"' for p in seg.must_include)
                parts.append(f',"mustInclude":[{joined}]')
        else:
            parts.append(',"text":null,"dedupeKey":null')
        parts.append("}")
        if i < len(ordered_segments) - 1:
            parts.append(",")
        parts.append("\n")
    parts.append("  ],\n")

    # placeholders
    parts.append('  "placeholders": {')
    if all_locked_spans:
        parts.append("\n")
        for i, span in enumerate(all_locked_spans):
            parts.append(f'    "{span.placeholder}": "{_escape_json(span.original_text)}"')
            if i < len(all_locked_spans) - 1:
                parts.append(",")
            parts.append("\n")
        parts.append("  ")
    parts.append("}\n")

    parts.append("}\n")
    parts.append("```\n")

    return "".join(parts)


def _escape_json(s: str | None) -> str:
    if s is None:
        return ""
    return (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )
