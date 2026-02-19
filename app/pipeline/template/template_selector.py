"""Template selector — PURPOSE→CONTEXT→keyword→S2 enforcement→skip rules."""

import logging
import re
from dataclasses import dataclass

from app.models.domain import LabelStats
from app.models.enums import Persona, Purpose, SituationContext, Topic
from app.pipeline.template.structure_template import StructureSection, StructureTemplate
from app.pipeline.template.template_registry import TemplateRegistry

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class TemplateSelectionResult:
    template: StructureTemplate
    s2_enforced: bool
    effective_sections: list[StructureSection]


# PURPOSE → template ID mapping
_PURPOSE_TEMPLATE_MAP: dict[Purpose, str] = {
    Purpose.INFO_DELIVERY: "T01_GENERAL",
    Purpose.DATA_REQUEST: "T02_DATA_REQUEST",
    Purpose.SCHEDULE_COORDINATION: "T04_SCHEDULE",
    Purpose.APOLOGY_RECOVERY: "T05_APOLOGY",
    Purpose.RESPONSIBILITY_SEPARATION: "T09_BLAME_SEPARATION",
    Purpose.REJECTION_NOTICE: "T06_REJECTION",
    Purpose.REFUND_REJECTION: "T11_REFUND_REJECTION",
    Purpose.WARNING_PREVENTION: "T12_WARNING_PREVENTION",
    Purpose.RELATIONSHIP_RECOVERY: "T10_RELATIONSHIP_RECOVERY",
    Purpose.NEXT_ACTION_CONFIRM: "T01_GENERAL",
    Purpose.ANNOUNCEMENT: "T07_ANNOUNCEMENT",
}

# CONTEXT → template ID mapping (primary context)
_CONTEXT_TEMPLATE_MAP: dict[SituationContext, str] = {
    SituationContext.REQUEST: "T02_DATA_REQUEST",
    SituationContext.SCHEDULE_DELAY: "T04_SCHEDULE",
    SituationContext.URGING: "T03_NAGGING_REMINDER",
    SituationContext.REJECTION: "T06_REJECTION",
    SituationContext.APOLOGY: "T05_APOLOGY",
    SituationContext.COMPLAINT: "T09_BLAME_SEPARATION",
    SituationContext.ANNOUNCEMENT: "T07_ANNOUNCEMENT",
    SituationContext.FEEDBACK: "T08_FEEDBACK",
    SituationContext.BILLING: "T09_BLAME_SEPARATION",
    SituationContext.SUPPORT: "T05_APOLOGY",
    SituationContext.CONTRACT: "T06_REJECTION",
    SituationContext.RECRUITING: "T01_GENERAL",
    SituationContext.CIVIL_COMPLAINT: "T09_BLAME_SEPARATION",
    SituationContext.GRATITUDE: "T10_RELATIONSHIP_RECOVERY",
}

_REFUND_KEYWORDS = re.compile(r"환불|취소|반품|결제\s*취소|카드\s*취소|refund|cancel")


def select_template(
    registry: TemplateRegistry,
    persona: Persona,
    contexts: list[SituationContext],
    topic: Topic | None,
    purpose: Purpose | None,
    label_stats: LabelStats,
    masked_text: str | None,
) -> TemplateSelectionResult:
    # 1. PURPOSE provided → direct mapping
    if purpose is not None:
        template_id = _PURPOSE_TEMPLATE_MAP.get(purpose, "T01_GENERAL")
        logger.info("[TemplateSelector] Selected by PURPOSE: %s → %s", purpose, template_id)
    # 2. Primary CONTEXT → mapping
    elif contexts:
        primary_context = contexts[0]
        template_id = _CONTEXT_TEMPLATE_MAP.get(primary_context, "T01_GENERAL")
        logger.info("[TemplateSelector] Selected by CONTEXT: %s → %s", primary_context, template_id)
    # 3. Default
    else:
        template_id = "T01_GENERAL"
        logger.info("[TemplateSelector] Default template: T01_GENERAL")

    # 4. Topic override: REFUND_CANCEL + rejection-like → T11
    if topic == Topic.REFUND_CANCEL and _is_rejection_like(purpose, contexts):
        template_id = "T11_REFUND_REJECTION"
        logger.info("[TemplateSelector] Topic override → T11_REFUND_REJECTION")

    # 5. Keyword override: refund keywords + rejection labels
    if (
        template_id != "T11_REFUND_REJECTION"
        and masked_text is not None
        and _REFUND_KEYWORDS.search(masked_text)
        and (label_stats.has_negative_feedback or _is_rejection_like(purpose, contexts))
    ):
        template_id = "T11_REFUND_REJECTION"
        logger.info("[TemplateSelector] Keyword override → T11_REFUND_REJECTION")

    template = registry.get(template_id)

    # 6. S2 enforcement: ACCOUNTABILITY or NEGATIVE_FEEDBACK → inject S2 if missing
    s2_enforced = False
    sections = list(template.section_order)
    if (
        (label_stats.has_accountability or label_stats.has_negative_feedback)
        and StructureSection.S2_OUR_EFFORT not in sections
    ):
        insert_idx = -1
        if StructureSection.S1_ACKNOWLEDGE in sections:
            insert_idx = sections.index(StructureSection.S1_ACKNOWLEDGE)
        elif StructureSection.S0_GREETING in sections:
            insert_idx = sections.index(StructureSection.S0_GREETING)
        sections.insert(insert_idx + 1, StructureSection.S2_OUR_EFFORT)
        s2_enforced = True
        logger.info("[TemplateSelector] S2 enforced for ACCOUNTABILITY/NEGATIVE_FEEDBACK")

    # 7. Apply persona skip rules → produce effectiveSections
    effective = _apply_skip_rules(sections, template, persona)

    return TemplateSelectionResult(
        template=template,
        s2_enforced=s2_enforced,
        effective_sections=effective,
    )


def _is_rejection_like(purpose: Purpose | None, contexts: list[SituationContext]) -> bool:
    if purpose in (Purpose.REJECTION_NOTICE, Purpose.REFUND_REJECTION):
        return True
    return SituationContext.REJECTION in contexts


def _apply_skip_rules(
    sections: list[StructureSection],
    template: StructureTemplate,
    persona: Persona,
) -> list[StructureSection]:
    if not template.persona_skip_rules or persona not in template.persona_skip_rules:
        return sections

    rule = template.persona_skip_rules[persona]
    return [s for s in sections if s not in rule.skip_sections]
