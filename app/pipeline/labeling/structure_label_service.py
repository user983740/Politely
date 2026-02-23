"""LLM-based structure labeling service (LLM call #1 in the pipeline).

Sends segments + metadata to gemini-2.5-flash-lite and receives 3-tier labels
(GREEN/YELLOW/RED) for each segment.

14-label system: GREEN 5 + YELLOW 5 + RED 4
YELLOW uses hard/soft trigger judgment instead of "polite conversion test".
"""

import logging
import re
from dataclasses import dataclass

from app.core.config import settings
from app.models.domain import LabeledSegment, LlmCallResult, Segment
from app.models.enums import Persona, SegmentLabel, SegmentLabelTier, SituationContext, ToneLevel
from app.pipeline import prompt_builder

logger = logging.getLogger(__name__)

PRIMARY_MODEL = settings.gemini_label_model  # gemini-2.5-flash-lite
FALLBACK_MODEL = "gpt-4o-mini"
THINKING_BUDGET = 512
TEMPERATURE = 0.2
MAX_TOKENS = 800
MIN_COVERAGE = 0.6

# Migration map for transitional period: old 8 YELLOW labels → new 5 YELLOW labels.
_MIGRATION_MAP: dict[str, SegmentLabel] = {
    "ACCOUNTABILITY_FACT": SegmentLabel.ACCOUNTABILITY,
    "ACCOUNTABILITY_JUDGMENT": SegmentLabel.ACCOUNTABILITY,
    "SELF_CONTEXT": SegmentLabel.SELF_JUSTIFICATION,
    "SELF_DEFENSIVE": SegmentLabel.SELF_JUSTIFICATION,
    "SPECULATION": SegmentLabel.EXCESS_DETAIL,
    "OVER_EXPLANATION": SegmentLabel.EXCESS_DETAIL,
}


@dataclass(frozen=True)
class StructureLabelResult:
    labeled_segments: list[LabeledSegment]
    summary_text: str | None
    prompt_tokens: int
    completion_tokens: int
    yellow_recovery_applied: bool = False
    yellow_upgrade_count: int = 0


