"""Cushion strategy service for YELLOW segment rewriting guidance.

Generates per-segment cushion strategies (approach, phrase, avoidance)
by making **parallel per-YELLOW LLM calls** and merging the results.

Model: gemini-2.5-flash-lite, temp=0.3, per-segment max_tokens=800 (incl. 512 thinking), thinking=512
"""

import asyncio
import json
import logging
from dataclasses import dataclass, field

from app.core.config import settings
from app.models.domain import LabeledSegment
from app.models.enums import SegmentLabelTier
from app.pipeline.gating.situation_analysis_service import SituationAnalysisResult
from app.pipeline.template.structure_template import StructureSection, StructureTemplate

logger = logging.getLogger(__name__)

MODEL = settings.gemini_label_model  # gemini-2.5-flash-lite
TEMPERATURE = 0.3
_PER_SEGMENT_MAX_TOKENS = 800
THINKING_BUDGET = 512

# ---------------------------------------------------------------------------
# System prompt — targets a single YELLOW segment
# ---------------------------------------------------------------------------
_PER_SEGMENT_SYSTEM_PROMPT = """\
역할: 한국어 비즈니스 커뮤니케이션 쿠션 전략 설계 전문가

## 임무
주어진 YELLOW 세그먼트 1개에 대해 **쿠션 전략**을 설계하세요.
쿠션 = YELLOW 내용을 수신자가 받아들이기 쉽게 만드는 완충 표현/접근법입니다.

## 출력 형식
아래 키를 가진 flat JSON 객체 하나만 출력. 마크다운 코드블록/설명 없이 순수 JSON만.
{
  "segment_id": "세그먼트 ID (예: T2)",
  "label": "세그먼트 라벨",
  "approach": "재작성 접근법 1문장 (예: 상황 주어로 전환하여 책임 분산)",
  "cushion_phrase": "실제 사용할 쿠션 표현 (예: 확인해 본 결과)",
  "avoid": "금지 표현/패턴 (예: 직접적 책임 지적)"
}

## 쿠션 표현 제약 (필수)
- cushion_phrase는 **최대 15자**. 짧고 자연스러운 비즈니스 표현만.
- 과잉 보상 금지:
  ✗ 고어/과잉 사과: "금할 길이 없습니다", "송구스럽기 그지없습니다", "면목이 없습니다"
  ✗ 과잉 감정: "진심으로 깊이", "마음이 무겁습니다", "죄송한 마음 금할 길이"
  ✗ 과도한 겸양: "감히 말씀드리기 어렵지만", "부족한 저로서는"
- 자연스러운 예시: "확인해 보니" / "살펴본 바로는" / "말씀드리면" / "관련하여" / "배경을 말씀드리면"

## 라벨별 제약
- ACCOUNTABILITY: 상황/시스템 주어 전환. 직접 귀책 금지.
- SELF_JUSTIFICATION: 방어 프레임 제거. 업무 맥락만 사실로 전환.
- NEGATIVE_FEEDBACK: 긍정 인정 선행. 직접 거부/판단 금지.
- EMOTIONAL: 감정 삭제 금지, 간접 전환만. 과잉 공감 금지.
- EXCESS_DETAIL: 압축 중심. 쿠션은 최소화.

## 예시

입력: T2 | ACCOUNTABILITY | "귀사 서버 설정이 이상해서 생긴거고"

출력:
{
  "segment_id": "T2",
  "label": "ACCOUNTABILITY",
  "approach": "상황/시스템 주어로 전환, 비난 제거",
  "cushion_phrase": "확인해 본 결과",
  "avoid": "직접 귀책 지목, 비난 어조"
}

## 주의사항
- 화자 의도(SA intent)를 훼손하지 않는 범위에서 쿠션 적용
- 쿠션이 본문보다 길어지면 안 됨 — 쿠션은 보조, 본문 사실이 주연
"""


