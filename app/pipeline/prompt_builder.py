"""Prompt builder — Korean labels, dynamic persona/context/tone blocks.

Provides label maps and dynamic prompt blocks appended to core prompts
for the final LLM model.
"""

from app.models.enums import Persona, SituationContext, ToneLevel

PERSONA_LABELS: dict[Persona, str] = {
    Persona.BOSS: "직장 상사",
    Persona.CLIENT: "고객",
    Persona.PARENT: "학부모",
    Persona.PROFESSOR: "교수",
    Persona.OTHER: "기타",
    Persona.OFFICIAL: "공식 기관",
}

CONTEXT_LABELS: dict[SituationContext, str] = {
    SituationContext.REQUEST: "요청",
    SituationContext.SCHEDULE_DELAY: "일정 지연",
    SituationContext.URGING: "독촉",
    SituationContext.REJECTION: "거절",
    SituationContext.APOLOGY: "사과",
    SituationContext.COMPLAINT: "항의",
    SituationContext.ANNOUNCEMENT: "공지",
    SituationContext.FEEDBACK: "피드백",
    SituationContext.BILLING: "비용/정산",
    SituationContext.SUPPORT: "기술지원",
    SituationContext.CONTRACT: "계약",
    SituationContext.RECRUITING: "채용",
    SituationContext.CIVIL_COMPLAINT: "민원",
    SituationContext.GRATITUDE: "감사",
}

TONE_LABELS: dict[ToneLevel, str] = {
    ToneLevel.NEUTRAL: "중립",
    ToneLevel.POLITE: "공손",
    ToneLevel.VERY_POLITE: "매우 공손",
}

# ===== Dynamic persona blocks (each ~50 tokens) =====

_PERSONA_BLOCKS: dict[Persona, str] = {
    Persona.BOSS: """

## 받는 사람: 직장 상사
호칭: 인사 시 "안녕하세요" 또는 "안녕하십니까"로 시작.
"상사님"/"팀장님"/"부장님" 등은 senderInfo에 직급이 있을 때만 사용. **"상장님" 등 잘못된 호칭 절대 금지**.
겸양어와 존댓말 필수. 완곡한 요청/질문 형식 사용 (예시 참고만, 그대로 복사 금지: "~해주시면 감사하겠습니다" 류).
보내는 사람 정보가 있으면 인사에 포함. 서명 "[이름] 드림".
구어체 표현을 비즈니스체로 전환: "갈아엎다"→"개선하다/재구성하다", "이 꼴"→"이 상황", "빡빡하다"→"촉박하다"
YELLOW 쿠션 표현: "확인해 보니" / "살펴본 바로는" / "말씀드리기 어렵지만"
금지: "이해합니다"(상사에게 위→아래 뉘앙스), "내부 확인 결과"(기업 CS 용어)""",

    Persona.CLIENT: """

## 받는 사람: 고객
전문적이고 공식적. 회사를 대표하는 톤 유지.
보내는 사람 정보가 있으면 소속·이름 포함. 서명 "[이름] 드림".
고객 입장 공감 선행("불편을 드려 죄송합니다" 등) → 사실 설명 → 대안/안내 순서.
직접 거절·거부 금지. "~은 어려운 점이 있습니다" 등 완곡 안내 사용.
원인 설명 시 "확인 결과" 쿠션 선행 + 주어를 '귀사'가 아닌 '시스템/설정/환경' 등 비인격 주어로 전환.
YELLOW 쿠션 표현: "확인 결과" / "검토해 본 바" / "말씀 주신 부분 확인하였습니다"
금지: 고객 귀책을 직접 지적하는 표현("~하셨기 때문에" 식), 직접 거절("~은 불가합니다" 식)""",

    Persona.PARENT: """

## 받는 사람: 학부모
교사가 학부모에게 보내는 톤. 정중하면서 아이에 대한 관심과 배려가 드러나는 전문적 태도.
보내는 사람 정보가 있으면 소속·직함 포함. 따뜻한 마무리.
YELLOW 쿠션 표현: "아이의 학습 상황을 살펴보니" / "말씀드리기 조심스럽지만" / "함께 고민해 보면 좋을 것 같아서"
금지: "내부 확인 결과"/"검토해 본 바"(기업 CS 용어), "귀하"(학부모에게 부적절)""",

    Persona.PROFESSOR: """

## 받는 사람: 교수
학생이 교수에게 보내는 톤. 최상위 존칭. 인사 → 용건 순서.
서명 "[이름] 올림". **캐주얼 표현 절대 금지**: "솔직히"→삭제 또는 "말씀드리자면", "빡빡하다"→"촉박하다",
"꼬이다"→"이슈가 발생하다", "정리해놨고"→"정리하였으며"
YELLOW 쿠션 표현: "말씀드리기 조심스럽지만" / "관련하여 여쭤보고 싶은 부분이 있어" / "해당 부분에 대해 말씀드리면"
금지: "내부 확인 결과"/"검토해 본 바"(기업 CS 용어), "이해합니다"(교수에게 부적절)""",

    Persona.OFFICIAL: """

## 받는 사람: 공식 기관
격식체. 용건을 두괄식으로 명확하게. 필요 정보(날짜, 번호) 구체적 기재.
YELLOW 쿠션 표현: "확인 결과" / "검토한 바에 따르면" / "해당 건에 대하여"
금지: 캐주얼 표현""",

    Persona.OTHER: """

## 받는 사람: 기타
상황과 말투 강도에 맞춰 적절히 변환. 특정 관계를 전제하지 않음.
YELLOW 쿠션 표현: "관련하여" / "확인해 보니" / "말씀드리면\"""",
}