SYSTEM_PROMPT = (
    "역할: 한국어 텍스트 구조 분석 전문가\n"
    "입력: 메타데이터 + 마스킹된 원문 + 서버 세그먼트 목록\n\n"
    "출력 형식: 줄당 <SEG_ID>|<LABEL>\n"
    "세그먼트 텍스트는 서버가 이미 보유하고 있으므로 출력하지 마세요.\n\n"
    "## 라벨링의 목적 — 3축 분석\n\n"
    "각 세그먼트를 **내용 기능**(무엇을 전달하는가), **내용 적절성**(그 내용을 수신자에게 전달해도 괜찮은가), "
    "**관계 방향성**(이 내용이 관계를 개선/유지/악화하는가)의 3축으로 분석합니다.\n"
    "라벨 = 재작성 전략입니다. 라벨이 결정되면 서버/최종 모델이 라벨별 차등 재작성을 수행합니다.\n\n"
    "- **GREEN**: 메시지에 꼭 필요한 내용. 내용/프레이밍 보존, 표현은 적극 재구성\n"
    "- **YELLOW**: 필요한 내용이지만, 내용 자체를 재구성하지 않으면 "
    "수신자가 불편할 내용 — 라벨별 차등 재작성 전략 적용\n"
    "- **RED**: 내용 자체가 불필요하고 해로움 — 완전 삭제\n\n"
    "## 14개 라벨 체계\n\n"
    "### GREEN (보존) — 5개\n"
    "- CORE_FACT: 핵심 사실 (기한, 수치, 상태, 결과, 구체적 행동 계획)\n"
    "- CORE_INTENT: 핵심 요청/목적 (부탁, 제안, 대안, 질문)\n"
    "- REQUEST: 명시적 요청/부탁 — 단, 요청 자체가 수신자에게 부적절하면 YELLOW\n"
    "- APOLOGY: 사과/양해 구하기\n"
    "- COURTESY: 관례적 예의 (인사, 감사, 호칭)\n"
    "핵심: GREEN은 내용/프레이밍 보존 + 표현 적극 재구성(구어체→비즈니스체 전환 포함). "
    "내용의 프레이밍 자체가 바뀌어야 하면(비난→중립, 직접→간접 등) 반드시 YELLOW\n\n"
    "※ GREEN 판단 시 '표현의 뉘앙스/어감/말투'는 고려하지 않습니다. "
    "투박하거나 거친 표현이라도 내용이 필수적이면 GREEN. "
    "표현 정리는 최종 모델이 담당합니다.\n\n"
    "### GREEN 함정 — 표면 형태에 속지 말 것\n"
    "- 요청 형태이지만 상대 행동을 판단/지시 → NEGATIVE_FEEDBACK\n"
    "- 사실 형태이지만 실제 기능이 자기변호/책임전가 → SELF_JUSTIFICATION 또는 ACCOUNTABILITY\n"
    "- 인사/사과 형태이지만 빈정·비꼼 → EMOTIONAL 또는 AGGRESSION\n"
    "- 사과 형태이지만 실제로는 변명/책임회피 → SELF_JUSTIFICATION\n"
    "판단 기준: 세그먼트의 **표면 형태**가 아니라 **실제 기능**에 따라 분류\n\n"
    "### YELLOW (수정) — 5개 (강도 스펙트럼)\n"
    "각 YELLOW 라벨은 서로 다른 재작성 전략을 유발하며, 강도 스펙트럼을 가집니다:\n\n"
    "**ACCOUNTABILITY** (책임소재):\n"
    "스펙트럼:\n"
    "· 약(사실 보고): \"서버 설정 변경 후 오류 발생\" → 인과 보존, 보고체 정리만\n"
    "· 강(비난 지적): \"귀사가 맨날 이래서 문제\" → 비난/판단 완전 제거, 사실만 남기기\n"
    "기준: 상대 탓 뉘앙스가 강하면 비난 완전 제거. 약하면 보고체 정리만.\n"
    "⚠️ 비난이 사실보다 압도적이면 PERSONAL_ATTACK(RED) 검토\n\n"
    "**SELF_JUSTIFICATION** (자기변호):\n"
    "스펙트럼:\n"
    "· 약(업무 맥락): \"디자인팀 자료가 3일 늦어서\" → 일정/원인/의존성 업무 맥락 보존, 문체만 정리\n"
    "· 강(방어적 변명): \"제 잘못도 있지만 사실은...\" → 방어 프레임 삭제, 원인 사실만 추출\n"
    "기준: 업무 맥락 정보는 남기되, 자기 방어 문장 구조만 제거.\n"
    "⚠️ 사실 없이 순수 자기방어만이면 PURE_GRUMBLE(RED) 검토\n"
    "⚠️ **SELF_JUSTIFICATION vs PRIVATE_TMI 구분**: "
    "업무 의존성/일정/원인 사실이 포함된 변명 → SELF_JUSTIFICATION(YELLOW). "
    "예: \"디자인팀 자료 3일 지연\" = YELLOW. "
    "반면, 개인 노력/고생 어필(시간/야근/체력 등) → PRIVATE_TMI(RED). "
    "예: \"새벽 3시까지 했고\", \"야근했고\", \"밤새 작업\" = RED\n\n"
    "**NEGATIVE_FEEDBACK** (부정적 평가):\n"
    "상대의 행동/결과/능력에 대한 부정적 평가나 피드백.\n"
    "\"퀄리티가 떨어진다\", \"좀 신경 써주시면\" 등.\n"
    "→ 긍정 인정 선행 + 개선 프레임 전환. 심각도 보존.\n\n"
    "**EMOTIONAL** (감정표현):\n"
    "업무/이슈에 대한 감정적 반응. \"너무 화가 난다\", \"답답하다\" 등 — 메시지 맥락을 형성하는 감정.\n"
    "→ 삭제 금지. 직접→간접 전환. 협조 의지 마무리.\n"
    "⚠️ EMOTIONAL vs PRIVATE_TMI: 이슈/업무에 대한 감정 → EMOTIONAL. "
    "발신자 개인 상태(직무 피로, 멘탈, 건강) → PRIVATE_TMI(RED)\n\n"
    "**EXCESS_DETAIL** (과잉설명):\n"
    "· 중복/반복: 핵심만 남기고 제거\n"
    "· 추측/가정: ⚠️ 별도 우선 처리. 반드시 가능성 표현 전환 + persona별 확인 표현 추가. 단순 중복 제거로 처리 금지.\n"
    "· 논리 체인: A→B→C 구조는 압축 보존\n\n"
    "### RED (삭제) — 4개\n"
    "- AGGRESSION: 공격/비꼬기/도발\n"
    "- PERSONAL_ATTACK: 인신공격 — 상대 인격/능력 비하\n"
    "- PRIVATE_TMI: 받는 사람이 전혀 알 필요 없는 사적 정보. "
    "**즉시 RED 패턴**: 개인 건강/신체 상태(\"허리 아프고\", \"몸이 안 좋아서\"), "
    "가정사/집안일(\"이사 준비\", \"집안일이 있어서\", \"애가 아파서\"), "
    "사적 생활 일정(\"새벽 3시까지 했고\", \"밤새 작업\"), "
    "야근/과도한 노력 어필(\"야근했고\", \"주말에도 나왔고\", \"새벽까지 했고\", \"밤새 작업했는데\", \"휴일 반납\"), "
    "직무 스트레스/감정적 한계 토로(\"멘탈이 나간다\", \"스트레스 받는다\", \"지쳐간다\", \"번아웃\"). "
    "삭제해도 메시지 용건 이해에 전혀 지장 없는 사적 정보 → RED\n"
    "- PURE_GRUMBLE: 순수 넋두리/체념/한탄 — 용건과 완전히 무관. "
    "**즉시 RED 패턴**: 체념/포기(\"진짜 모르겠습니다\", \"이러다 다 망한다\"), "
    "피해의식/억울함(\"저만 탓받는 느낌\", \"항상 제가 당하는 느낌\"), "
    "사실 없는 감정 토로(\"너무 힘들다\", \"이게 말이 되냐\")\n\n"
    "⚠️ 같은 내용이라도 발신자→수신자 관계에서 적절하지 않은 비판/불만은 PURE_GRUMBLE. "
    "예: 연구생→교수 '일정이 빡빡하면 퀄리티가 나올 수 없다' = "
    "맥락상 수신자에게 전달할 내용이 아님 → PURE_GRUMBLE(RED). "
    "동료에게 같은 말 = YELLOW 가능.\n\n"
    "### RED 분류 핵심 자문\n"
    "RED 판단 전 스스로 물어보세요:\n"
    "1. \"이 세그먼트를 완전히 삭제해도 메시지의 핵심 용건이 전달되는가?\" → YES이면 RED 후보\n"
    "2. \"이 내용이 수신자에게 유용한 정보(원인/해결/일정/사실)를 포함하는가?\" → NO이면 RED 확정\n"
    "3. \"개인 건강/가정사/직무 피로/멘탈 토로인가?\" → YES이면 PRIVATE_TMI(RED)\n"
    "4. \"사실 없이 불만/체념/한탄만인가?\" → YES이면 PURE_GRUMBLE(RED)\n"
    "⚠️ EXCESS_DETAIL과 PRIVATE_TMI를 혼동하지 마세요: 업무 관련 과잉 설명은 EXCESS_DETAIL(YELLOW), "
    "개인 사적 정보/건강/가정사는 PRIVATE_TMI(RED)\n\n"
    "### RED 강화 규칙\n"
    "- **Rule 1 (공격 패턴):** 욕설/축약(ㅅㅂ,ㅄ,시x), 비꼬기(\"잘하시네요\"+ㅋㅋ), "
    "능력 부정형, \"매번/맨날\" 행동 비난 → RED\n"
    "- **Rule 2 (조롱 자동):** 반어적 칭찬 → 사실 포함 불문 RED\n"
    "- **Rule 3 (무정보 공격):** 사실 전혀 없이 비난/불만만 → RED\n\n"
    "## 분리 기준\n\n"
    "**ACCOUNTABILITY vs PERSONAL_ATTACK**: \"비난 빼면 사실이 남는가?\"\n"
    "- \"서버 설정 변경 후 오류가 발생한 것으로 확인됩니다\" → ACCOUNTABILITY (약): 사실 보고\n"
    "- \"귀사 서버 설정이 이상해서 생긴거고 제가 지난번에도 말했는데\" → ACCOUNTABILITY (강): 사실+비난\n"
    "- \"고객님이 매번 이러니까 문제가 생기는 거예요\" → PERSONAL_ATTACK (RED): 사실 없이 비난\n\n"
    "**SELF_JUSTIFICATION vs PURE_GRUMBLE**: \"방어 빼면 원인 사실이 남는가?\"\n"
    "- \"디자인팀 자료가 3일 늦게 와서 일정이 밀렸습니다\" → SELF_JUSTIFICATION (약): 업무 맥락\n"
    "- \"제 잘못도 있지만 사실은 디자인팀에서 너무 늦게 줘서요\" → SELF_JUSTIFICATION (강): 방어+사실\n"
    "- \"제 잘못이 아니에요 다들 그런 상황이면 똑같았을 거예요\" → PURE_GRUMBLE (RED): 사실 없는 넋두리\n\n"
    "## 판단 프로세스 — 4단계 + 하드/소프트 트리거\n\n"
    "### 1단계: 내용 필요성\n"
    "이 세그먼트를 통째로 삭제하면 메시지가 이해되는가?\n"
    "- 이해 안 됨 → 2단계로\n"
    "- 이해 됨 → ⚠️ 예외: 인사/감사/사과(COURTESY/APOLOGY 후보)는 품질에 필요 → 2단계로\n"
    "- 이해 됨 + 예의 아님 → 3단계로 (RED 후보)\n\n"
    "### 2단계: GREEN vs YELLOW — 하드/소프트 트리거\n\n"
    "◆ 하드 트리거 (1개라도 → 즉시 YELLOW):\n"
    "- 상대 행동/능력을 직접 판단·평가 (\"항상 이러시잖아요\", \"좀 신경 써주시면\")\n"
    "- 비난 어조 + 일반화/반복 패턴 (\"맨날/항상/매번 이런다\", \"왜 이래요\", \"당신이 안 해서\")\n"
    "  ※ 증거/로그/시간을 동반한 정상적 RCA 보고(\"귀사 설정 변경 후 오류로 확인됩니다\")는 하드 아님\n"
    "- 감정 직접 폭발 (\"답답하다\", \"화가 난다\", \"짜증난다\") "
    "※ 개인 상태 토로(\"멘탈 나간다\",\"지쳐간다\")는 PRIVATE_TMI(RED)\n"
    "- 방어적 변명 구조 (\"제 잘못도 있지만\", \"그건 제가 아니라\")\n\n"
    "◆ 소프트 트리거 (2개 이상 → YELLOW):\n"
    "- 원인의 외부 귀책 + 상대 과실/책임 지목이 결합 (\"~때문에\" 단독은 미해당)\n"
    "- 원인의 외부 귀책 + 불평/감정이 결합\n"
    "- 감정 간접 표출 (\"좀 그렇다\", \"아쉽다\")\n"
    "- 근거 약한 추측 (\"아마\", \"~것 같다\", \"분명\")\n"
    "- 동일 내용의 다른 표현으로 반복\n"
    "- [수신자별 — persona+context 조합 고려]\n"
    "  BOSS: 상사 결정 불만/업무 과중 호소\n"
    "  CLIENT: 고객 과실 언급/이용 방식 비판 (단, 장애/오류에서 보고체 RCA는 소프트 완화)\n"
    "  PARENT: 양육 지시/가정환경 언급\n"
    "  PROFESSOR: 수업 방식 의견\n"
    "  OFFICIAL: 절차 불만/담당자 비판\n"
    "  OTHER: 상대 영역 침범\n\n"
    "하드 0개 + 소프트 0~1개 → GREEN\n"
    "하드 1개+ → YELLOW\n"
    "소프트 2개+ → YELLOW\n\n"
    "### 3단계: RED vs YELLOW 판정\n"
    "1단계에서 \"삭제해도 이해됨 + 예의 아님\"으로 온 세그먼트:\n\n"
    "RED 패턴 (하나라도 해당 → RED):\n"
    "- 욕설/비속어/축약 (초성 욕설 ㅅㅂ, ㅄ 등 포함)\n"
    "- 비꼬는 칭찬 + 조롱 표식(ㅋㅋ, ^^, ㅎㅎ)\n"
    "- 상대 인격/능력 직접 비하 (\"무능하다\", \"뇌가 있냐\", \"그것도 못 해?\")\n"
    "- 수신자와 무관한 순수 사적 불만/한탄\n"
    "- 구체적 사실 정보 없이 비난만으로 구성\n\n"
    "RED 패턴 미해당 시:\n"
    "- 구체적 정보가치(원인/수치/행동 지침 등) 있음 → YELLOW\n"
    "- 정보가치 없음(넋두리/TMI/불필요한 반복) → RED\n\n"
    "### 4단계: 라벨 선택\n"
    "GREEN/YELLOW/RED 각 라벨 중 가장 적합한 것 선택\n\n"
    "## YELLOW 인식 확대 — All-GREEN 오분류 방지\n\n"
    "다음은 자주 GREEN으로 오분류되는 패턴입니다. 주의하세요:\n"
    "- \"~때문에\" 구조: 단순 원인 설명이면 GREEN, 외부 귀책+불만 결합이면 YELLOW(ACCOUNTABILITY)\n"
    "- 요청 형태의 지시: \"좀 해주세요\"류 — 실제로 상대 행동을 판단/지시하면 YELLOW(NEGATIVE_FEEDBACK)\n"
    "- 장황한 설명: 핵심 사실 1개 + 동일 내용 반복 2~3개면 반복 부분은 YELLOW(EXCESS_DETAIL)\n"
    "- 감정 간접 표출: \"좀 그런 것 같다\" — 간접이라도 감정이면 YELLOW(EMOTIONAL)\n"
    "- 방어적 원인 설명: 원인이 객관적이어도 \"~이라서 어쩔 수 없었다\" 구조면 YELLOW(SELF_JUSTIFICATION)\n\n"
    "## 예시 1\n\n"
    "받는 사람: 직장 상사\n\n"
    "[세그먼트]\n"
    "T1: 과장님 보고서 제출이 늦어졌습니다\n"
    "T2: 사실 제 잘못도 있지만 디자인팀에서 자료를 너무 늦게 줘서요\n"
    "T3: 개인적으로 이사 준비도 있어서 정신이 없었고\n"
    "T4: 수정본은 내일 오전까지 제출하겠습니다\n"
    "T5: 솔직히 이런 일정이면 퀄리티가 나올 수가 없다고 봅니다\n"
    "T6: 늦어서 죄송합니다\n\n"
    "[정답]\n"
    "T1|CORE_FACT\n"
    "T2|SELF_JUSTIFICATION\n"
    "T3|PRIVATE_TMI\n"
    "T4|CORE_INTENT\n"
    "T5|PURE_GRUMBLE\n"
    "T6|APOLOGY\n\n"
    "## 예시 2\n\n"
    "받는 사람: 고객\n\n"
    "[세그먼트]\n"
    "T1: 고객님\n"
    "T2: 솔직히 말씀드리면 이번 오류는 저희 쪽 문제라기보단 귀사 서버 설정이 이상해서 "
    "생긴거고 제가 지난번에도 config.yaml 건드리지 말라고 했는데\n"
    "T3: 또 수정하셔서 이 사단이 난거 같습니다\n"
    "T4: 아무튼 현재 연락처로 연락주셔도 되고 로그파일 보내주시면 제가 보겠습니다\n"
    "T5: 근데 저도 사람이라 하루종일 이런 대응하면 멘탈 좀 나가긴 합니다\n\n"
    "[정답]\n"
    "T1|COURTESY\n"
    "T2|ACCOUNTABILITY\n"
    "T3|NEGATIVE_FEEDBACK\n"
    "T4|CORE_INTENT\n"
    "T5|PRIVATE_TMI\n\n"
    "## 예시 3\n\n"
    "받는 사람: 학부모\n\n"
    "[세그먼트]\n"
    "T1: 안녕하세요 어머니\n"
    "T2: 님 애가 시험을 망해서 놀라셨죠\n"
    "T3: 님 애가 안 쳐한거임\n"
    "T4: 솔직히 수업 시간에 만날 떠들어서 다른 애들한테도 피해줌\n"
    "T5: 내 탓하려고 시동건다\n"
    "T6: 아무튼 수학이랑 영어는 보충수업 들어야 할 것 같고요\n"
    "T7: 님도 애를 좀 잡아봐\n"
    "T8: 다음 주에 상담 한번 잡으면 좋겠습니다\n\n"
    "[정답]\n"
    "T1|COURTESY\n"
    "T2|CORE_FACT\n"
    "T3|NEGATIVE_FEEDBACK\n"
    "T4|NEGATIVE_FEEDBACK\n"
    "T5|PERSONAL_ATTACK\n"
    "T6|CORE_INTENT\n"
    "T7|NEGATIVE_FEEDBACK\n"
    "T8|REQUEST\n\n"
    "[판단 근거]\n"
    "T2: 시험 결과 + 학부모 반응이라는 내용 자체가 서론으로 필요. 하드 트리거 없음 → GREEN (CORE_FACT)\n"
    "T3: 하드 트리거 — \"안 쳐한거임\"은 학생 능력 직접 평가 → NEGATIVE_FEEDBACK\n"
    "T4: 하드 트리거 — \"만날 떠들어서\"는 비난 어조+일반화. 수업 태도 부정적 피드백 → NEGATIVE_FEEDBACK\n"
    "T5: 삭제해도 이해됨 + 사실 없이 비난만 → PERSONAL_ATTACK (RED)\n"
    "T7: 하드 트리거 — \"애를 잡아봐\"는 요청 형태이지만 양육 지시(PARENT 수신자 영역 침범) → NEGATIVE_FEEDBACK\n\n"
    "선택사항(마지막 줄): SUMMARY: 핵심 용건을 1-2문장으로 요약\n\n"
    "## 필수 규칙\n"
    "- **모든 세그먼트에 반드시 라벨을 부여하세요.** 빠뜨리는 세그먼트가 없어야 합니다.\n"
    "- **RED는 극도로 보수적으로.** 단, RED 강화 규칙에 해당하면 적용.\n"
    "- **GREEN vs YELLOW 핵심**: 하드 트리거 1개+ 또는 소프트 트리거 2개+ → YELLOW. "
    "표면 형태에 속지 말고 실제 기능으로 판단.\n"
    "- **수신자 관점 필수 체크**: persona의 입장에서, 관계 경계 침범 여부 확인.\n"
    "- **YELLOW 라벨 선택**: 5개 YELLOW 라벨 중 재작성 전략이 가장 적합한 것 선택.\n"
    "- RED 전 반드시 자문: \"이걸 통째로 삭제하면 메시지가 여전히 이해되는가?\"\n\n"
    "금지: {{TYPE_N}} 형식 플레이스홀더(예: {{DATE_1}}, {{PHONE_1}}) 수정"
)


