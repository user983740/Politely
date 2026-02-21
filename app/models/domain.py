from dataclasses import dataclass, field

from app.models.enums import (
    LockedSpanType,
    SegmentLabel,
    Severity,
    ValidationIssueType,
)


@dataclass(frozen=True)
class LockedSpan:
    index: int
    original_text: str
    placeholder: str
    type: LockedSpanType
    start_pos: int
    end_pos: int


@dataclass(frozen=True)
class Segment:
    id: str
    text: str
    start: int
    end: int


@dataclass(frozen=True)
class LabeledSegment:
    segment_id: str
    label: SegmentLabel
    text: str
    start: int
    end: int


@dataclass(frozen=True)
class TransformResult:
    transformed_text: str
    analysis_context: str | None = None


@dataclass(frozen=True)
class ValidationIssue:
    type: ValidationIssueType
    severity: Severity
    message: str
    matched_text: str | None = None


@dataclass(frozen=True)
class ValidationResult:
    passed: bool
    issues: list[ValidationIssue] = field(default_factory=list)

    def has_errors(self) -> bool:
        return any(i.severity == Severity.ERROR for i in self.issues)

    def errors(self) -> list[ValidationIssue]:
        return [i for i in self.issues if i.severity == Severity.ERROR]

    def warnings(self) -> list[ValidationIssue]:
        return [i for i in self.issues if i.severity == Severity.WARNING]


@dataclass(frozen=True)
class LlmCallResult:
    content: str
    analysis_context: str | None
    prompt_tokens: int
    completion_tokens: int


@dataclass(frozen=True)
class PipelineStats:
    analysis_prompt_tokens: int
    analysis_completion_tokens: int
    final_prompt_tokens: int
    final_completion_tokens: int
    segment_count: int
    green_count: int
    yellow_count: int
    red_count: int
    locked_span_count: int
    retry_count: int
    identity_booster_fired: bool
    situation_analysis_fired: bool
    metadata_overridden: bool
    chosen_template_id: str
    total_latency_ms: int


@dataclass(frozen=True)
class LabelStats:
    green_count: int
    yellow_count: int
    red_count: int
    has_accountability: bool
    has_negative_feedback: bool
    has_emotional: bool
    has_self_justification: bool
    has_aggression: bool

    @staticmethod
    def from_segments(segments: list[LabeledSegment]) -> "LabelStats":
        from app.models.enums import SegmentLabelTier

        green = yellow = red = 0
        accountability = negative_feedback = emotional = False
        self_justification = aggression = False

        for seg in segments:
            tier = seg.label.tier
            if tier == SegmentLabelTier.GREEN:
                green += 1
            elif tier == SegmentLabelTier.YELLOW:
                yellow += 1
            elif tier == SegmentLabelTier.RED:
                red += 1

            if seg.label == SegmentLabel.ACCOUNTABILITY:
                accountability = True
            elif seg.label == SegmentLabel.NEGATIVE_FEEDBACK:
                negative_feedback = True
            elif seg.label == SegmentLabel.EMOTIONAL:
                emotional = True
            elif seg.label == SegmentLabel.SELF_JUSTIFICATION:
                self_justification = True
            elif seg.label == SegmentLabel.AGGRESSION:
                aggression = True

        return LabelStats(
            green_count=green,
            yellow_count=yellow,
            red_count=red,
            has_accountability=accountability,
            has_negative_feedback=negative_feedback,
            has_emotional=emotional,
            has_self_justification=self_justification,
            has_aggression=aggression,
        )
