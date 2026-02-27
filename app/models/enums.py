from enum import Enum


class Topic(str, Enum):
    REFUND_CANCEL = "REFUND_CANCEL"
    OUTAGE_ERROR = "OUTAGE_ERROR"
    ACCOUNT_PERMISSION = "ACCOUNT_PERMISSION"
    DATA_FILE = "DATA_FILE"
    SCHEDULE_DEADLINE = "SCHEDULE_DEADLINE"
    COST_BILLING = "COST_BILLING"
    CONTRACT_TERMS = "CONTRACT_TERMS"
    HR_EVALUATION = "HR_EVALUATION"
    ACADEMIC_GRADE = "ACADEMIC_GRADE"
    COMPLAINT_REGULATION = "COMPLAINT_REGULATION"
    OTHER = "OTHER"


class Purpose(str, Enum):
    INFO_DELIVERY = "INFO_DELIVERY"
    DATA_REQUEST = "DATA_REQUEST"
    SCHEDULE_COORDINATION = "SCHEDULE_COORDINATION"
    APOLOGY_RECOVERY = "APOLOGY_RECOVERY"
    RESPONSIBILITY_SEPARATION = "RESPONSIBILITY_SEPARATION"
    REJECTION_NOTICE = "REJECTION_NOTICE"
    REFUND_REJECTION = "REFUND_REJECTION"
    WARNING_PREVENTION = "WARNING_PREVENTION"
    RELATIONSHIP_RECOVERY = "RELATIONSHIP_RECOVERY"
    NEXT_ACTION_CONFIRM = "NEXT_ACTION_CONFIRM"
    ANNOUNCEMENT = "ANNOUNCEMENT"


class SegmentLabelTier(str, Enum):
    GREEN = "GREEN"
    YELLOW = "YELLOW"
    RED = "RED"


class SegmentLabel(str, Enum):
    # GREEN (preserve) - message skeleton, must include, style polish only
    CORE_FACT = "CORE_FACT"
    CORE_INTENT = "CORE_INTENT"
    REQUEST = "REQUEST"
    APOLOGY = "APOLOGY"
    COURTESY = "COURTESY"
    # YELLOW (modify) - content preserved, delivery method changed
    ACCOUNTABILITY = "ACCOUNTABILITY"
    SELF_JUSTIFICATION = "SELF_JUSTIFICATION"
    NEGATIVE_FEEDBACK = "NEGATIVE_FEEDBACK"
    EMOTIONAL = "EMOTIONAL"
    EXCESS_DETAIL = "EXCESS_DETAIL"
    # RED (remove) - content itself is unnecessary and harmful
    AGGRESSION = "AGGRESSION"
    PERSONAL_ATTACK = "PERSONAL_ATTACK"
    PRIVATE_TMI = "PRIVATE_TMI"
    PURE_GRUMBLE = "PURE_GRUMBLE"

    @property
    def tier(self) -> SegmentLabelTier:
        _tier_map = {
            SegmentLabel.CORE_FACT: SegmentLabelTier.GREEN,
            SegmentLabel.CORE_INTENT: SegmentLabelTier.GREEN,
            SegmentLabel.REQUEST: SegmentLabelTier.GREEN,
            SegmentLabel.APOLOGY: SegmentLabelTier.GREEN,
            SegmentLabel.COURTESY: SegmentLabelTier.GREEN,
            SegmentLabel.ACCOUNTABILITY: SegmentLabelTier.YELLOW,
            SegmentLabel.SELF_JUSTIFICATION: SegmentLabelTier.YELLOW,
            SegmentLabel.NEGATIVE_FEEDBACK: SegmentLabelTier.YELLOW,
            SegmentLabel.EMOTIONAL: SegmentLabelTier.YELLOW,
            SegmentLabel.EXCESS_DETAIL: SegmentLabelTier.YELLOW,
            SegmentLabel.AGGRESSION: SegmentLabelTier.RED,
            SegmentLabel.PERSONAL_ATTACK: SegmentLabelTier.RED,
            SegmentLabel.PRIVATE_TMI: SegmentLabelTier.RED,
            SegmentLabel.PURE_GRUMBLE: SegmentLabelTier.RED,
        }
        return _tier_map[self]


class LockedSpanType(str, Enum):
    EMAIL = "EMAIL"
    URL = "URL"
    ACCOUNT = "ACCOUNT"
    DATE = "DATE"
    TIME = "TIME"
    TIME_HH_MM = "TIME"
    PHONE = "PHONE"
    MONEY = "MONEY"
    UNIT_NUMBER = "NUMBER"
    LARGE_NUMBER = "NUMBER"
    UUID = "UUID"
    FILE_PATH = "FILE"
    ISSUE_TICKET = "TICKET"
    VERSION = "VERSION"
    QUOTED_TEXT = "QUOTE"
    IDENTIFIER = "ID"
    HASH_COMMIT = "HASH"
    SEMANTIC = "NAME"

    @property
    def placeholder_prefix(self) -> str:
        return self.value


class ValidationIssueType(str, Enum):
    EMOJI = "EMOJI"
    FORBIDDEN_PHRASE = "FORBIDDEN_PHRASE"
    HALLUCINATED_FACT = "HALLUCINATED_FACT"
    ENDING_REPETITION = "ENDING_REPETITION"
    LENGTH_OVEREXPANSION = "LENGTH_OVEREXPANSION"
    PERSPECTIVE_ERROR = "PERSPECTIVE_ERROR"
    LOCKED_SPAN_MISSING = "LOCKED_SPAN_MISSING"
    REDACTED_REENTRY = "REDACTED_REENTRY"
    REDACTION_TRACE = "REDACTION_TRACE"
    CORE_NUMBER_MISSING = "CORE_NUMBER_MISSING"
    CORE_DATE_MISSING = "CORE_DATE_MISSING"
    SOFTEN_CONTENT_DROPPED = "SOFTEN_CONTENT_DROPPED"
    SECTION_S2_MISSING = "SECTION_S2_MISSING"
    INFORMAL_CONJUNCTION = "INFORMAL_CONJUNCTION"


class Severity(str, Enum):
    ERROR = "ERROR"
    WARNING = "WARNING"


class UserTier(str, Enum):
    FREE = "FREE"
    PAID = "PAID"
