"""LLM-based segment refiner for long segments.

After rule-based MeaningSegmenter, segments exceeding the length threshold
are batched into a single LLM call (gpt-4o-mini, temp=0) for semantic splitting.

Flow:
  1. Filter segments > min_length (default 30 chars)
  2. Batch long segments into one prompt
  3. LLM inserts ||| delimiters at semantic boundaries
  4. Parse response, validate sub-texts exist in original
  5. Rebuild segment list with updated IDs (T1..Tn)

If LLM fails or produces invalid output, original segments are kept as-is.
"""

import logging
import re
from dataclasses import dataclass

from app.models.domain import LlmCallResult, Segment

logger = logging.getLogger(__name__)

MODEL = "gpt-4o-mini"
TEMPERATURE = 0.0
MAX_TOKENS = 600
MIN_LENGTH_DEFAULT = 30

SYSTEM_PROMPT = (
    "당신은 한국어 텍스트 의미 분절 전문가입니다.\n\n"
    "각 항목이 둘 이상의 독립된 의미 단위(완결된 생각/주장/사실)를 포함할 때만 분리하세요.\n"
    "하나의 의미 단위라면 길더라도 원문 그대로 출력하세요. 무리하게 쪼개지 마세요.\n\n"
    "규칙:\n"
    "1. 분리 시 ||| 를 삽입하세요\n"
    "2. 원문 텍스트를 정확히 보존하세요 (한 글자도 변경/추가/삭제 금지)\n"
    "3. {{TYPE_N}} 형식 플레이스홀더(예: {{DATE_1}}, {{PHONE_1}})는 절대 분리하지 마세요\n"
    "4. 너무 짧은 조각(10자 미만)이 생기지 않도록 하세요\n"
    "5. [N] 번호를 유지하고, 각 항목을 한 줄에 출력하세요"
)

_ENTRY_PATTERN = re.compile(r"\[(\d+)]\s*(.+)")


@dataclass
class RefineResult:
    segments: list[Segment]
    prompt_tokens: int
    completion_tokens: int


async def refine(
    segments: list[Segment],
    masked_text: str,
    ai_call_fn,
    min_length: int = MIN_LENGTH_DEFAULT,
) -> RefineResult:
    """Refine long segments using LLM.

    Args:
        segments: input segments from MeaningSegmenter
        masked_text: the full masked text
        ai_call_fn: async function(model, system, user, temp, max_tokens, analysis_context) -> LlmCallResult
        min_length: minimum segment length to trigger refinement
    """
    long_indices = [i for i, seg in enumerate(segments) if len(seg.text) > min_length]

    if not long_indices:
        logger.debug("[SegmentRefiner] No segments > %d chars, skipping LLM", min_length)
        return RefineResult(segments=segments, prompt_tokens=0, completion_tokens=0)

    logger.info("[SegmentRefiner] %d segments > %d chars, invoking LLM", len(long_indices), min_length)

    # Build user message
    user_msg_parts = []
    for i, idx in enumerate(long_indices):
        seg = segments[idx]
        user_msg_parts.append(f"[{i + 1}] {seg.text}")
    user_msg = "\n".join(user_msg_parts)

    try:
        result: LlmCallResult = await ai_call_fn(MODEL, SYSTEM_PROMPT, user_msg, TEMPERATURE, MAX_TOKENS, None)

        parsed_splits = _parse_response(result.content, len(long_indices), segments, long_indices)
        refined = _rebuild_segments(segments, long_indices, parsed_splits, masked_text)

        logger.info(
            "[SegmentRefiner] %d -> %d segments (LLM split %d long segments)",
            len(segments), len(refined), len(long_indices),
        )
        return RefineResult(
            segments=refined,
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )

    except Exception as e:
        logger.warning("[SegmentRefiner] LLM call failed, keeping original segments: %s", e)
        return RefineResult(segments=segments, prompt_tokens=0, completion_tokens=0)


def _parse_response(
    response: str, expected_count: int, segments: list[Segment], long_indices: list[int]
) -> list[list[str]]:
    # Initialize with originals as fallback
    result: list[list[str]] = [[segments[idx].text] for idx in long_indices]

    for line in response.split("\n"):
        line = line.strip()
        if not line:
            continue

        m = _ENTRY_PATTERN.match(line)
        if not m:
            continue

        entry_num = int(m.group(1))
        content = m.group(2).strip()

        if entry_num < 1 or entry_num > expected_count:
            continue

        entry_idx = entry_num - 1
        original_text = segments[long_indices[entry_idx]].text

        parts = [p.strip() for p in content.split("|||") if p.strip()]

        if len(parts) > 1 and _validate_parts(parts, original_text):
            result[entry_idx] = parts
        elif len(parts) == 1:
            result[entry_idx] = [original_text]

    return result


def _validate_parts(parts: list[str], original_text: str) -> bool:
    search_from = 0
    for part in parts:
        pos = original_text.find(part, search_from)
        if pos < 0:
            normalized = re.sub(r"\s+", " ", part)
            pos = original_text.find(normalized, search_from)
            if pos < 0:
                logger.debug(
                    "[SegmentRefiner] Part '%s...' not found in original at offset %d",
                    part[:30], search_from,
                )
                return False
        search_from = pos + len(part)
    return True


def _rebuild_segments(
    original: list[Segment],
    long_indices: list[int],
    splits: list[list[str]],
    masked_text: str,
) -> list[Segment]:
    result: list[Segment] = []
    long_idx = 0
    seg_id = 1

    for i, seg in enumerate(original):
        if long_idx < len(long_indices) and long_indices[long_idx] == i:
            parts = splits[long_idx]
            search_from = seg.start

            for part in parts:
                pos = masked_text.find(part, search_from)
                if pos < 0:
                    pos = search_from
                    logger.warning("[SegmentRefiner] Split part not found in maskedText, using fallback pos %d", pos)
                end = min(pos + len(part), len(masked_text))
                result.append(Segment(id=f"T{seg_id}", text=part, start=pos, end=end))
                seg_id += 1
                search_from = end

            long_idx += 1
        else:
            result.append(Segment(id=f"T{seg_id}", text=seg.text, start=seg.start, end=seg.end))
            seg_id += 1

    return result
