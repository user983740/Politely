"""Optional LLM: metadata mismatch verification (gpt-4o-mini, confidence ≥ 0.72)."""

import json
import logging
from dataclasses import dataclass, field

from app.models.domain import LabeledSegment, LlmCallResult
from app.models.enums import Persona, Purpose, SituationContext, ToneLevel, Topic

logger = logging.getLogger(__name__)

MODEL = "gpt-4o-mini"
TEMPERATURE = 0.2
MAX_TOKENS = 300
OVERRIDE_CONFIDENCE_THRESHOLD = 0.72


@dataclass(frozen=True)
class ContextGatingResult:
    should_override: bool
    confidence: float
    inferred_topic: Topic | None
    inferred_purpose: Purpose | None
    inferred_primary_context: SituationContext | None
    inferred_template_id: str | None
    reasons: list[str] = field(default_factory=list)
    safety_notes: list[str] = field(default_factory=list)
    prompt_tokens: int = 0
    completion_tokens: int = 0

    def meets_threshold(self) -> bool:
        return self.should_override and self.confidence >= OVERRIDE_CONFIDENCE_THRESHOLD


SYSTEM_PROMPT = (
    "당신은 한국어 메시지의 메타데이터 검증 전문가입니다.\n"
    "사용자가 선택한 메타데이터(수신자/상황/주제/목적)와 실제 텍스트 내용이 일치하는지 검증하세요.\n\n"
    "응답은 반드시 JSON 형식으로:\n"
    "{\n"
    '  "should_override": true/false,\n'
    '  "confidence": 0.0~1.0,\n'
    '  "inferred": {\n'
    '    "topic": "ENUM값 또는 null",\n'
    '    "purpose": "ENUM값 또는 null",\n'
    '    "primary_context": "ENUM값 또는 null",\n'
    '    "template_id": "T01~T12 또는 null"\n'
    "  },\n"
    '  "reasons": ["이유1", "이유2"],\n'
    '  "safety_notes": ["주의사항"]\n'
    "}\n\n"
    "Topic: REFUND_CANCEL, OUTAGE_ERROR, ACCOUNT_PERMISSION, DATA_FILE, SCHEDULE_DEADLINE, "
    "COST_BILLING, CONTRACT_TERMS, HR_EVALUATION, ACADEMIC_GRADE, COMPLAINT_REGULATION, OTHER\n"
    "Purpose: INFO_DELIVERY, DATA_REQUEST, SCHEDULE_COORDINATION, APOLOGY_RECOVERY, "
    "RESPONSIBILITY_SEPARATION, REJECTION_NOTICE, REFUND_REJECTION, WARNING_PREVENTION, "
    "RELATIONSHIP_RECOVERY, NEXT_ACTION_CONFIRM, ANNOUNCEMENT\n"
    "Context: REQUEST, SCHEDULE_DELAY, URGING, REJECTION, APOLOGY, COMPLAINT, ANNOUNCEMENT, "
    "FEEDBACK, BILLING, SUPPORT, CONTRACT, RECRUITING, CIVIL_COMPLAINT, GRATITUDE\n\n"
    "규칙:\n"
    "- 메타데이터가 텍스트 내용과 명백히 불일치할 때만 should_override=true\n"
    "- 애매한 경우 should_override=false (사용자 의도 존중)\n"
    "- inferred 값은 확신이 있을 때만 제공, 아니면 null"
)


async def evaluate(
    persona: Persona,
    contexts: list[SituationContext],
    topic: Topic | None,
    purpose: Purpose | None,
    tone_level: ToneLevel,
    masked_text: str,
    labeled_segments: list[LabeledSegment],
    ai_call_fn,
) -> ContextGatingResult:
    """Evaluate metadata mismatch.

    Args:
        ai_call_fn: async function(model, system, user, temp, max_tokens, analysis_context) -> LlmCallResult
    """
    label_summary = ", ".join(f"{ls.segment_id}:{ls.label.name}" for ls in labeled_segments)

    truncated_text = masked_text[:1200] + "..." if len(masked_text) > 1200 else masked_text

    user_message = (
        f"사용자 메타:\n"
        f"- 수신자: {persona.name}\n"
        f"- 상황: {', '.join(ctx.name for ctx in contexts)}\n"
        f"- 주제: {topic.name if topic else '미지정'}\n"
        f"- 목적: {purpose.name if purpose else '미지정'}\n"
        f"- 톤: {tone_level.name}\n\n"
        f"라벨 요약: {label_summary}\n\n"
        f"텍스트 (마스킹):\n{truncated_text}"
    )

    try:
        result: LlmCallResult = await ai_call_fn(MODEL, SYSTEM_PROMPT, user_message, TEMPERATURE, MAX_TOKENS, None)
        return _parse_result(result)
    except Exception as e:
        logger.warning("[ContextGating] LLM call failed, returning no-override: %s", e)
        return ContextGatingResult(
            should_override=False,
            confidence=0,
            inferred_topic=None,
            inferred_purpose=None,
            inferred_primary_context=None,
            inferred_template_id=None,
            reasons=[],
            safety_notes=[f"LLM call failed: {e}"],
            prompt_tokens=0,
            completion_tokens=0,
        )


def _parse_result(result: LlmCallResult) -> ContextGatingResult:
    try:
        root = json.loads(result.content)

        should_override = root.get("should_override", False)
        confidence = root.get("confidence", 0.0)

        inferred = root.get("inferred", {})
        inferred_topic = _parse_enum(Topic, inferred.get("topic"))
        inferred_purpose = _parse_enum(Purpose, inferred.get("purpose"))
        inferred_context = _parse_enum(SituationContext, inferred.get("primary_context"))
        inferred_template_id = inferred.get("template_id")
        if inferred_template_id in (None, "null", ""):
            inferred_template_id = None

        reasons = _json_array_to_list(root.get("reasons"))
        safety_notes = _json_array_to_list(root.get("safety_notes"))

        return ContextGatingResult(
            should_override=should_override,
            confidence=confidence,
            inferred_topic=inferred_topic,
            inferred_purpose=inferred_purpose,
            inferred_primary_context=inferred_context,
            inferred_template_id=inferred_template_id,
            reasons=reasons,
            safety_notes=safety_notes,
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )
    except Exception as e:
        logger.warning("[ContextGating] Parse failed: %s", e)
        return ContextGatingResult(
            should_override=False,
            confidence=0,
            inferred_topic=None,
            inferred_purpose=None,
            inferred_primary_context=None,
            inferred_template_id=None,
            reasons=[],
            safety_notes=[f"Parse failed: {e}"],
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )


def _parse_enum(enum_class, value):
    if value is None or value == "" or value == "null":
        return None
    try:
        return enum_class(value.strip())
    except (ValueError, AttributeError):
        return None


def _json_array_to_list(node) -> list[str]:
    if node is None or not isinstance(node, list):
        return []
    return [str(item) for item in node]