async def label(
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
    user_prompt: str | None,
    sender_info: str | None,
    segments: list[Segment],
    masked_text: str,
    ai_call_fn,
) -> StructureLabelResult:
    """Label segments using LLM.

    Args:
        ai_call_fn: async function(model, system, user, temp, max_tokens, analysis_context) -> LlmCallResult
    """
    user_message = _build_user_message(persona, contexts, tone_level, user_prompt, sender_info, segments, masked_text)

    result: LlmCallResult = await ai_call_fn(
        PRIMARY_MODEL, SYSTEM_PROMPT, user_message, TEMPERATURE, MAX_TOKENS, None,
        thinking_budget=THINKING_BUDGET,
    )

    logger.debug("[StructureLabel] Raw LLM response:\n%s", result.content)

    labeled = _parse_output(result.content, masked_text, segments)
    summary = _parse_summary(result.content)

    total_prompt = result.prompt_tokens
    total_completion = result.completion_tokens

    # Validate coverage
    if not _validate_result(labeled, masked_text, segments):
        labeled_ids = {ls.segment_id for ls in labeled}
        missing_ids = [seg.id for seg in segments if seg.id not in labeled_ids]

        logger.warning(
            "[StructureLabel] Validation failed (parsed %d of %d labels, missing: %s), retrying once",
            len(labeled), len(segments), missing_ids,
        )
        retry_message = (
            user_message
            + "\n\n[시스템 경고] 이전 응답에서 다음 세그먼트의 라벨이 누락되었습니다: "
            + ", ".join(missing_ids)
            + ". 모든 세그먼트에 라벨을 부여해주세요. "
            "반드시 SEG_ID|LABEL 형식으로 줄마다 출력하세요. "
            "코드블록이나 설명 없이 바로 출력하세요."
        )
        retry_result: LlmCallResult = await ai_call_fn(
            PRIMARY_MODEL, SYSTEM_PROMPT, retry_message, TEMPERATURE, MAX_TOKENS, None,
            thinking_budget=THINKING_BUDGET,
        )

        logger.debug("[StructureLabel] Retry raw LLM response:\n%s", retry_result.content)

        retry_labeled = _parse_output(retry_result.content, masked_text, segments)
        retry_summary = _parse_summary(retry_result.content)
        total_prompt += retry_result.prompt_tokens
        total_completion += retry_result.completion_tokens

        if retry_labeled:
            retry_labeled = _fill_missing_labels(retry_labeled, segments)
            return StructureLabelResult(retry_labeled, retry_summary, total_prompt, total_completion)

        # Both attempts failed — fallback: label all as COURTESY
        logger.warning(
            "[StructureLabel] Both attempts failed, falling back to all-COURTESY labels for %d segments",
            len(segments),
        )
        fallback = [
            LabeledSegment(seg.id, SegmentLabel.COURTESY, seg.text, seg.start, seg.end)
            for seg in segments
        ]
        return StructureLabelResult(fallback, retry_summary, total_prompt, total_completion)

    # All-GREEN 3-level response
    if len(segments) >= 4 and _is_all_green(labeled):
        logger.warning(
            "[StructureLabel] All %d segments labeled GREEN with %d+ segments — trying yellow scanner first",
            len(labeled), len(segments),
        )

        # 1) Try server-side yellow trigger scanner before LLM retry
        from app.pipeline.labeling import yellow_trigger_scanner

        upgrades = yellow_trigger_scanner.scan_yellow_triggers(segments, labeled, persona)
        if upgrades:
            logger.info(
                "[StructureLabel] Yellow scanner recovered %d segment(s): %s",
                len(upgrades),
                [(u.segment_id, u.new_label.name, u.score) for u in upgrades],
            )
            upgraded_map = {u.segment_id: u.new_label for u in upgrades}
            upgraded_labeled = [
                LabeledSegment(ls.segment_id, upgraded_map[ls.segment_id], ls.text, ls.start, ls.end)
                if ls.segment_id in upgraded_map else ls
                for ls in labeled
            ]
            upgraded_labeled = _fill_missing_labels(upgraded_labeled, segments)
            return StructureLabelResult(
                upgraded_labeled, summary, total_prompt, total_completion,
                yellow_recovery_applied=True,
                yellow_upgrade_count=len(upgrades),
            )

        # 2) Scanner found nothing — fall back to gpt-4o-mini (model diversity)
        logger.info(
            "[StructureLabel] Yellow scanner found no triggers — falling back to FALLBACK_MODEL (%s)",
            FALLBACK_MODEL,
        )
        fallback_result: LlmCallResult = await ai_call_fn(
            FALLBACK_MODEL, SYSTEM_PROMPT, user_message, TEMPERATURE, MAX_TOKENS, None,
        )

        logger.debug("[StructureLabel] Fallback model response:\n%s", fallback_result.content)

        fallback_labeled = _parse_output(fallback_result.content, masked_text, segments)
        fallback_summary = _parse_summary(fallback_result.content)
        total_prompt += fallback_result.prompt_tokens
        total_completion += fallback_result.completion_tokens

        if fallback_labeled:
            has_non_green = any(s.label.tier != SegmentLabelTier.GREEN for s in fallback_labeled)
            if has_non_green:
                fallback_labeled = _fill_missing_from_original(fallback_labeled, labeled, segments)
                return StructureLabelResult(
                    fallback_labeled,
                    fallback_summary if fallback_summary else summary,
                    total_prompt, total_completion,
                )
        logger.info("[StructureLabel] Fallback model also all-GREEN — accepting original result")

    # Fill any missing segments with COURTESY default
    labeled = _fill_missing_labels(labeled, segments)

    return StructureLabelResult(labeled, summary, total_prompt, total_completion)


