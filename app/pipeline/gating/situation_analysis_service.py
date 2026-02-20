"""Situation analysis: extracts objective facts and core intent from text.

Outputs structured JSON (facts + intent).
Provides Final model with accurate context to reduce hallucination.
"""

import json
import logging
import re
from dataclasses import dataclass

from app.models.domain import LabeledSegment, LlmCallResult
from app.models.enums import Persona, SegmentLabelTier, SituationContext, ToneLevel
from app.pipeline import prompt_builder

logger = logging.getLogger(__name__)

MODEL = "gpt-4o-mini"
TEMPERATURE = 0.2
MAX_TOKENS = 500

_KOREAN_WORD_PATTERN = re.compile(r"[가-힣]{2,}")
_STOPWORDS = frozenset({
    "그리고", "하지만", "그래서", "때문에", "그런데", "그러나", "또한", "이런", "저런", "그런",
    "이것", "저것", "그것", "여기", "거기", "저기", "우리", "너희", "이번", "다음",
})


@dataclass(frozen=True)
class Fact:
    content: str
    source: str


@dataclass(frozen=True)
class SituationAnalysisResult:
    facts: list[Fact]
    intent: str
    prompt_tokens: int
    completion_tokens: int


SYSTEM_PROMPT = (
    "당신은 한국어 메시지 상황 분석 전문가입니다.\n"
    "원문과 메타데이터를 분석하여 객관적 사실(facts)과 화자의 핵심 목적(intent)을 추출합니다.\n\n"
    "## 규칙\n"
    "1. facts: 원문에서 직접 읽히는 객관적 사실만 추출 (최대 5개)\n"
    "2. 각 fact의 content: 사실을 명확한 1문장으로 요약\n"
    "3. 각 fact의 source: 해당 사실의 근거가 되는 원문 구절을 **정확히 인용** (변형 금지)\n"
    "4. intent: 화자의 핵심 전달 목적을 1~2문장으로 요약\n"
    '5. 지시대명사("그거", "이것", "저기") → 원문 맥락에서 해석하여 구체적 대상으로 복원\n'
    "6. 생략된 주어 → 문맥에서 추론하여 복원\n"
    "7. `{{TYPE_N}}` 형식 플레이스홀더(예: {{DATE_1}}, {{PHONE_1}})는 그대로 유지\n"
    "8. 근거 없는 추측 금지. 원문에서 직접 읽히는 것만\n\n"
    "## 출력 형식 (JSON만, 다른 텍스트 금지)\n"
    "{\n"
    '  "facts": [\n'
    '    {"content": "사실 요약", "source": "원문 그대로 인용"},\n'
    "    ...\n"
    "  ],\n"
    '  "intent": "화자의 핵심 목적"\n'
    "}\n\n"
    "## 예시\n\n"
    "입력:\n"
    "받는 사람: 학부모\n"
    "상황: 피드백\n"
    "원문:\n"
    "아이가 수학 시험에서 {{UNIT_NUMBER_1}} 맞았는데 그거 반 평균보다 낮은 거잖아요. "
    "선생님이 보충수업 해주신다고 했는데 아직 연락이 없어서요.\n\n"
    "출력:\n"
    "{\n"
    '  "facts": [\n'
    '    {"content": "아이의 수학 시험 점수가 {{UNIT_NUMBER_1}}이다", '
    '"source": "아이가 수학 시험에서 {{UNIT_NUMBER_1}} 맞았는데"},\n'
    '    {"content": "아이의 점수가 반 평균보다 낮다", '
    '"source": "그거 반 평균보다 낮은 거잖아요"},\n'
    '    {"content": "선생님이 보충수업을 해주기로 했으나 아직 연락이 없다", '
    '"source": "선생님이 보충수업 해주신다고 했는데 아직 연락이 없어서요"}\n'
    "  ],\n"
    '  "intent": "보충수업 일정을 확인하고, 아이의 성적 개선을 위한 후속 조치를 요청하려는 목적"\n'
    "}"
)


