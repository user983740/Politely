"""Optional LLM gating component: extracts semantic locked spans.

Extracts proper nouns, filenames, etc. that regex patterns cannot catch.
"""

import logging
import re
from dataclasses import dataclass

from app.core.config import settings
from app.models.domain import LlmCallResult, LockedSpan
from app.models.enums import LockedSpanType

logger = logging.getLogger(__name__)

MODEL = settings.gemini_label_model  # gemini-2.5-flash-lite
THINKING_BUDGET = None  # No thinking for booster (speed priority)
TEMPERATURE = 0.2
MAX_TOKENS = 300

SYSTEM_PROMPT = (
    "당신은 텍스트에서 변경 불가능한 고유 표현을 추출하는 전문가입니다.\n"
    "정규식으로 잡을 수 없는, 대체하면 의미가 달라지는 고유 식별자만 찾습니다.\n\n"
    "이미 마스킹된 {{TYPE_N}} 형식의 플레이스홀더(예: {{DATE_1}}, {{PHONE_1}})는 무시하세요.\n"
    "날짜, 시간, 전화번호, 이메일, URL, 금액 등은 이미 처리되었으므로 제외하세요.\n\n"
    "## 추출 대상 (고유 식별자만)\n"
    "- 사람/회사/기관의 고유 이름 (예: 김민수, ㈜한빛소프트)\n"
    "- 프로젝트/제품/서비스 고유 명칭 (예: Project Alpha, 스터디플랜 v2)\n"
    "- 파일명, 코드명, 시스템명 (예: report_final.xlsx, ERP)\n\n"
    "## 제외 대상 (절대 추출 금지)\n"
    "- 일반 명사, 보통 명사, 일상 어휘\n"
    "- 관계/역할 호칭 (학부모, 담임, 교수, 팀장, 고객, 선생님 등)\n"
    "- 메타데이터에 이미 명시된 정보 (받는 사람, 상황 등)\n"
    "- 누구나 쓸 수 있는 범용 단어\n\n"
    '기준: "이 단어를 다른 말로 바꾸면 지칭 대상이 달라지는가?" → Yes만 추출.\n\n'
    '변경 불가 표현을 한 줄에 하나씩, "- " 접두사로 작성하세요.\n'
    "예:\n"
    "- 김민수\n"
    "- report_final.xlsx\n"
    "- ㈜한빛소프트\n\n"
    "예시 (추출 없음):\n"
    "원문: 내일까지 보고서 제출 부탁드립니다\n"
    "출력: 없음\n\n"
    '변경 불가 표현이 없으면 "없음"이라고만 작성하세요.'
)


@dataclass(frozen=True)
class BoosterResult:
    extra_spans: list[LockedSpan]  # newly found SEMANTIC spans only
    prompt_tokens: int
    completion_tokens: int


async def boost(
    normalized_text: str,
    current_spans: list[LockedSpan],
    masked_text: str,
    ai_call_fn,
) -> BoosterResult:
    """Extract semantic locked spans using LLM.

    Args:
        ai_call_fn: async function(model, system, user, temp, max_tokens, analysis_context) -> LlmCallResult
    """
    user_message = f"원문:\n{masked_text}"

    result: LlmCallResult = await ai_call_fn(
        MODEL, SYSTEM_PROMPT, user_message, TEMPERATURE, MAX_TOKENS, None,
        thinking_budget=THINKING_BUDGET,
    )

    new_spans = _parse_semantic_spans(normalized_text, current_spans, result.content)

    if new_spans:
        logger.info("[IdentityBooster] Found %d semantic spans", len(new_spans))

    return BoosterResult(new_spans, result.prompt_tokens, result.completion_tokens)


def _parse_semantic_spans(
    normalized_text: str,
    existing_spans: list[LockedSpan],
    output: str,
) -> list[LockedSpan]:
    result: list[LockedSpan] = []
    if not output or not output.strip() or output.strip() == "없음":
        return result

    all_known_spans = list(existing_spans)
    next_index = len(existing_spans)

    for line in output.split("\n"):
        line = line.strip()
        if not line.startswith("- "):
            continue

        text = line[2:].strip()
        if not text or len(text) < 2:
            continue

        # Use word-boundary-aware search
        span_pattern = _build_word_boundary_pattern(text)
        for m in span_pattern.finditer(normalized_text):
            pos = m.start()
            end_pos = m.end()

            # Check overlap
            overlaps = any(pos < s.end_pos and end_pos > s.start_pos for s in all_known_spans)
            if overlaps:
                continue

            prefix = LockedSpanType.SEMANTIC.placeholder_prefix
            new_span = LockedSpan(
                index=next_index,
                original_text=text,
                placeholder=f"{{{{{prefix}_{next_index}}}}}",
                type=LockedSpanType.SEMANTIC,
                start_pos=pos,
                end_pos=end_pos,
            )
            result.append(new_span)
            all_known_spans.append(new_span)
            next_index += 1

    return result


def _build_word_boundary_pattern(text: str) -> re.Pattern:
    """Build a word-boundary-aware pattern for the given text."""
    quoted = re.escape(text)
    starts_with_korean = _is_korean_char(text[0])
    ends_with_korean = _is_korean_char(text[-1])

    prefix = r"(?<![가-힣ㄱ-ㅎㅏ-ㅣ])" if starts_with_korean else r"\b"
    suffix = r"(?![가-힣ㄱ-ㅎㅏ-ㅣ])" if ends_with_korean else r"\b"

    return re.compile(prefix + quoted + suffix)


def _is_korean_char(c: str) -> bool:
    code = ord(c)
    return (
        (0xAC00 <= code <= 0xD7A3)  # 한글 음절
        or (0x3131 <= code <= 0x314E)  # 자음
        or (0x314F <= code <= 0x3163)  # 모음
    )