def _is_all_green(labeled: list[LabeledSegment]) -> bool:
    return all(s.label.tier == SegmentLabelTier.GREEN for s in labeled)


def _build_user_message(
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
    user_prompt: str | None,
    sender_info: str | None,
    segments: list[Segment],
    masked_text: str,
) -> str:
    parts: list[str] = []
    parts.append(f"받는 사람: {prompt_builder.get_persona_label(persona)}")
    parts.append(f"상황: {', '.join(prompt_builder.get_context_label(ctx) for ctx in contexts)}")
    parts.append(f"말투 강도: {prompt_builder.get_tone_label(tone_level)}")
    if sender_info and sender_info.strip():
        parts.append(f"보내는 사람: {sender_info}")
    if user_prompt and user_prompt.strip():
        parts.append(f"참고 맥락: {user_prompt}")

    parts.append("\n[서버 세그먼트]")
    for seg in segments:
        parts.append(f"{seg.id}: {seg.text}")

    parts.append(f"\n[마스킹된 원문]\n{masked_text}")
    return "\n".join(parts)


_ENTRY_PATTERN = re.compile(r"\*\*")


def _parse_output(output: str, masked_text: str, segments: list[Segment]) -> list[LabeledSegment]:
    """Parse LLM output lines: SEG_ID|LABEL (2-column format)."""
    result: list[LabeledSegment] = []
    if not output or not output.strip():
        return result

    # Build segment lookup map
    segment_map = {seg.id: seg for seg in segments}

    # Track seen segment IDs to deduplicate
    seen_seg_ids: set[str] = set()

    # Strip markdown code blocks
    cleaned = output.strip()
    if cleaned.startswith("```"):
        first_newline = cleaned.find("\n")
        if first_newline > 0:
            cleaned = cleaned[first_newline + 1:]
        if cleaned.endswith("```"):
            cleaned = cleaned[:-3]
        cleaned = cleaned.strip()

    for line in cleaned.split("\n"):
        line = line.strip()
        if line.startswith("SUMMARY:"):
            continue
        if not line:
            continue
        if line.startswith("```") or line.startswith("#") or line.startswith("---"):
            continue
        if "|" not in line:
            continue

        parts = line.split("|", 2)
        if len(parts) < 2:
            continue

        raw_seg_id = parts[0].strip()
        label_str = parts[1].strip()

        # Normalize segId: strip markdown bold (**T1** → T1), leading dash/bullet
        seg_id = raw_seg_id.replace("**", "")
        seg_id = re.sub(r"^[-•*]\s*", "", seg_id).strip()

        # Deduplicate
        if seg_id in seen_seg_ids:
            logger.debug("[StructureLabel] Duplicate segment ID '%s', keeping first occurrence", seg_id)
            continue
        seen_seg_ids.add(seg_id)

        # Look up the segment
        seg = segment_map.get(seg_id)
        if seg is None:
            logger.debug("[StructureLabel] Unknown segment ID '%s', skipping", seg_id)
            continue

        # Resolve label
        label = _resolve_label(label_str, seg_id)
        result.append(LabeledSegment(seg_id, label, seg.text, seg.start, seg.end))

    return result


