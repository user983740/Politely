"""Template registry — 12 purpose-based templates (T01-T12)."""

from collections import OrderedDict

from app.models.enums import Persona
from app.pipeline.template.structure_template import (
    SectionSkipRule,
    StructureSection,
    StructureTemplate,
)

S0 = StructureSection.S0_GREETING
S1 = StructureSection.S1_ACKNOWLEDGE
S2 = StructureSection.S2_OUR_EFFORT
S3 = StructureSection.S3_FACTS
S4 = StructureSection.S4_RESPONSIBILITY
S5 = StructureSection.S5_REQUEST
S6 = StructureSection.S6_OPTIONS
S7 = StructureSection.S7_POLICY
S8 = StructureSection.S8_CLOSING

_BOSS_PROF_OFFICIAL_RULES = {
    Persona.BOSS: SectionSkipRule(shorten_sections=frozenset({S1})),
    Persona.PROFESSOR: SectionSkipRule(shorten_sections=frozenset({S1})),
    Persona.OFFICIAL: SectionSkipRule(shorten_sections=frozenset({S1})),
}

_CLIENT_EXPAND_S1_S2 = {
    Persona.CLIENT: SectionSkipRule(expand_sections=frozenset({S1, S2})),
    Persona.BOSS: SectionSkipRule(shorten_sections=frozenset({S1})),
    Persona.PROFESSOR: SectionSkipRule(shorten_sections=frozenset({S1})),
    Persona.OFFICIAL: SectionSkipRule(shorten_sections=frozenset({S1})),
}

_PARENT_EXPAND_S1 = {
    Persona.PARENT: SectionSkipRule(expand_sections=frozenset({S1})),
    Persona.BOSS: SectionSkipRule(shorten_sections=frozenset({S1})),
    Persona.PROFESSOR: SectionSkipRule(shorten_sections=frozenset({S1})),
    Persona.OFFICIAL: SectionSkipRule(shorten_sections=frozenset({S1})),
}


class TemplateRegistry:
    def __init__(self) -> None:
        self._templates: OrderedDict[str, StructureTemplate] = OrderedDict()
        self._register_all()

    def _register_all(self) -> None:
        self._register(StructureTemplate(
            "T01_GENERAL", "일반 전달",
            [S0, S1, S3, S5, S6, S8],
            "범용 템플릿. 특정 패턴 없이 사실 전달 + 요청 + 대안 구조.",
            _BOSS_PROF_OFFICIAL_RULES,
        ))
        self._register(StructureTemplate(
            "T02_DATA_REQUEST", "자료 요청",
            [S0, S1, S3, S5, S8],
            "요청 사유를 먼저 밝히고, 구체적 자료/기한/형식을 명시. 부담을 줄이는 완곡 표현.",
            _BOSS_PROF_OFFICIAL_RULES,
        ))
        self._register(StructureTemplate(
            "T03_NAGGING_REMINDER", "독촉/리마인더",
            [S0, S1, S3, S5, S8],
            "이전 요청 상기 + 회신 기한. 비난 없이 사실 기반 리마인드. S1은 짧게.",
            {
                Persona.BOSS: SectionSkipRule(shorten_sections=frozenset({S1})),
                Persona.PROFESSOR: SectionSkipRule(shorten_sections=frozenset({S1})),
                Persona.OFFICIAL: SectionSkipRule(shorten_sections=frozenset({S1})),
                Persona.CLIENT: SectionSkipRule(shorten_sections=frozenset({S1})),
            },
        ))
        self._register(StructureTemplate(
            "T04_SCHEDULE", "일정 조율/지연",
            [S0, S1, S3, S4, S6, S8],
            "사과 → 지연 원인(사실) → 새 일정 제안. 변명 최소화, 대안 집중.",
            _PARENT_EXPAND_S1,
        ))
        self._register(StructureTemplate(
            "T05_APOLOGY", "사과/수습",
            [S0, S1, S2, S3, S6, S8],
            "진심 사과 → 내부 확인 노력 → 원인 → 해결/재발 방지. S2 필수.",
            _CLIENT_EXPAND_S1_S2,
        ))
        self._register(StructureTemplate(
            "T06_REJECTION", "거절/불가 안내",
            [S0, S1, S7, S3, S6, S8],
            "공감 → 정책/규정 근거 → 대안 제시. 감정 배제, 거절 이유 명확.",
            _CLIENT_EXPAND_S1_S2,
        ))
        self._register(StructureTemplate(
            "T07_ANNOUNCEMENT", "공지/안내",
            [S0, S3, S5, S8],
            "두괄식. 핵심 정보(일시/장소/대상) 먼저. 행동 요청으로 마무리. S1 생략.",
            {},
        ))
        self._register(StructureTemplate(
            "T08_FEEDBACK", "피드백",
            [S0, S1, S3, S5, S6, S8],
            "긍정 인정 → 개선점(요청 형태) → 기대 효과. 비판 아닌 성장 지향.",
            _PARENT_EXPAND_S1,
        ))
        self._register(StructureTemplate(
            "T09_BLAME_SEPARATION", "책임 분리",
            [S0, S1, S2, S3, S4, S6, S8],
            "공감 → 내부 확인 → 사실 나열 → 귀책 방향(주어 전환) → 해결안. 비난 제거 필수.",
            _CLIENT_EXPAND_S1_S2,
        ))
        self._register(StructureTemplate(
            "T10_RELATIONSHIP_RECOVERY", "관계 회복",
            [S0, S1, S3, S6, S8],
            "깊은 공감·사과 → 상황 인정 → 협력 제안. 감정 간접 전환 중시.",
            _PARENT_EXPAND_S1,
        ))
        self._register(StructureTemplate(
            "T11_REFUND_REJECTION", "환불 거절",
            [S0, S1, S2, S3, S7, S6, S8],
            "공감 → 내부 점검 → 사실 → 정책 근거 → 대안. S2 필수(점검 노력 표시).",
            _CLIENT_EXPAND_S1_S2,
        ))
        self._register(StructureTemplate(
            "T12_WARNING_PREVENTION", "경고/재발 방지",
            [S0, S1, S3, S5, S6, S8],
            "문제 인정 → 사실/경과 → 구체적 요청(재발 방지) → 기대 효과.",
            _BOSS_PROF_OFFICIAL_RULES,
        ))

    def _register(self, template: StructureTemplate) -> None:
        self._templates[template.id] = template

    def get(self, template_id: str) -> StructureTemplate:
        return self._templates.get(template_id, self._templates["T01_GENERAL"])

    def get_default(self) -> StructureTemplate:
        return self._templates["T01_GENERAL"]

    def all(self) -> list[StructureTemplate]:
        return list(self._templates.values())