# ===== Dynamic context blocks (each ~30 tokens) =====

_CONTEXT_BLOCKS: dict[SituationContext, str] = {
    SituationContext.REQUEST: (
        '- **요청**: 부담을 줄이는 완곡 표현("~해주시면 감사하겠습니다", "혹시 가능하시다면"). '
        '상대의 바쁜 상황 배려("바쁘신 중에 죄송합니다만"). 기한은 원문의 구체적 날짜 유지. 요청 이유를 간결히 설명.'
    ),
    SituationContext.SCHEDULE_DELAY: (
        '- **일정 지연**: 사과를 먼저("지연되어 죄송합니다") + 원인 간결히(변명 아닌 사실 설명) '
        '+ 구체적 새 일정("~까지 완료하겠습니다"). 변명성 설명은 최소화하고 대안에 집중.'
    ),
    SituationContext.URGING: (
        '- **독촉**: 이전 요청 사실을 상기시키되 비난하지 않기("앞서 말씀드린 건으로 확인 부탁드립니다"). '
        '구체적 회신 기한 제시. "확인차 연락드립니다" 패턴으로 부드럽게. 반복 독촉이면 단호하되 예의 유지.'
    ),
    SituationContext.REJECTION: (
        '- **거절**: 감사/이해 표현 → 거절 이유(솔직하되 부드럽게) → 가능한 대안 제시 → 아쉬움 마무리. '
        '"어렵겠습니다", "양해 부탁드립니다" 등 완곡 표현 활용.'
    ),
    SituationContext.APOLOGY: (
        '- **사과**: 진심 어린 사과("깊이 사과드립니다") + 상대 불편 공감 '
        '+ 원인(부적절한 사유는 "불가피한 사정"으로 대체) '
        '+ 해결책/재발 방지 의지. 체념("어쩔 수 없다")은 개선 의지로 전환. '
        '사과와 함께 구체적 보상/해결안 제시.'
    ),
    SituationContext.COMPLAINT: (
        "- **항의**: 감정을 절제하고 사실 기반으로 문제 기술. 구체적 근거(날짜, 횟수, 금액) 제시. "
        '원하는 해결 방향 명시("~해주시면 감사하겠습니다"). 공격적 표현 대신 건설적 요청으로.'
    ),
    SituationContext.ANNOUNCEMENT: (
        '- **공지**: 핵심 정보(일시·장소·대상·내용)를 두괄식으로. 부가 설명은 뒤에 간결히. '
        '행동 요청("참석 부탁드립니다", "확인 부탁드립니다")으로 마무리.'
    ),
    SituationContext.FEEDBACK: (
        '- **피드백**: 긍정적 면을 구체적으로 먼저 언급 → 개선점을 건설적으로 제시("~하면 더 좋을 것 같습니다") '
        "→ 함께 노력하는 자세. 비판이 아닌 성장 지향 톤."
    ),
    SituationContext.BILLING: (
        "- **비용/정산**: 금액·내역의 정확성 최우선. 단가·수량·합계 명시적으로 기재. "
        "정산 기한과 입금 계좌 등 구체 정보 포함. 청구서/영수증 참조 안내."
    ),
    SituationContext.SUPPORT: (
        "- **기술지원**: 환경/버전 정보 명시. 재현 단계(1-2-3) 구조화. 로그/스크린샷 안내. "
        "해결 진행상황 공유. 기술 용어는 정확히, 비기술 수신자에겐 쉽게 풀어서."
    ),
    SituationContext.CONTRACT: (
        '- **계약**: 조항 번호/명칭 정확히 참조. 법적 톤 유의("~에 의거하여", "~에 해당합니다"). '
        "양측 의무/권리 명확히. 변경 시 서면 합의 필요성 언급."
    ),
    SituationContext.RECRUITING: (
        "- **채용**: 전문적이면서 환영하는 톤. 역할/직급/소속 명확히. "
        "면접 일정/장소/준비물 구체적 기재. 문의 창구 안내."
    ),
    SituationContext.CIVIL_COMPLAINT: (
        "- **민원**: 법적 근거(관련 법률/조례) 참조. 처리 기한 명시. 담당자/부서 안내. "
        "이의 신청 방법 고지. 격식체 유지."
    ),
    SituationContext.GRATITUDE: (
        "- **감사**: 구체적 행동/도움에 대한 감사 표현. 덕분에 얻은 효과/결과 언급. "
        "향후 협력 의지. 따뜻한 마무리."
    ),
}