def _resolve_label(label_str: str, seg_id: str) -> SegmentLabel:
    """Resolve a label string to a SegmentLabel enum value."""
    # 1. Try direct lookup
    try:
        return SegmentLabel(label_str)
    except ValueError:
        pass

    # 2. Try migration map
    migrated = _MIGRATION_MAP.get(label_str)
    if migrated is not None:
        logger.warning("[StructureLabel] Migrated old label '%s' → '%s' for segment %s", label_str, migrated, seg_id)
        return migrated

    # 3. Unknown label — default to COURTESY
    logger.warning("[StructureLabel] Unknown label '%s' for segment %s, defaulting to COURTESY", label_str, seg_id)
    return SegmentLabel.COURTESY


def _parse_summary(output: str) -> str | None:
    if not output:
        return None
    for line in output.split("\n"):
        line = line.strip()
        if line.startswith("SUMMARY:"):
            return line[len("SUMMARY:"):].strip()
    return None


def _fill_missing_labels(labeled: list[LabeledSegment], segments: list[Segment]) -> list[LabeledSegment]:
    labeled_ids = {ls.segment_id for ls in labeled}
    missing = [
        LabeledSegment(seg.id, SegmentLabel.COURTESY, seg.text, seg.start, seg.end)
        for seg in segments
        if seg.id not in labeled_ids
    ]
    if missing:
        logger.warning(
            "[StructureLabel] %d segments missing labels, defaulting to COURTESY: %s",
            len(missing), [m.segment_id for m in missing],
        )
        return list(labeled) + missing
    return labeled