async def analyze(
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
    masked_text: str,
    user_prompt: str | None,
    sender_info: str | None,
    ai_call_fn,
) -> SituationAnalysisResult:
    """Run situation analysis LLM call.

    Args:
        ai_call_fn: async function(model, system, user, temp, max_tokens, analysis_context) -> LlmCallResult
    """
    parts: list[str] = []
    parts.append(f"받는 사람: {prompt_builder.get_persona_label(persona)}")
    parts.append(f"상황: {', '.join(prompt_builder.get_context_label(ctx) for ctx in contexts)}")
    parts.append(f"말투 강도: {prompt_builder.get_tone_label(tone_level)}")
    if sender_info and sender_info.strip():
        parts.append(f"보내는 사람: {sender_info}")
    if user_prompt and user_prompt.strip():
        parts.append(f"참고 맥락: {user_prompt}")
    parts.append(f"\n원문:\n{masked_text}")

    user_message = "\n".join(parts)

    try:
        result: LlmCallResult = await ai_call_fn(MODEL, SYSTEM_PROMPT, user_message, TEMPERATURE, MAX_TOKENS, None)
        return _parse_result(result)
    except Exception as e:
        logger.warning("[SituationAnalysis] LLM call failed, returning empty result: %s", e)
        return SituationAnalysisResult(facts=[], intent="", prompt_tokens=0, completion_tokens=0)


def filter_red_facts(
    original: SituationAnalysisResult,
    masked_text: str,
    labeled_segments: list[LabeledSegment],
) -> SituationAnalysisResult:
    """Filter out facts whose source overlaps with RED-labeled segments.

    Matching strategy (3-tier fallback):
    1. Exact indexOf → position-based overlap check
    2. Normalized contains → RED segment text contains normalized fact source
    3. Semantic word overlap → 2+ meaningful words from fact.source found in RED segment text
    """
    red_segments = [ls for ls in labeled_segments if ls.label.tier == SegmentLabelTier.RED]

    if not red_segments:
        return original

    filtered_facts: list[Fact] = []
    for fact in original.facts:
        if not fact.source or not fact.source.strip():
            filtered_facts.append(fact)
            continue

        # Strategy 1: Exact indexOf with position-based overlap
        fact_start = masked_text.find(fact.source)
        if fact_start >= 0:
            fact_end = fact_start + len(fact.source)
            overlaps_red = any(
                fact_start < red.end and fact_end > red.start
                for red in red_segments
            )
            if overlaps_red:
                logger.info("[SituationAnalysis] Filtered RED-overlapping fact (exact): %s", fact.content)
                continue
            filtered_facts.append(fact)
            continue

        # Strategy 2: Normalized contains
        normalized_source = _normalize_for_match(fact.source)
        if normalized_source:
            normalized_match = any(
                normalized_source in _normalize_for_match(red.text)
                for red in red_segments
            )
            if normalized_match:
                logger.info("[SituationAnalysis] Filtered RED-overlapping fact (normalized): %s", fact.content)
                continue

        # Strategy 3: Semantic word overlap
        source_words = _extract_meaning_words(fact.source)
        if len(source_words) >= 2:
            semantic_match = any(
                sum(1 for w in source_words if w in red.text) >= 2
                for red in red_segments
            )
            if semantic_match:
                logger.info("[SituationAnalysis] Filtered RED-overlapping fact (semantic): %s", fact.content)
                continue

        filtered_facts.append(fact)

    return SituationAnalysisResult(
        facts=filtered_facts,
        intent=original.intent,
        prompt_tokens=original.prompt_tokens,
        completion_tokens=original.completion_tokens,
    )


def _normalize_for_match(text: str) -> str:
    return re.sub(r"[^가-힣a-zA-Z0-9]", "", text).lower()


def _extract_meaning_words(text: str) -> list[str]:
    words: list[str] = []
    for m in _KOREAN_WORD_PATTERN.finditer(text):
        word = m.group()
        if word not in _STOPWORDS:
            words.append(word)
    return words


def _parse_result(result: LlmCallResult) -> SituationAnalysisResult:
    try:
        root = json.loads(result.content)

        facts: list[Fact] = []
        for fact_node in root.get("facts", []):
            content = fact_node.get("content", "")
            source = fact_node.get("source", "")
            if content:
                facts.append(Fact(content=content, source=source))

        intent = root.get("intent", "")

        return SituationAnalysisResult(
            facts=facts,
            intent=intent,
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )
    except Exception as e:
        logger.warning("[SituationAnalysis] Parse failed: %s", e)
        return SituationAnalysisResult(
            facts=[],
            intent="",
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )
