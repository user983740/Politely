"""Situation analysis: extracts objective facts, core intent, and metadata validation.

Outputs structured JSON (facts + intent + metadata_check).
Provides Final model with accurate context to reduce hallucination.
Metadata validation replaces the old ContextGating separate LLM call.
"""

import json
import logging
import re
from dataclasses import dataclass

from app.models.domain import LabeledSegment, LlmCallResult
from app.models.enums import Persona, Purpose, SegmentLabelTier, SituationContext, ToneLevel, Topic
from app.pipeline import prompt_builder

logger = logging.getLogger(__name__)

MODEL = "gpt-4o-mini"
TEMPERATURE = 0.2
MAX_TOKENS = 650
OVERRIDE_CONFIDENCE_THRESHOLD = 0.72

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
class MetadataCheck:
    should_override: bool
    confidence: float
    inferred_topic: Topic | None
    inferred_purpose: Purpose | None
    inferred_primary_context: SituationContext | None

    def meets_threshold(self) -> bool:
        return self.should_override and self.confidence >= OVERRIDE_CONFIDENCE_THRESHOLD


@dataclass(frozen=True)
class SituationAnalysisResult:
    facts: list[Fact]
    intent: str
    prompt_tokens: int
    completion_tokens: int
    metadata_check: MetadataCheck | None = None


SYSTEM_PROMPT = (
    "당신은 한국어 메시지 상황 분석 전문가입니다.\n"
    "원문과 메타데이터를 분석하여 객관적 사실(facts)과 화자의 핵심 목적(intent)을 추출합니다.\n"
    "또한 사용자가 선택한 메타데이터(주제/목적)가 실제 텍스트와 일치하는지 검증합니다.\n\n"
    "## 규칙\n"
    "1. facts: 원문에서 직접 읽히는 객관적 사실만 추출 (최대 5개)\n"
    "2. 각 fact의 content: 사실을 명확한 1문장으로 요약\n"
    "3. 각 fact의 source: 해당 사실의 근거가 되는 원문 구절을 **정확히 인용** (변형 금지)\n"
    "4. intent: 화자의 핵심 전달 목적을 1~2문장으로 요약\n"
    '5. 지시대명사("그거", "이것", "저기") → 원문 맥락에서 해석하여 구체적 대상으로 복원\n'
    "6. 생략된 주어 → 문맥에서 추론하여 복원\n"
    "7. `{{TYPE_N}}` 형식 플레이스홀더(예: {{DATE_1}}, {{PHONE_1}})는 그대로 유지\n"
    "8. 근거 없는 추측 금지. 원문에서 직접 읽히는 것만\n\n"
    "## 메타데이터 검증 규칙\n"
    "주제(topic)와 목적(purpose)이 제공된 경우, 실제 텍스트 내용과 비교하여 검증합니다.\n"
    "- 메타데이터가 텍스트와 **명백히 불일치**할 때만 should_override=true\n"
    "- 애매하거나 부분적으로 일치하면 should_override=false (사용자 의도 존중)\n"
    "- confidence: 불일치 확신도 (0.0~1.0)\n"
    "- inferred 값은 확신이 있을 때만 제공, 아니면 null\n\n"
    "Topic 값: REFUND_CANCEL, OUTAGE_ERROR, ACCOUNT_PERMISSION, DATA_FILE, SCHEDULE_DEADLINE, "
    "COST_BILLING, CONTRACT_TERMS, HR_EVALUATION, ACADEMIC_GRADE, COMPLAINT_REGULATION, OTHER\n"
    "Purpose 값: INFO_DELIVERY, DATA_REQUEST, SCHEDULE_COORDINATION, APOLOGY_RECOVERY, "
    "RESPONSIBILITY_SEPARATION, REJECTION_NOTICE, REFUND_REJECTION, WARNING_PREVENTION, "
    "RELATIONSHIP_RECOVERY, NEXT_ACTION_CONFIRM, ANNOUNCEMENT\n"
    "Context 값: REQUEST, SCHEDULE_DELAY, URGING, REJECTION, APOLOGY, COMPLAINT, ANNOUNCEMENT, "
    "FEEDBACK, BILLING, SUPPORT, CONTRACT, RECRUITING, CIVIL_COMPLAINT, GRATITUDE\n\n"
    "## 출력 형식 (JSON만, 다른 텍스트 금지)\n"
    "{\n"
    '  "facts": [\n'
    '    {"content": "사실 요약", "source": "원문 그대로 인용"},\n'
    "    ...\n"
    "  ],\n"
    '  "intent": "화자의 핵심 목적",\n'
    '  "metadata_check": {\n'
    '    "should_override": false,\n'
    '    "confidence": 0.0,\n'
    '    "inferred": {\n'
    '      "topic": null,\n'
    '      "purpose": null,\n'
    '      "primary_context": null\n'
    "    }\n"
    "  }\n"
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
    '  "intent": "보충수업 일정을 확인하고, 아이의 성적 개선을 위한 후속 조치를 요청하려는 목적",\n'
    '  "metadata_check": {\n'
    '    "should_override": false,\n'
    '    "confidence": 0.0,\n'
    '    "inferred": {"topic": null, "purpose": null, "primary_context": null}\n'
    "  }\n"
    "}"
)