# ===== Dynamic tone level blocks (each ~20 tokens) =====

_TONE_BLOCKS: dict[ToneLevel, str] = {
    ToneLevel.NEUTRAL: """

## 말투: 중립 — 존댓말이되 격식 낮춤. "~해요", "~할게요". 친근하면서 예의 바른 톤.""",
    ToneLevel.POLITE: """

## 말투: 공손 — 표준 비즈니스 존댓말. 자연스럽고 과하지 않은 정중함.""",
    ToneLevel.VERY_POLITE: """

## 말투: 매우 공손 — 최상위 존칭 + 겸양어. 격식을 갖추되 진심이 느껴지는 정중함.""",
}


# ===== Public functions =====


def build_dynamic_blocks(
    core_prompt: str,
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
) -> str:
    """Build dynamic blocks (persona + context + tone) appended to a custom core prompt."""
    parts = [core_prompt]

    # Persona block
    parts.append(_PERSONA_BLOCKS.get(persona, ""))

    # Context blocks
    if contexts:
        parts.append("\n\n## 상황")
        for ctx in contexts:
            block = _CONTEXT_BLOCKS.get(ctx, "")
            if block:
                parts.append(f"\n{block}")
        if len(contexts) > 1:
            parts.append(
                "\n- **복합 상황 우선순위**: 첫 번째 상황이 메시지의 주된 목적입니다. "
                "나머지 상황은 보조적으로 반영하되, 상충 시 첫 번째 상황의 톤을 우선하세요."
            )

    # Tone block
    parts.append(_TONE_BLOCKS.get(tone_level, ""))

    return "".join(parts)


def get_context_label(ctx: SituationContext) -> str:
    return CONTEXT_LABELS.get(ctx, ctx.name)


def get_persona_label(persona: Persona) -> str:
    return PERSONA_LABELS.get(persona, persona.name)


def get_tone_label(tone_level: ToneLevel) -> str:
    return TONE_LABELS.get(tone_level, tone_level.name)