def _fill_missing_from_original(
    diversity_labeled: list[LabeledSegment],
    original_labeled: list[LabeledSegment],
    segments: list[Segment],
) -> list[LabeledSegment]:
    diversity_ids = {ls.segment_id for ls in diversity_labeled}
    original_map = {ls.segment_id: ls for ls in original_labeled}

    combined = list(diversity_labeled)
    for seg in segments:
        if seg.id not in diversity_ids:
            original = original_map.get(seg.id)
            if original is not None:
                combined.append(original)
            else:
                combined.append(LabeledSegment(seg.id, SegmentLabel.COURTESY, seg.text, seg.start, seg.end))
    return combined


def _validate_result(labeled: list[LabeledSegment], masked_text: str, segments: list[Segment]) -> bool:
    if not labeled:
        return False
    if not segments:
        return False

    coverage = len(labeled) / len(segments)
    if coverage < MIN_COVERAGE:
        logger.warning(
            "[StructureLabel] Low segment coverage: %d of %d (%d%%, min: %d%%)",
            len(labeled), len(segments), round(coverage * 100), round(MIN_COVERAGE * 100),
        )
        return False

    has_core_green = any(
        s.label in (SegmentLabel.CORE_FACT, SegmentLabel.CORE_INTENT, SegmentLabel.REQUEST)
        for s in labeled
    )
    if not has_core_green:
        logger.warning("[StructureLabel] No CORE_FACT, CORE_INTENT, or REQUEST found")
        return False

    return True