@dataclass(frozen=True)
class CushionStrategy:
    raw_json: str
    overall_tone: str
    strategies: list[dict] = field(default_factory=list)
    transition_notes: str = ""
    prompt_tokens: int = 0
    completion_tokens: int = 0


_EMPTY = CushionStrategy(raw_json="", overall_tone="", strategies=[], transition_notes="")

_REQUIRED_KEYS = {"segment_id", "label", "approach", "cushion_phrase", "avoid"}


# ---------------------------------------------------------------------------
# Per-segment user message builder
# ---------------------------------------------------------------------------
def _build_per_segment_user_message(
    sa_result: SituationAnalysisResult,
    target_segment: LabeledSegment,
    all_segments: list[LabeledSegment],
    sender_info: str | None,
) -> str:
    parts: list[str] = []

    # SA context (compact)
    parts.append("## 상황 분석\n")
    if sa_result.facts:
        for f in sa_result.facts:
            parts.append(f"- {f.content}\n")
    if sa_result.intent:
        parts.append(f"의도: {sa_result.intent}\n")
    if sender_info:
        parts.append(f"발신자: {sender_info}\n")
    parts.append("\n")

    # Target YELLOW segment
    parts.append("## 대상 YELLOW 세그먼트\n")
    parts.append(f"- {target_segment.segment_id} | {target_segment.label.name} | {target_segment.text}\n\n")

    # Adjacent segments for context (1 before, 1 after)
    sorted_segs = sorted(all_segments, key=lambda s: s.start)
    target_idx = next(
        (i for i, s in enumerate(sorted_segs) if s.segment_id == target_segment.segment_id),
        None,
    )
    if target_idx is not None:
        neighbors: list[LabeledSegment] = []
        if target_idx > 0:
            neighbors.append(sorted_segs[target_idx - 1])
        if target_idx < len(sorted_segs) - 1:
            neighbors.append(sorted_segs[target_idx + 1])
        if neighbors:
            parts.append("## 인접 세그먼트 (맥락용)\n")
            for seg in neighbors:
                tier = seg.label.tier.name
                label = seg.label.name
                text = seg.text if seg.label.tier != SegmentLabelTier.RED else "[삭제됨]"
                parts.append(f"- {seg.segment_id} | {tier}/{label} | {text}\n")

    return "".join(parts)


# ---------------------------------------------------------------------------
# Single-YELLOW LLM call
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class _SingleResult:
    strategy: dict
    prompt_tokens: int
    completion_tokens: int


async def _generate_single(
    sa_result: SituationAnalysisResult,
    target_segment: LabeledSegment,
    all_segments: list[LabeledSegment],
    sender_info: str | None,
    ai_call_fn,
) -> _SingleResult | None:
    """Call LLM for one YELLOW segment. Returns result or None on failure."""
    user_message = _build_per_segment_user_message(
        sa_result, target_segment, all_segments, sender_info,
    )

    try:
        result = await ai_call_fn(
            MODEL, _PER_SEGMENT_SYSTEM_PROMPT, user_message,
            TEMPERATURE, _PER_SEGMENT_MAX_TOKENS, None,
            thinking_budget=THINKING_BUDGET,
        )

        raw = result.content.strip()
        # Strip markdown code fences if present
        if raw.startswith("```"):
            raw = raw.split("\n", 1)[1] if "\n" in raw else raw[3:]
            if raw.endswith("```"):
                raw = raw[:-3].strip()

        parsed = json.loads(raw)

        # Validate required keys
        missing = _REQUIRED_KEYS - set(parsed.keys())
        if missing:
            logger.warning(
                "[CushionStrategy] Missing keys %s for %s",
                missing, target_segment.segment_id,
            )
            return None

        # Enforce cushion_phrase max length
        phrase = parsed.get("cushion_phrase", "")
        if len(phrase) > 15:
            parsed["cushion_phrase"] = phrase[:15]

        return _SingleResult(
            strategy=parsed,
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )

    except Exception:
        logger.warning(
            "[CushionStrategy] _generate_single failed for %s",
            target_segment.segment_id,
            exc_info=True,
        )
        return None


