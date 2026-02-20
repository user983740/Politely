"""Tests for domain model dataclasses."""

from datetime import datetime, timedelta

from app.models.domain import LabeledSegment, LabelStats
from app.models.enums import SegmentLabel
from app.models.user import EmailVerification

# --- LabelStats ---


def test_label_stats_from_segments_counts_tiers():
    segments = [
        LabeledSegment("T1", SegmentLabel.CORE_FACT, "a", 0, 1),
        LabeledSegment("T2", SegmentLabel.CORE_INTENT, "b", 1, 2),
        LabeledSegment("T3", SegmentLabel.ACCOUNTABILITY, "c", 2, 3),
        LabeledSegment("T4", SegmentLabel.AGGRESSION, "d", 3, 4),
    ]
    stats = LabelStats.from_segments(segments)
    assert stats.green_count == 2
    assert stats.yellow_count == 1
    assert stats.red_count == 1


def test_label_stats_from_segments_detects_flags():
    segments = [
        LabeledSegment("T1", SegmentLabel.ACCOUNTABILITY, "a", 0, 1),
        LabeledSegment("T2", SegmentLabel.NEGATIVE_FEEDBACK, "b", 1, 2),
        LabeledSegment("T3", SegmentLabel.EMOTIONAL, "c", 2, 3),
        LabeledSegment("T4", SegmentLabel.SELF_JUSTIFICATION, "d", 3, 4),
        LabeledSegment("T5", SegmentLabel.AGGRESSION, "e", 4, 5),
    ]
    stats = LabelStats.from_segments(segments)
    assert stats.has_accountability is True
    assert stats.has_negative_feedback is True
    assert stats.has_emotional is True
    assert stats.has_self_justification is True
    assert stats.has_aggression is True


# --- EmailVerification ---


def test_email_verification_is_expired():
    past = datetime.utcnow() - timedelta(minutes=10)
    v = EmailVerification(
        email="e@e.com", code="123456", verified=False,
        expires_at=past, created_at=past - timedelta(minutes=5),
    )
    assert v.is_expired() is True


def test_email_verification_not_expired():
    future = datetime.utcnow() + timedelta(minutes=5)
    v = EmailVerification(
        email="e@e.com", code="123456", verified=False,
        expires_at=future, created_at=datetime.utcnow(),
    )
    assert v.is_expired() is False


def test_email_verification_mark_verified():
    v = EmailVerification(
        email="e@e.com", code="123456", verified=False,
        expires_at=datetime.utcnow() + timedelta(minutes=5),
        created_at=datetime.utcnow(),
    )
    assert v.verified is False
    v.mark_verified()
    assert v.verified is True
