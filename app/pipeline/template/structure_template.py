"""Structure template — section enum and template dataclass."""

from dataclasses import dataclass
from enum import Enum


class StructureSection(str, Enum):
    S0_GREETING = "S0_GREETING"
    S1_ACKNOWLEDGE = "S1_ACKNOWLEDGE"
    S2_OUR_EFFORT = "S2_OUR_EFFORT"
    S3_FACTS = "S3_FACTS"
    S4_RESPONSIBILITY = "S4_RESPONSIBILITY"
    S5_REQUEST = "S5_REQUEST"
    S6_OPTIONS = "S6_OPTIONS"
    S7_POLICY = "S7_POLICY"
    S8_CLOSING = "S8_CLOSING"

    @property
    def label(self) -> str:
        return _SECTION_META[self]["label"]

    @property
    def instruction(self) -> str:
        return _SECTION_META[self]["instruction"]

    @property
    def expression_pool(self) -> list[str]:
        return _SECTION_META[self]["expression_pool"]

    @property
    def length_hint(self) -> str:
        return _SECTION_META[self]["length_hint"]


_SECTION_META: dict[StructureSection, dict] = {
    StructureSection.S0_GREETING: {
        "label": "인사",
        "instruction": "COURTESY 기반 인사. 보내는 사람 정보 있으면 포함",
        "expression_pool": [],
        "length_hint": "1문장",
    },
    StructureSection.S1_ACKNOWLEDGE: {
        "label": "공감/유감",
        "instruction": "상대 상황이나 요청에 대한 공감·유감·감사",
        "expression_pool": [
            "말씀해 주신 내용 확인했습니다",
            "불편을 드린 점 유감입니다",
            "해당 상황에 대해 충분히 이해합니다",
            "말씀 주신 부분 관련하여",
        ],
        "length_hint": "1~2문장",
    },
    StructureSection.S2_OUR_EFFORT: {
        "label": "내부 확인/점검",
        "instruction": "우리 측 확인·점검·조치 노력. 비난 완화 장치",
        "expression_pool": [
            "내부 확인 결과",
            "로그 기준으로 보면",
            "설정값을 기준으로 점검해 보니",
            "담당 부서와 확인한 바로는",
        ],
        "length_hint": "1문장 가능",
    },
    StructureSection.S3_FACTS: {
        "label": "핵심 사실",
        "instruction": "CORE_FACT + ACCOUNTABILITY(재작성). 수치/날짜/원인 정확 보존",
        "expression_pool": [],
        "length_hint": "1~3문장",
    },
    StructureSection.S4_RESPONSIBILITY: {
        "label": "책임 프레이밍",
        "instruction": "귀책 방향 설정. 주어를 상황/시스템/프로세스로 전환",
        "expression_pool": [
            "확인 결과 ~부분에서 차이가 발생하여",
            "해당 프로세스상 ~이(가) 원인으로 파악됩니다",
            "시스템 점검 과정에서 ~이(가) 확인되었습니다",
        ],
        "length_hint": "1~2문장",
    },
    StructureSection.S5_REQUEST: {
        "label": "요청/행동 요청",
        "instruction": "REQUEST + NEGATIVE_FEEDBACK(재작성). 기한/조건 보존",
        "expression_pool": [],
        "length_hint": "1~2문장",
    },
    StructureSection.S6_OPTIONS: {
        "label": "대안/다음 단계",
        "instruction": "CORE_INTENT + 대안 제시. 구체적 해결 방향",
        "expression_pool": [],
        "length_hint": "1~3문장",
    },
    StructureSection.S7_POLICY: {
        "label": "정책/한계/불가",
        "instruction": "거절 근거. 정책/규정 기반 완곡 설명. 감정 배제",
        "expression_pool": [
            "현행 정책에 따르면",
            "운영 기준상",
            "관련 규정에 의거하여",
        ],
        "length_hint": "1~2문장",
    },
    StructureSection.S8_CLOSING: {
        "label": "마무리",
        "instruction": "감사 + 해결 의지 또는 서명. 짧게.",
        "expression_pool": [],
        "length_hint": "1문장",
    },
}


@dataclass(frozen=True)
class StructureTemplate:
    id: str
    name: str
    section_order: list[StructureSection]
    constraints: str