# ---------------------------------------------------------------------------
# Heuristic helpers (no LLM call)
# ---------------------------------------------------------------------------
_LABEL_TONE_MAP = {
    "ACCOUNTABILITY": "상황 중심 건설적 톤",
    "NEGATIVE_FEEDBACK": "긍정 전환 요청 톤",
    "SELF_JUSTIFICATION": "사실 기반 간결 톤",
    "EMOTIONAL": "공감 기반 절제된 톤",
    "EXCESS_DETAIL": "핵심 위주 간결 톤",
}


def _derive_overall_tone(strategies: list[dict]) -> str:
    """Pick overall tone from the highest-priority label present."""
    label_priority = [
        "ACCOUNTABILITY", "NEGATIVE_FEEDBACK", "SELF_JUSTIFICATION",
        "EMOTIONAL", "EXCESS_DETAIL",
    ]
    labels_present = {s.get("label") for s in strategies}
    for label in label_priority:
        if label in labels_present:
            return _LABEL_TONE_MAP[label]
    return "정중하고 명확한 전달 톤"


def _derive_transition_notes(strategies: list[dict]) -> str:
    """Auto-generate transition hints when 2+ strategies exist."""
    if len(strategies) < 2:
        return ""
    labels = [s.get("label", "") for s in strategies]
    unique_labels = set(labels)
    if len(unique_labels) >= 2:
        return "서로 다른 유형의 YELLOW 세그먼트가 있으므로 각 쿠션 표현이 중복되지 않도록 다양하게 전환하세요."
    return "동일 유형 YELLOW 세그먼트가 반복되므로 쿠션 표현에 변화를 주어 단조로움을 피하세요."


# ---------------------------------------------------------------------------
# Public API — signature unchanged
# ---------------------------------------------------------------------------
async def generate(
    sa_result: SituationAnalysisResult,
    labeled_segments: list[LabeledSegment],
    template: StructureTemplate,
    effective_sections: list[StructureSection],
    sender_info: str | None,
    ai_call_fn,
) -> CushionStrategy:
    """Generate cushion strategies for YELLOW segments via parallel per-segment LLM calls.

    Returns empty strategy if no YELLOW segments or all calls fail.
    """
    yellow_segments = [s for s in labeled_segments if s.label.tier == SegmentLabelTier.YELLOW]
    if not yellow_segments:
        logger.info("[CushionStrategy] No YELLOW segments, skipping")
        return _EMPTY

    # Launch parallel LLM calls — one per YELLOW segment
    tasks = [
        _generate_single(sa_result, seg, labeled_segments, sender_info, ai_call_fn)
        for seg in yellow_segments
    ]
    results = await asyncio.gather(*tasks, return_exceptions=True)

    # Collect successful results
    strategies: list[dict] = []
    total_prompt = 0
    total_completion = 0
    for r in results:
        if isinstance(r, Exception):
            logger.warning("[CushionStrategy] Parallel task exception: %s", r)
            continue
        if r is not None:
            strategies.append(r.strategy)
            total_prompt += r.prompt_tokens
            total_completion += r.completion_tokens

    if not strategies:
        logger.warning("[CushionStrategy] All per-segment calls failed, returning empty")
        return _EMPTY

    overall_tone = _derive_overall_tone(strategies)
    transition_notes = _derive_transition_notes(strategies)

    raw_json = json.dumps(
        {"overall_tone": overall_tone, "strategies": strategies, "transition_notes": transition_notes},
        ensure_ascii=False,
    )

    logger.info(
        "[CushionStrategy] Generated %d/%d strategies",
        len(strategies), len(yellow_segments),
    )

    return CushionStrategy(
        raw_json=raw_json,
        overall_tone=overall_tone,
        strategies=strategies,
        transition_notes=transition_notes,
        prompt_tokens=total_prompt,
        completion_tokens=total_completion,
    )