SYSTEM_PROMPT_TEXT_ONLY = (
    "당신은 한국어 메시지 상황 분석 전문가입니다.\n"
    "원문에서 객관적 사실(facts)과 화자의 핵심 목적(intent)을 추출합니다.\n\n"
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
    "}"
)


async def analyze_text_only(
    masked_text: str,
    sender_info: str | None,
    ai_call_fn,
    user_prompt: str | None = None,
) -> SituationAnalysisResult:
    """Run text-only situation analysis (facts + intent only, no metadata_check)."""
    parts: list[str] = []
    if sender_info and sender_info.strip():
        parts.append(f"보내는 사람: {sender_info}")
    if user_prompt and user_prompt.strip():
        parts.append(f"추가 정보: {user_prompt}")
    parts.append(f"\n원문:\n{masked_text}")

    user_message = "\n".join(parts)

    try:
        result: LlmCallResult = await ai_call_fn(
            MODEL, SYSTEM_PROMPT_TEXT_ONLY, user_message, TEMPERATURE, MAX_TOKENS, None,
        )
        return _parse_result_text_only(result)
    except Exception as e:
        logger.warning("[SituationAnalysis] Text-only LLM call failed, returning empty result: %s", e)
        return SituationAnalysisResult(facts=[], intent="", prompt_tokens=0, completion_tokens=0)


async def analyze(
    persona: Persona,
    contexts: list[SituationContext],
    tone_level: ToneLevel,
    masked_text: str,
    user_prompt: str | None,
    sender_info: str | None,
    ai_call_fn,
    topic: Topic | None = None,
    purpose: Purpose | None = None,
) -> SituationAnalysisResult:
    """Run situation analysis LLM call with integrated metadata validation.

    Args:
        ai_call_fn: async function(model, system, user, temp, max_tokens, analysis_context) -> LlmCallResult
        topic: user-selected topic for metadata validation
        purpose: user-selected purpose for metadata validation
    """
    parts: list[str] = []
    parts.append(f"받는 사람: {prompt_builder.get_persona_label(persona)}")
    parts.append(f"상황: {', '.join(prompt_builder.get_context_label(ctx) for ctx in contexts)}")
    parts.append(f"말투 강도: {prompt_builder.get_tone_label(tone_level)}")
    if topic:
        parts.append(f"주제: {topic.name}")
    if purpose:
        parts.append(f"목적: {purpose.name}")
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
        metadata_check=original.metadata_check,
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
        metadata_check = _parse_metadata_check(root.get("metadata_check"))

        return SituationAnalysisResult(
            facts=facts,
            intent=intent,
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
            metadata_check=metadata_check,
        )
    except Exception as e:
        logger.warning("[SituationAnalysis] Parse failed: %s", e)
        return SituationAnalysisResult(
            facts=[],
            intent="",
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )


def _parse_metadata_check(node) -> MetadataCheck | None:
    if node is None or not isinstance(node, dict):
        return None
    try:
        should_override = node.get("should_override", False)
        confidence = float(node.get("confidence", 0.0))
        inferred = node.get("inferred", {})
        inferred_topic = _parse_enum(Topic, inferred.get("topic"))
        inferred_purpose = _parse_enum(Purpose, inferred.get("purpose"))
        inferred_context = _parse_enum(SituationContext, inferred.get("primary_context"))
        return MetadataCheck(
            should_override=should_override,
            confidence=confidence,
            inferred_topic=inferred_topic,
            inferred_purpose=inferred_purpose,
            inferred_primary_context=inferred_context,
        )
    except Exception as e:
        logger.warning("[SituationAnalysis] MetadataCheck parse failed: %s", e)
        return None


def _parse_enum(enum_class, value):
    if value is None or value == "" or value == "null":
        return None
    try:
        return enum_class(value.strip())
    except (ValueError, AttributeError):
        return None


def _parse_result_text_only(result: LlmCallResult) -> SituationAnalysisResult:
    """Parse text-only SA response (facts + intent only, no metadata_check)."""
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
        logger.warning("[SituationAnalysis] Text-only parse failed: %s", e)
        return SituationAnalysisResult(
            facts=[],
            intent="",
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )
