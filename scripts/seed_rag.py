"""
RAG seed script — populates rag_entries with ~270 Korean expressions.

Usage:
    python -m scripts.seed_rag                      # Full seed (idempotent)
    python -m scripts.seed_rag --category forbidden  # Single category
    python -m scripts.seed_rag --reset               # Delete all + re-seed
    python -m scripts.seed_rag --dry-run             # Count per category, no DB write
"""

from __future__ import annotations

import argparse
import asyncio
import logging

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Seed data — ~270 entries across 6 categories
# ---------------------------------------------------------------------------

def _e(cat, content, **kw):
    """Shorthand for seed entry dict."""
    return {"category": cat, "content": content, **kw}


_EP = "expression_pool"
_CU = "cushion"
_FB = "forbidden"
_PO = "policy"
_EX = "example"
_DC = "domain_context"

# fmt: off
SEED_DATA: list[dict] = [
    # =================================================================
    # expression_pool (~85) — 섹션별 공손한 참고 표현
    # =================================================================

    # --- S0: 인사 (Greeting) ---
    _e(_EP, "바쁘신 중에 시간 내어 말씀해 주셔서 감사합니다.", sections="S0"),
    _e(_EP, "먼저 말씀드리기 어려운 부분이 있어 양해 부탁드립니다.", sections="S0"),
    _e(_EP, "안녕하세요, 연락드리게 되어 감사합니다.", sections="S0", tone_levels="NEUTRAL,POLITE"),
    _e(_EP, "평소 많은 도움 주셔서 항상 감사드립니다.", sections="S0", tone_levels="VERY_POLITE"),
    _e(_EP, "안녕하세요, 아래 내용 관련하여 연락드립니다.", sections="S0", tone_levels="NEUTRAL"),
    _e(_EP, "교수님, 학기 중 바쁘신 와중에 연락드려 죄송합니다.",
       sections="S0", personas="PROFESSOR", tone_levels="VERY_POLITE"),
    _e(_EP, "담당자님, 아래 건으로 문의드립니다.",
       sections="S0", personas="OFFICIAL", tone_levels="POLITE"),
    _e(_EP, "안녕하세요, 지난번 말씀 주신 건 관련입니다.", sections="S0", contexts="FEEDBACK"),
    _e(_EP, "늘 세심하게 신경 써 주셔서 감사합니다.",
       sections="S0", tone_levels="VERY_POLITE", personas="BOSS"),
    _e(_EP, "안녕하세요, 급하게 연락드리게 되었습니다.", sections="S0", contexts="URGING"),

    # --- S1: 공감/유감 (Acknowledge) ---
    _e(_EP, "다름이 아니라 아래 내용에 대해 말씀드리고자 합니다.", sections="S1"),
    _e(_EP, "관련하여 몇 가지 사항을 공유드립니다.", sections="S1"),
    _e(_EP, "해당 건으로 불편을 드린 점 먼저 사과드립니다.", sections="S1", contexts="APOLOGY,COMPLAINT"),
    _e(_EP, "요청해 주신 사항에 대해 안내드리겠습니다.", sections="S1", contexts="REQUEST"),
    _e(_EP, "앞서 논의된 내용을 정리하여 공유드립니다.", sections="S1"),
    _e(_EP, "기다려 주셔서 감사합니다.", sections="S1", contexts="URGING,SCHEDULE_DELAY"),
    _e(_EP, "걱정을 끼쳐 드린 부분 말씀드리겠습니다.", sections="S1", contexts="COMPLAINT"),
    _e(_EP, "그간의 경과를 먼저 공유드립니다.", sections="S1", contexts="SUPPORT"),
    _e(_EP, "도움 주신 덕분에 잘 마무리할 수 있었습니다.", sections="S1", contexts="GRATITUDE"),
    _e(_EP, "사안의 중요성을 감안하여 별도 안내드립니다.", sections="S1", contexts="ANNOUNCEMENT"),

    # --- S2: 내부 확인 (Our Effort) ---
    _e(_EP, "해당 건에 대해 확인한 내용을 안내드립니다.", sections="S2"),
    _e(_EP, "말씀해 주신 부분에 대해 확인 후 안내드립니다.", sections="S2"),
    _e(_EP, "내부적으로 확인한 결과를 공유드립니다.", sections="S2"),
    _e(_EP, "관련 부서와 협의한 내용을 전달드립니다.", sections="S2"),
    _e(_EP, "해당 사안의 경과를 말씀드리겠습니다.", sections="S2"),
    _e(_EP, "로그 기준으로 확인한 결과를 안내드립니다.", sections="S2", contexts="SUPPORT,COMPLAINT"),
    _e(_EP, "담당 부서와 재차 확인한 내용을 공유드립니다.", sections="S2", contexts="COMPLAINT"),
    _e(_EP, "관련 기록을 확인하여 아래와 같이 정리하였습니다.", sections="S2"),

    # --- S3: 핵심 사실 (Facts) ---
    _e(_EP, "확인 결과, 아래와 같은 사항이 파악되었습니다.", sections="S3"),
    _e(_EP, "해당 건의 현황을 정리하면 다음과 같습니다.", sections="S3"),
    _e(_EP, "현재까지 확인된 내용을 안내드립니다.", sections="S3"),
    _e(_EP, "관련 사항을 확인한 바, 아래와 같이 안내드립니다.", sections="S3"),
    _e(_EP, "구체적인 내용은 다음과 같습니다.", sections="S3", tone_levels="NEUTRAL"),
    _e(_EP, "요점을 정리하면 아래와 같습니다.", sections="S3", tone_levels="NEUTRAL"),
    _e(_EP, "해당 건의 핵심 사항은 다음 세 가지입니다.", sections="S3"),
    _e(_EP, "경위를 정리하면 아래와 같습니다.", sections="S3", contexts="COMPLAINT,APOLOGY"),
    _e(_EP, "비용 내역은 다음과 같이 확인됩니다.", sections="S3", contexts="BILLING"),
    _e(_EP, "일정 관련 현황을 안내드립니다.", sections="S3", contexts="SCHEDULE_DELAY"),

    # --- S4: 책임 프레이밍 (Responsibility) ---
    _e(_EP, "해당 상황은 시스템 점검 과정에서 발생한 것으로 파악됩니다.", sections="S4"),
    _e(_EP, "이번 건은 프로세스상의 미비점에서 비롯된 것으로 확인됩니다.", sections="S4"),
    _e(_EP, "해당 문제는 내부 절차 개선이 필요한 부분으로 판단됩니다.", sections="S4"),
    _e(_EP, "이 부분은 담당 부서 간 소통 과정에서 누락이 있었던 것으로 보입니다.", sections="S4"),
    _e(_EP, "원인을 분석한 결과, 업무 전달 과정에서 혼선이 있었던 것으로 파악됩니다.", sections="S4"),
    _e(_EP, "자동화 시스템의 오작동으로 인해 발생한 것으로 확인됩니다.", sections="S4", contexts="SUPPORT"),
    _e(_EP, "기존 프로세스의 예외 상황에 해당하여 정상 처리가 되지 않았습니다.", sections="S4", contexts="COMPLAINT"),
    _e(_EP, "해당 일정 변경은 외부 요인에 의한 불가피한 조정이었습니다.", sections="S4", contexts="SCHEDULE_DELAY"),

    # --- S5: 요청/행동 (Request) ---
    _e(_EP, "번거로우시겠지만 아래 사항 확인 부탁드립니다.",
       sections="S5", tone_levels="POLITE,VERY_POLITE"),
    _e(_EP, "가능하시다면 확인 후 회신 부탁드리겠습니다.",
       sections="S5", tone_levels="POLITE,VERY_POLITE"),
    _e(_EP, "아래 사항에 대해 검토 부탁드립니다.", sections="S5", tone_levels="NEUTRAL"),
    _e(_EP, "가능하시면 금일 중 회신 부탁드리겠습니다.", sections="S5", contexts="URGING"),
    _e(_EP, "해당 자료 송부 부탁드리겠습니다.", sections="S5", contexts="REQUEST"),
    _e(_EP, "아래 서류에 서명 후 회신 부탁드립니다.", sections="S5", contexts="CONTRACT"),
    _e(_EP, "해당 내용 확인하시어 의견 주시면 반영하겠습니다.", sections="S5", contexts="FEEDBACK"),
    _e(_EP, "불편하시더라도 아래 절차를 진행해 주시기 바랍니다.",
       sections="S5", contexts="SUPPORT", tone_levels="POLITE"),
    _e(_EP, "관련 증빙 자료를 첨부하여 보내 주시면 처리하겠습니다.",
       sections="S5", contexts="BILLING,COMPLAINT"),
    _e(_EP, "일정 조율을 위해 가능한 시간대를 알려 주시면 감사하겠습니다.",
       sections="S5", contexts="SCHEDULE_DELAY"),

    # --- S6: 대안/다음 단계 (Options) ---
    _e(_EP, "빠른 시일 내에 처리될 수 있도록 하겠습니다.", sections="S6"),
    _e(_EP, "말씀해 주신 내용을 바탕으로 진행하도록 하겠습니다.", sections="S6"),
    _e(_EP, "대안으로 아래 방안을 제안드립니다.", sections="S6"),
    _e(_EP, "다음과 같은 방향으로 진행하면 어떨까 합니다.", sections="S6"),
    _e(_EP, "이 점 참고하여 진행해 주시면 감사하겠습니다.", sections="S6"),
    _e(_EP, "우선 긴급 조치를 진행하고, 근본 원인은 추가 분석 후 안내드리겠습니다.",
       sections="S6", contexts="SUPPORT"),
    _e(_EP, "다른 일정으로 대체하는 방안도 검토 가능합니다.",
       sections="S6", contexts="SCHEDULE_DELAY,REJECTION"),
    _e(_EP, "환불 대신 동일 금액의 크레딧으로 전환하는 방법도 있습니다.",
       sections="S6", contexts="BILLING,REJECTION"),
    _e(_EP, "재발 방지를 위해 아래와 같이 프로세스를 개선하겠습니다.",
       sections="S6", contexts="COMPLAINT,APOLOGY"),
    _e(_EP, "추가 논의가 필요하시면 별도 미팅을 잡겠습니다.", sections="S6"),

    # --- S7: 정책/한계 (Policy) ---
    _e(_EP, "해당 사항은 현행 규정상 처리가 어려운 점 양해 부탁드립니다.", sections="S7"),
    _e(_EP, "관련 정책에 따라 아래와 같이 안내드립니다.", sections="S7"),
    _e(_EP, "규정 범위 내에서 최대한 지원 가능한 방안을 안내드리겠습니다.", sections="S7"),
    _e(_EP, "현재 기준으로는 해당 요청을 수용하기 어려운 상황입니다.",
       sections="S7", contexts="REJECTION"),
    _e(_EP, "내부 정책상 해당 기한 이후에는 접수가 어렵습니다.",
       sections="S7", contexts="REJECTION,SCHEDULE_DELAY"),
    _e(_EP, "관련 법령에 따라 아래 범위 내에서만 처리 가능합니다.",
       sections="S7", contexts="CIVIL_COMPLAINT"),
    _e(_EP, "보안 정책상 해당 권한은 별도 승인 절차가 필요합니다.",
       sections="S7", contexts="REQUEST,SUPPORT"),
    _e(_EP, "계약 조건에 명시된 바에 따라 아래와 같이 처리됩니다.",
       sections="S7", contexts="CONTRACT"),

    # --- S8: 마무리 (Closing) ---
    _e(_EP, "검토해 주셔서 감사합니다.", sections="S8"),
    _e(_EP, "항상 신경 써 주셔서 감사드립니다.", sections="S8"),
    _e(_EP, "원활한 협조 부탁드립니다.", sections="S8"),
    _e(_EP, "좋은 하루 되시기 바랍니다.", sections="S8", tone_levels="NEUTRAL,POLITE"),
    _e(_EP, "혹시 어려운 부분이 있으시면 말씀해 주시기 바랍니다.", sections="S5,S8"),
    _e(_EP, "추가로 필요한 사항이 있으시면 편하게 말씀해 주세요.", sections="S5,S8"),
    _e(_EP, "빠른 시일 내 좋은 결과로 안내드리겠습니다.", sections="S8", contexts="COMPLAINT,SUPPORT"),
    _e(_EP, "앞으로도 더 나은 서비스를 위해 노력하겠습니다.", sections="S8", tone_levels="POLITE"),
    _e(_EP, "건강 유의하시고 좋은 결과 있으시길 바랍니다.",
       sections="S8", personas="PARENT,PROFESSOR", tone_levels="VERY_POLITE"),
    _e(_EP, "업무에 참고 부탁드리며, 문의 사항은 언제든 연락 주세요.", sections="S8"),

    # =================================================================
    # cushion (~35) — YELLOW 라벨 완충 표현
    # =================================================================

    # ACCOUNTABILITY (7)
    _e(_CU, "확인해 보니 해당 부분에서 차이가 발생한 것으로 보입니다.",
       yellow_labels="ACCOUNTABILITY,NEGATIVE_FEEDBACK"),
    _e(_CU, "상황을 다시 확인해 본 결과를 말씀드리겠습니다.",
       yellow_labels="ACCOUNTABILITY"),
    _e(_CU, "해당 부분은 추가적인 확인이 필요한 상태입니다.",
       yellow_labels="ACCOUNTABILITY,SELF_JUSTIFICATION"),
    _e(_CU, "관련 경위를 확인한 내용을 공유드립니다.",
       yellow_labels="ACCOUNTABILITY"),
    _e(_CU, "해당 건에 대해 면밀히 살펴본 결과를 말씀드리겠습니다.",
       yellow_labels="ACCOUNTABILITY"),
    _e(_CU, "책임 소재를 명확히 하기 위해 확인한 내용입니다.",
       yellow_labels="ACCOUNTABILITY"),
    _e(_CU, "관련 이력을 다시 점검한 결과를 안내드립니다.",
       yellow_labels="ACCOUNTABILITY"),

    # SELF_JUSTIFICATION (7)
    _e(_CU, "당시 상황을 고려하여 설명드리겠습니다.",
       yellow_labels="SELF_JUSTIFICATION"),
    _e(_CU, "관련 배경을 먼저 말씀드리면 이해에 도움이 될 것 같습니다.",
       yellow_labels="SELF_JUSTIFICATION"),
    _e(_CU, "해당 판단을 하게 된 경위를 설명드리겠습니다.",
       yellow_labels="SELF_JUSTIFICATION"),
    _e(_CU, "당시 여건상 아래와 같은 방향으로 진행하게 되었습니다.",
       yellow_labels="SELF_JUSTIFICATION"),
    _e(_CU, "의사결정 당시의 상황을 함께 공유드립니다.",
       yellow_labels="SELF_JUSTIFICATION"),
    _e(_CU, "당시 가용한 정보를 기반으로 판단한 내용입니다.",
       yellow_labels="SELF_JUSTIFICATION"),
    _e(_CU, "절차에 따라 진행된 경과를 설명드리겠습니다.",
       yellow_labels="SELF_JUSTIFICATION"),

    # NEGATIVE_FEEDBACK (7)
    _e(_CU, "조금 더 면밀히 검토가 필요한 부분이 있었습니다.",
       yellow_labels="NEGATIVE_FEEDBACK"),
    _e(_CU, "이 점에 대해 솔직하게 말씀드리겠습니다.",
       yellow_labels="NEGATIVE_FEEDBACK,EMOTIONAL"),
    _e(_CU, "개선이 필요한 부분에 대해 말씀드리겠습니다.",
       yellow_labels="NEGATIVE_FEEDBACK"),
    _e(_CU, "말씀드리기 조심스러운 부분이 있습니다.",
       yellow_labels="NEGATIVE_FEEDBACK,ACCOUNTABILITY"),
    _e(_CU, "보완이 필요한 사항을 정리하여 안내드립니다.",
       yellow_labels="NEGATIVE_FEEDBACK"),
    _e(_CU, "아쉬운 부분이 있어 함께 공유드립니다.",
       yellow_labels="NEGATIVE_FEEDBACK"),
    _e(_CU, "다소 부담스러운 내용일 수 있으나 공유드립니다.",
       yellow_labels="NEGATIVE_FEEDBACK"),

    # EMOTIONAL (7)
    _e(_CU, "혹시 불편하셨을 수 있는 부분에 대해 말씀드립니다.",
       yellow_labels="EMOTIONAL"),
    _e(_CU, "심려를 끼쳐 드린 점에 대해 말씀드리겠습니다.",
       yellow_labels="EMOTIONAL"),
    _e(_CU, "우려되시는 부분에 대해 안내드리겠습니다.",
       yellow_labels="EMOTIONAL"),
    _e(_CU, "염려하고 계실 부분을 충분히 이해합니다.",
       yellow_labels="EMOTIONAL"),
    _e(_CU, "불안하신 마음을 충분히 이해합니다.",
       yellow_labels="EMOTIONAL"),
    _e(_CU, "답답하셨을 상황에 대해 공감합니다.",
       yellow_labels="EMOTIONAL"),
    _e(_CU, "걱정되시는 점을 해소해 드리고자 말씀드립니다.",
       yellow_labels="EMOTIONAL"),

    # EXCESS_DETAIL (7)
    _e(_CU, "관련하여 부연 설명을 드리겠습니다.",
       yellow_labels="EXCESS_DETAIL"),
    _e(_CU, "조금 길어질 수 있으나 배경을 함께 공유드립니다.",
       yellow_labels="EXCESS_DETAIL"),
    _e(_CU, "이해를 돕기 위해 추가 설명을 드리겠습니다.",
       yellow_labels="EXCESS_DETAIL"),
    _e(_CU, "참고하실 수 있도록 관련 내용을 정리하여 안내드립니다.",
       yellow_labels="EXCESS_DETAIL"),
    _e(_CU, "전체 맥락을 파악하시는 데 도움이 될 내용입니다.",
       yellow_labels="EXCESS_DETAIL"),
    _e(_CU, "세부 사항은 아래에 별도 정리하였습니다.",
       yellow_labels="EXCESS_DETAIL"),
    _e(_CU, "요약하면 아래 핵심 사항으로 정리됩니다.",
       yellow_labels="EXCESS_DETAIL"),

    # =================================================================
    # forbidden (~35) — 금지 표현 + 대체어
    # =================================================================
    _e(_FB, "불편을 끼쳐 드려 진심으로 사과드립니다",
       alternative="죄송합니다", trigger_phrases="진심으로 사과,불편을 끼쳐"),
    _e(_FB, "소중한 의견 감사드립니다",
       alternative="말씀 감사합니다", trigger_phrases="소중한 의견"),
    _e(_FB, "고객님의 소중한 시간",
       alternative="시간 내 주셔서", trigger_phrases="소중한 시간"),
    _e(_FB, "심심한 사과의 말씀을 드립니다",
       alternative="죄송합니다", trigger_phrases="심심한 사과"),
    _e(_FB, "적극 검토하겠습니다",
       alternative="확인 후 안내드리겠습니다", trigger_phrases="적극 검토"),
    _e(_FB, "최선을 다하겠습니다",
       alternative="신속히 처리하겠습니다", trigger_phrases="최선을 다하"),
    _e(_FB, "너그러운 양해 부탁드립니다",
       alternative="양해 부탁드립니다", trigger_phrases="너그러운 양해"),
    _e(_FB, "고객님의 만족을 위해 노력하겠습니다",
       alternative="빠르게 처리하겠습니다", trigger_phrases="만족을 위해"),
    _e(_FB, "항상 고객님의 편에서 생각하겠습니다",
       alternative="확인 후 안내드리겠습니다", trigger_phrases="고객님의 편에서"),
    _e(_FB, "진심 어린 사과의 말씀 드립니다",
       alternative="죄송합니다", trigger_phrases="진심 어린"),
    _e(_FB, "불철주야 노력하겠습니다",
       alternative="신속히 진행하겠습니다", trigger_phrases="불철주야"),
    _e(_FB, "혼신의 힘을 다해 처리하겠습니다",
       alternative="빠르게 처리하겠습니다", trigger_phrases="혼신의 힘"),
    _e(_FB, "무한한 감사를 드립니다",
       alternative="감사합니다", trigger_phrases="무한한 감사"),
    _e(_FB, "깊은 유감을 표합니다",
       alternative="죄송합니다", trigger_phrases="깊은 유감"),
    _e(_FB, "온 마음을 다해 사과드립니다",
       alternative="죄송합니다", trigger_phrases="온 마음을 다해"),
    _e(_FB, "한 치의 소홀함도 없이 처리하겠습니다",
       alternative="꼼꼼히 처리하겠습니다", trigger_phrases="한 치의 소홀"),
    _e(_FB, "두 손 모아 부탁드립니다",
       alternative="부탁드립니다", trigger_phrases="두 손 모아"),
    _e(_FB, "부족하지만 최선을 다해 모시겠습니다",
       alternative="노력하겠습니다", trigger_phrases="부족하지만"),
    _e(_FB, "미력하나마 도움이 되고자 합니다",
       alternative="도움드리겠습니다", trigger_phrases="미력하나마"),
    _e(_FB, "한없이 부끄럽고 죄송합니다",
       alternative="죄송합니다", trigger_phrases="한없이 부끄"),
    _e(_FB, "머리 숙여 사과드립니다",
       alternative="죄송합니다", trigger_phrases="머리 숙여"),
    _e(_FB, "변명의 여지가 없습니다",
       alternative="죄송합니다", trigger_phrases="변명의 여지"),
    _e(_FB, "다시는 이런 일이 없도록 하겠습니다",
       alternative="재발 방지하겠습니다", trigger_phrases="다시는 이런"),
    _e(_FB, "항상 초심을 잃지 않겠습니다",
       alternative="개선하겠습니다", trigger_phrases="초심을 잃지"),
    _e(_FB, "고객님 덕분에 성장하고 있습니다",
       alternative="감사합니다", trigger_phrases="덕분에 성장"),
    _e(_FB, "귀하의 성원에 보답하겠습니다",
       alternative="감사합니다", trigger_phrases="성원에 보답"),
    _e(_FB, "만전을 기하겠습니다",
       alternative="확인 후 처리하겠습니다", trigger_phrases="만전을 기하"),
    _e(_FB, "사력을 다해 처리하겠습니다",
       alternative="빠르게 처리하겠습니다", trigger_phrases="사력을 다해"),
    _e(_FB, "몸 둘 바를 모르겠습니다",
       alternative="죄송합니다", trigger_phrases="몸 둘 바를"),
    _e(_FB, "말씀 하나하나 새겨듣겠습니다",
       alternative="말씀 감사합니다", trigger_phrases="하나하나 새겨"),
    _e(_FB, "언제든 달려가겠습니다",
       alternative="지원하겠습니다", trigger_phrases="달려가겠습니다"),
    _e(_FB, "송구스럽기 그지없습니다",
       alternative="죄송합니다", trigger_phrases="송구스럽기"),
    _e(_FB, "감히 말씀드리기 어려우나",
       alternative="말씀드리겠습니다", trigger_phrases="감히 말씀"),
    _e(_FB, "고객님을 최우선으로 생각하겠습니다",
       alternative="신속히 처리하겠습니다", trigger_phrases="최우선으로 생각"),
    _e(_FB, "성심성의껏 모시겠습니다",
       alternative="지원하겠습니다", trigger_phrases="성심성의껏"),

    # =================================================================
    # policy (~45) — 도메인별 정책 정보
    # =================================================================

    # BILLING (8)
    _e(_PO, "환불 처리는 결제일로부터 7일 이내 신청 건에 한하여 가능합니다.",
       contexts="BILLING,COMPLAINT"),
    _e(_PO, "전자상거래법에 따라 청약 철회는 수령일로부터 7일 이내 가능합니다.",
       contexts="BILLING,REJECTION"),
    _e(_PO, "결제 관련 분쟁은 카드사 또는 PG사를 통해 중재 가능합니다.",
       contexts="BILLING,COMPLAINT"),
    _e(_PO, "할부 결제 취소 시 카드사 처리 기간에 따라 3~5영업일 소요됩니다.",
       contexts="BILLING"),
    _e(_PO, "세금계산서 발행은 결제 완료 후 익월 10일까지 요청 가능합니다.",
       contexts="BILLING,REQUEST"),
    _e(_PO, "미납 요금은 30일 초과 시 서비스 이용이 제한될 수 있습니다.",
       contexts="BILLING"),
    _e(_PO, "중복 결제 확인 시 즉시 환불 처리되며, 실제 환급은 카드사 기준 3~7영업일 소요됩니다.",
       contexts="BILLING,COMPLAINT"),
    _e(_PO, "부분 환불은 사용 일수를 일할 계산하여 잔여 금액 기준으로 처리됩니다.",
       contexts="BILLING,REJECTION"),

    # COMPLAINT (5)
    _e(_PO, "서비스 장애 발생 시 영업일 기준 3일 이내 보상 처리됩니다.",
       contexts="COMPLAINT,SUPPORT"),
    _e(_PO, "고객 불만 접수 시 접수일 기준 24시간 이내 1차 회신이 원칙입니다.",
       contexts="COMPLAINT"),
    _e(_PO, "동일 건에 대한 재접수 시 이전 처리 이력을 함께 검토합니다.",
       contexts="COMPLAINT"),
    _e(_PO, "서비스 품질 관련 보상은 장애 시간 기준으로 산정됩니다.",
       contexts="COMPLAINT,SUPPORT"),
    _e(_PO, "불만 처리 결과에 이의가 있을 경우 상위 부서 검토를 요청할 수 있습니다.",
       contexts="COMPLAINT"),

    # SUPPORT (4)
    _e(_PO, "개인정보 변경은 본인 인증 후 처리 가능합니다.",
       contexts="SUPPORT,REQUEST"),
    _e(_PO, "기술 지원 요청은 접수 순서에 따라 처리되며, 긴급 건은 우선 배정됩니다.",
       contexts="SUPPORT"),
    _e(_PO, "원격 지원은 고객 동의 후 진행되며, 세션 기록은 30일간 보관됩니다.",
       contexts="SUPPORT"),
    _e(_PO, "계정 잠금 해제는 본인 확인 절차 완료 후 즉시 처리됩니다.",
       contexts="SUPPORT,REQUEST"),

    # REQUEST (3)
    _e(_PO, "자료 요청 시 보안 등급에 따라 승인 절차가 상이합니다.",
       contexts="REQUEST"),
    _e(_PO, "대량 데이터 추출 요청은 영업일 기준 3일 이내 처리됩니다.",
       contexts="REQUEST"),
    _e(_PO, "개인정보 포함 자료는 정보보호 책임자 승인 후 제공됩니다.",
       contexts="REQUEST"),

    # SCHEDULE_DELAY (3)
    _e(_PO, "일정 변경은 시작일 기준 3영업일 전까지 요청 가능합니다.",
       contexts="SCHEDULE_DELAY,REQUEST"),
    _e(_PO, "납기 연장은 1회에 한하여 최대 7일까지 승인 가능합니다.",
       contexts="SCHEDULE_DELAY"),
    _e(_PO, "일정 변경으로 인한 추가 비용은 변경 요청자 부담이 원칙입니다.",
       contexts="SCHEDULE_DELAY"),

    # URGING (3)
    _e(_PO, "독촉 사항은 접수 순서에 따라 순차적으로 처리됩니다.",
       contexts="URGING"),
    _e(_PO, "긴급 처리 요청 시 별도 승인 절차를 거쳐 우선 배정됩니다.",
       contexts="URGING"),
    _e(_PO, "처리 현황은 접수 번호로 실시간 조회 가능합니다.",
       contexts="URGING"),

    # REJECTION (3)
    _e(_PO, "요청 거절 시 사유를 서면으로 안내드리고 있습니다.",
       contexts="REJECTION"),
    _e(_PO, "거절된 요청은 보완 후 재신청 가능합니다.",
       contexts="REJECTION"),
    _e(_PO, "거절 사유에 이의가 있을 경우 재심 요청이 가능합니다.",
       contexts="REJECTION"),

    # APOLOGY (3)
    _e(_PO, "공식 사과는 사실 관계 확인 후 담당 부서를 통해 전달됩니다.",
       contexts="APOLOGY"),
    _e(_PO, "사과 서한은 사실 확인 완료 후 영업일 기준 2일 이내 발송됩니다.",
       contexts="APOLOGY"),
    _e(_PO, "재발 방지 대책 수립 후 후속 조치 결과를 별도 안내드립니다.",
       contexts="APOLOGY,COMPLAINT"),

    # ANNOUNCEMENT (3)
    _e(_PO, "공지 사항은 시행일 기준 7일 전에 사전 안내됩니다.",
       contexts="ANNOUNCEMENT"),
    _e(_PO, "서비스 이용 약관 변경 시 30일 전 사전 고지가 원칙입니다.",
       contexts="ANNOUNCEMENT,CONTRACT"),
    _e(_PO, "긴급 공지는 이메일 및 앱 푸시로 즉시 발송됩니다.",
       contexts="ANNOUNCEMENT"),

    # FEEDBACK (3)
    _e(_PO, "피드백 접수 후 검토 결과는 영업일 기준 5일 이내 회신됩니다.",
       contexts="FEEDBACK"),
    _e(_PO, "고객 제안 중 채택된 건에 대해서는 별도 보상이 제공됩니다.",
       contexts="FEEDBACK"),
    _e(_PO, "정기 만족도 조사는 분기별 실시되며 결과는 서비스 개선에 반영됩니다.",
       contexts="FEEDBACK"),

    # CONTRACT (3)
    _e(_PO, "계약 변경 사항은 서면 동의가 필요합니다.",
       contexts="CONTRACT"),
    _e(_PO, "계약 해지 시 위약금은 잔여 기간 기준으로 산정됩니다.",
       contexts="CONTRACT"),
    _e(_PO, "자동 갱신 해지는 만료일 30일 전까지 통보해야 합니다.",
       contexts="CONTRACT"),

    # RECRUITING (3)
    _e(_PO, "채용 과정 중 제출 서류는 반환되지 않습니다.",
       contexts="RECRUITING"),
    _e(_PO, "최종 합격 후 입사 전 건강검진 및 신원조회가 필요합니다.",
       contexts="RECRUITING"),
    _e(_PO, "채용 전형별 결과는 영업일 기준 7일 이내 개별 통보됩니다.",
       contexts="RECRUITING"),

    # CIVIL_COMPLAINT (3)
    _e(_PO, "민원 접수 후 처리 기한은 영업일 기준 5일입니다.",
       contexts="CIVIL_COMPLAINT"),
    _e(_PO, "민원 처리 결과에 이의가 있을 경우 국민권익위원회에 재심을 요청할 수 있습니다.",
       contexts="CIVIL_COMPLAINT"),
    _e(_PO, "정보공개 청구는 접수일로부터 10일 이내 결정 통보됩니다.",
       contexts="CIVIL_COMPLAINT"),

    # GRATITUDE (2)
    _e(_PO, "감사 서한 접수 시 해당 담당자에게 전달 후 기록됩니다.",
       contexts="GRATITUDE"),
    _e(_PO, "우수 서비스 사례는 내부 공유 후 해당 직원에게 포상됩니다.",
       contexts="GRATITUDE"),

    # =================================================================
    # example (~40) — 원문→변환 예시 쌍
    # =================================================================

    # BOSS (10)
    _e(_EX, "앞서 요청드린 건에 대해 진행 상황 확인 부탁드립니다.",
       original_text="이거 왜 아직도 안 됐어요?",
       personas="BOSS,CLIENT", contexts="URGING"),
    _e(_EX, "해당 일정에 맞추기 어려운 상황이 발생하여 조율이 필요합니다.",
       original_text="그 날짜는 절대 못 맞춰요",
       personas="BOSS,CLIENT", contexts="SCHEDULE_DELAY,REJECTION"),
    _e(_EX, "확인 결과 요청하신 내용과 차이가 있어 재확인 부탁드립니다.",
       original_text="이거 아까 말한 거랑 다른데요?",
       personas="BOSS,CLIENT", contexts="COMPLAINT,FEEDBACK"),
    _e(_EX, "팀장님, 해당 업무의 우선순위에 대해 확인드리고 싶습니다.",
       original_text="이거 먼저 해야 하는 거예요 아닌 거예요?",
       personas="BOSS", contexts="REQUEST"),
    _e(_EX, "보고 드린 내용 중 수정이 필요한 부분이 있어 다시 안내드립니다.",
       original_text="아 보고서 잘못 썼어요 다시 보내드릴게요",
       personas="BOSS", contexts="APOLOGY"),
    _e(_EX, "해당 건에 대해 부서 내 공유가 필요하여 안내드립니다.",
       original_text="팀원들한테도 공유 좀 해주세요",
       personas="BOSS", contexts="ANNOUNCEMENT"),
    _e(_EX, "말씀하신 방향으로 수정 후 재검토 부탁드리겠습니다.",
       original_text="이거 고쳐서 다시 확인해 주세요",
       personas="BOSS", contexts="FEEDBACK"),
    _e(_EX, "금일 내 처리가 필요한 건이 있어 우선 확인 부탁드립니다.",
       original_text="오늘까지인데 빨리 좀 봐주세요",
       personas="BOSS", contexts="URGING"),
    _e(_EX, "비용 관련하여 재검토가 필요한 사항이 있습니다.",
       original_text="이 비용이 왜 이렇게 많이 나온 거야?",
       personas="BOSS", contexts="BILLING"),
    _e(_EX, "현재 진행 중인 건에 대해 중간 경과를 공유드립니다.",
       original_text="지금 어디까지 한 거야? 진행 상황 좀 알려줘",
       personas="BOSS", contexts="URGING,REQUEST"),

    # CLIENT (10)
    _e(_EX, "이용에 불편을 드려 죄송합니다. 현재 원인 파악 중이며 빠르게 조치하겠습니다.",
       original_text="서비스가 왜 이래요 도대체 뭐 하는 거예요",
       personas="CLIENT", contexts="COMPLAINT,SUPPORT"),
    _e(_EX, "해당 비용에 대해 정산 내역 확인 부탁드립니다.",
       original_text="이 청구서 금액이 왜 이렇게 나온 거예요?",
       personas="CLIENT,OFFICIAL", contexts="BILLING,COMPLAINT"),
    _e(_EX, "현재 시스템 점검으로 일시적인 불편이 발생하였습니다. 빠른 시일 내 정상화하겠습니다.",
       original_text="서버 또 터졌어요 언제 되는 거예요",
       personas="CLIENT", contexts="SUPPORT"),
    _e(_EX, "환불 규정을 확인하여 안내드리겠습니다.",
       original_text="환불해 주세요 안 되면 소보원에 신고할 거예요",
       personas="CLIENT", contexts="BILLING,COMPLAINT"),
    _e(_EX, "약속드린 납기를 준수하지 못하게 된 점 사과드립니다. 변경된 일정을 안내드립니다.",
       original_text="납품 언제 되는 거예요 자꾸 늦어지잖아요",
       personas="CLIENT", contexts="SCHEDULE_DELAY,URGING"),
    _e(_EX, "현재 진행 상황을 공유드리며, 추가 조치 사항을 안내드립니다.",
       original_text="진행이 어떻게 되고 있는지 알려주세요",
       personas="CLIENT", contexts="REQUEST,URGING"),
    _e(_EX, "계약 조건 변경에 대해 검토 후 회신드리겠습니다.",
       original_text="계약 조건 이거 너무 불리한 거 아닌가요",
       personas="CLIENT", contexts="CONTRACT,COMPLAINT"),
    _e(_EX, "해당 서비스는 정책상 제공이 어려운 점 안내드립니다. 대안으로 아래 방안을 제안드립니다.",
       original_text="이것도 안 된다는 거예요? 다른 데는 다 해주던데",
       personas="CLIENT", contexts="REJECTION"),
    _e(_EX, "개인정보 수정이 완료되었습니다. 변경 사항을 확인해 주시기 바랍니다.",
       original_text="내 정보 수정 좀 해주세요 왜 이렇게 복잡해요",
       personas="CLIENT", contexts="SUPPORT,REQUEST"),
    _e(_EX, "정기 점검 안내를 사전에 공유드리지 못한 점 사과드립니다.",
       original_text="공지도 안 하고 갑자기 점검이요?",
       personas="CLIENT", contexts="ANNOUNCEMENT,COMPLAINT"),

    # PARENT (6)
    _e(_EX, "어머니, 이번 주말에는 일정이 있어 방문이 어려울 것 같습니다. 다음 주에 찾아뵙겠습니다.",
       original_text="엄마 이번 주 못 가 다음 주에 갈게",
       personas="PARENT", contexts="SCHEDULE_DELAY,REJECTION"),
    _e(_EX, "어머니, 말씀해 주신 부분 잘 알겠습니다. 앞으로 더 신경 쓰도록 하겠습니다.",
       original_text="엄마 알겠어 알겠다고 그만 좀 해",
       personas="PARENT", contexts="FEEDBACK"),
    _e(_EX, "어머니, 걱정해 주셔서 감사합니다. 건강은 잘 챙기고 있으니 안심하셔도 됩니다.",
       original_text="엄마 나 괜찮다니까 걱정 좀 그만해",
       personas="PARENT", contexts="SUPPORT"),
    _e(_EX, "아버지, 말씀하신 내용 잘 알겠습니다. 한번 살펴보겠습니다.",
       original_text="아빠 그건 알아서 할게 자꾸 간섭하지 마",
       personas="PARENT", contexts="FEEDBACK,REJECTION"),
    _e(_EX, "어머니, 요즘 일이 많아 연락을 자주 못 드려 죄송합니다.",
       original_text="엄마 요즘 바빠서 전화를 못 했어",
       personas="PARENT", contexts="APOLOGY"),
    _e(_EX, "부모님 덕분에 잘 해낼 수 있었습니다. 항상 감사합니다.",
       original_text="엄마 아빠 고마워 다 엄마 아빠 덕이야",
       personas="PARENT", contexts="GRATITUDE"),

    # PROFESSOR (7)
    _e(_EX, "죄송합니다. 해당 부분은 제가 미처 확인하지 못했습니다.",
       original_text="아 그건 제 실수예요 죄송합니다",
       personas="BOSS,CLIENT,PROFESSOR", contexts="APOLOGY"),
    _e(_EX, "교수님, 과제 제출 기한 연장이 가능한지 여쭙고 싶습니다.",
       original_text="교수님 과제 기한 좀 늘려주세요",
       personas="PROFESSOR", contexts="REQUEST,SCHEDULE_DELAY"),
    _e(_EX, "교수님, 성적 산정 기준에 대해 여쭙고 싶은 부분이 있습니다.",
       original_text="교수님 성적 왜 이렇게 나온 거예요?",
       personas="PROFESSOR", contexts="COMPLAINT,FEEDBACK"),
    _e(_EX, "교수님, 수업 내용 관련하여 질문 사항이 있어 면담을 요청드립니다.",
       original_text="교수님 수업 시간에 이해 안 된 게 있는데요",
       personas="PROFESSOR", contexts="REQUEST"),
    _e(_EX, "교수님, 논문 지도에 대해 감사드립니다. 큰 도움이 되었습니다.",
       original_text="교수님 논문 봐주셔서 감사합니다",
       personas="PROFESSOR", contexts="GRATITUDE"),
    _e(_EX, "교수님, 추천서 작성을 부탁드려도 될지 여쭙습니다.",
       original_text="교수님 추천서 하나만 써주세요",
       personas="PROFESSOR", contexts="REQUEST"),
    _e(_EX, "교수님, 출석 관련하여 사정을 말씀드리고자 합니다.",
       original_text="교수님 그날 못 갔던 건 사정이 있었어요",
       personas="PROFESSOR", contexts="APOLOGY"),

    # OFFICIAL (6)
    _e(_EX, "관련 자료 전달 부탁드립니다. 검토 후 회신드리겠습니다.",
       original_text="자료 좀 보내주세요 확인하고 연락드릴게요",
       personas="BOSS,CLIENT,OFFICIAL", contexts="REQUEST"),
    _e(_EX, "해당 민원에 대해 접수 완료되었으며, 처리 현황은 별도 안내드리겠습니다.",
       original_text="민원 넣었는데 언제 처리되나요",
       personas="OFFICIAL", contexts="CIVIL_COMPLAINT,URGING"),
    _e(_EX, "담당자님, 해당 허가 절차에 대해 문의드립니다.",
       original_text="허가 관련해서 뭐가 필요한지 좀 알려주세요",
       personas="OFFICIAL", contexts="REQUEST"),
    _e(_EX, "민원 처리 결과에 대해 이의 신청을 하고자 합니다.",
       original_text="민원 결과가 이해가 안 되는데 다시 검토해 주세요",
       personas="OFFICIAL", contexts="CIVIL_COMPLAINT,COMPLAINT"),
    _e(_EX, "정보공개 청구 진행 상황을 확인하고자 합니다.",
       original_text="정보공개 요청한 거 언제 나오나요",
       personas="OFFICIAL", contexts="REQUEST,URGING"),
    _e(_EX, "세금 관련 문의 사항이 있어 연락드립니다.",
       original_text="세금 계산이 잘못된 것 같은데요",
       personas="OFFICIAL", contexts="BILLING,COMPLAINT"),

    # OTHER (3)
    _e(_EX, "감사합니다. 덕분에 원활하게 진행할 수 있었습니다.",
       original_text="도와줘서 고마워요",
       personas="BOSS,PROFESSOR", contexts="GRATITUDE"),
    _e(_EX, "아래 내용에 대해 전체 공유드리오니 참고 부탁드립니다.",
       original_text="다들 이거 좀 봐주세요",
       personas="BOSS,CLIENT", contexts="ANNOUNCEMENT"),
    _e(_EX, "계약 조건 관련하여 일부 수정이 필요한 사항이 있어 협의드리고자 합니다.",
       original_text="계약서 이 부분은 좀 바꿔야 할 것 같은데요",
       personas="CLIENT,BOSS", contexts="CONTRACT,FEEDBACK"),

    # =================================================================
    # domain_context (~30) — 도메인별 배경 지식
    # =================================================================

    # IT/시스템 (5)
    _e(_DC, "IT 서비스 장애는 SLA 기준에 따라 대응 등급이 결정됩니다.",
       contexts="COMPLAINT,SUPPORT"),
    _e(_DC, "서버 장애 시 1차 대응은 인프라팀, 2차 대응은 서비스 개발팀이 담당합니다.",
       contexts="SUPPORT,COMPLAINT"),
    _e(_DC, "시스템 접근 권한은 부서장 승인 후 IT 보안팀에서 처리합니다.",
       contexts="REQUEST,SUPPORT"),
    _e(_DC, "서비스 배포는 매주 화/목 정기 배포와 긴급 핫픽스로 구분됩니다.",
       contexts="SUPPORT,ANNOUNCEMENT"),
    _e(_DC, "장애 등급은 P1(전면 중단)~P4(경미)로 분류되며 등급별 대응 시간이 상이합니다.",
       contexts="COMPLAINT,SUPPORT"),

    # 학사/교육 (4)
    _e(_DC, "학사 관련 문의는 학과 사무실과 교무처를 통해 처리됩니다.",
       contexts="REQUEST,FEEDBACK", personas="PROFESSOR"),
    _e(_DC, "학점 이의 신청은 성적 공시일로부터 7일 이내에 가능합니다.",
       contexts="COMPLAINT,FEEDBACK", personas="PROFESSOR"),
    _e(_DC, "휴학 신청은 등록금 납부 기간 내 학과 사무실에서 접수합니다.",
       contexts="REQUEST", personas="PROFESSOR"),
    _e(_DC, "졸업 요건은 전공 필수 학점, 총 이수 학점, 졸업 논문/시험으로 구성됩니다.",
       contexts="REQUEST,FEEDBACK", personas="PROFESSOR"),

    # 공공/행정 (4)
    _e(_DC, "공공기관 민원은 민원24 또는 해당 기관 민원실에서 접수 가능합니다.",
       contexts="CIVIL_COMPLAINT", personas="OFFICIAL"),
    _e(_DC, "고객 개인정보 열람 요청은 정보보호법에 따라 10일 이내 처리해야 합니다.",
       contexts="REQUEST,CIVIL_COMPLAINT", personas="OFFICIAL"),
    _e(_DC, "행정처분에 대한 이의 신청은 처분 통보일로부터 90일 이내 가능합니다.",
       contexts="COMPLAINT,CIVIL_COMPLAINT", personas="OFFICIAL"),
    _e(_DC, "공공조달 입찰 참가 자격은 국가종합전자조달시스템에서 확인 가능합니다.",
       contexts="REQUEST", personas="OFFICIAL"),

    # 인사/채용 (4)
    _e(_DC, "인사 평가는 상반기/하반기 정기 평가와 수시 평가로 구분됩니다.",
       contexts="FEEDBACK", personas="BOSS"),
    _e(_DC, "채용 프로세스는 서류 심사, 실무 면접, 임원 면접, 최종 합격 순입니다.",
       contexts="RECRUITING"),
    _e(_DC, "수습 기간은 입사일로부터 3개월이며, 수습 평가 통과 시 정규직으로 전환됩니다.",
       contexts="RECRUITING,FEEDBACK"),
    _e(_DC, "연차 사용은 최소 1일 전 신청이 원칙이며, 반차도 동일 기준 적용됩니다.",
       contexts="REQUEST,SCHEDULE_DELAY", personas="BOSS"),

    # 계약/법무 (3)
    _e(_DC, "근로계약서 변경 시 양 당사자의 서면 합의가 필수입니다.",
       contexts="CONTRACT"),
    _e(_DC, "견적서와 실제 청구 금액이 다를 경우 계약서 기준으로 정산됩니다.",
       contexts="BILLING,CONTRACT"),
    _e(_DC, "비밀유지계약(NDA) 위반 시 손해배상 청구가 가능합니다.",
       contexts="CONTRACT,COMPLAINT"),

    # 결제/환불 (3)
    _e(_DC, "환불 규정은 전자상거래법에 따라 7일 이내 청약 철회가 가능합니다.",
       contexts="BILLING,COMPLAINT"),
    _e(_DC, "신용카드 결제 취소는 카드사별로 영업일 기준 3~7일 소요됩니다.",
       contexts="BILLING"),
    _e(_DC, "해외 결제 건은 환율 변동에 따라 실제 청구 금액이 달라질 수 있습니다.",
       contexts="BILLING,COMPLAINT"),

    # 일정/보고 (3)
    _e(_DC, "일정 조율 시 참석자 과반수 동의가 있으면 변경 가능합니다.",
       contexts="SCHEDULE_DELAY,REQUEST"),
    _e(_DC, "정기 보고서 제출 기한은 매월 말일 기준 영업일 3일 전입니다.",
       contexts="URGING,REQUEST", personas="BOSS"),
    _e(_DC, "프로젝트 마일스톤 변경 시 PM 승인 후 전체 이해관계자에게 공유됩니다.",
       contexts="SCHEDULE_DELAY,ANNOUNCEMENT", personas="BOSS"),

    # 고객 서비스 (4)
    _e(_DC, "VIP 고객 문의는 전담 매니저를 통해 우선 처리됩니다.",
       contexts="SUPPORT,COMPLAINT", personas="CLIENT"),
    _e(_DC, "제품 보증 기간은 구매일로부터 1년이며, 소모품은 보증 대상에서 제외됩니다.",
       contexts="COMPLAINT,SUPPORT", personas="CLIENT"),
    _e(_DC, "배송 지연 시 주문 상태 확인은 운송장 번호로 택배사 홈페이지에서 가능합니다.",
       contexts="URGING,SUPPORT", personas="CLIENT"),
    _e(_DC, "A/S 접수는 온라인 또는 서비스센터 방문으로 가능하며, 접수 후 영업일 기준 3일 이내 수리됩니다.",
       contexts="SUPPORT,REQUEST", personas="CLIENT"),
]
# fmt: on


async def seed(
    target_category: str | None = None,
    reset: bool = False,
    dry_run: bool = False,
) -> None:
    from app.db import rag_repository
    from app.db.session import async_session_factory
    from app.models.rag import RagCategory, RagEntry, compute_dedupe_key
    from app.services.embedding_service import embed_batch, embedding_to_json

    # Filter data by category if specified
    data = SEED_DATA
    if target_category:
        # Validate category
        try:
            RagCategory(target_category)
        except ValueError:
            valid = [c.value for c in RagCategory]
            logger.error("Invalid category '%s'. Valid: %s", target_category, valid)
            return
        data = [d for d in SEED_DATA if d["category"] == target_category]

    # Count by category
    counts: dict[str, int] = {}
    for d in data:
        counts[d["category"]] = counts.get(d["category"], 0) + 1

    logger.info("Seed data: %d entries across %d categories", len(data), len(counts))
    for cat, cnt in sorted(counts.items()):
        logger.info("  %s: %d", cat, cnt)

    if dry_run:
        logger.info("Dry run — no DB changes")
        return

    async with async_session_factory() as session:
        # Reset if requested
        if reset:
            if target_category:
                deleted = await rag_repository.delete_by_category(session, target_category)
            else:
                deleted = await rag_repository.delete_all(session)
            await session.commit()
            logger.info("Deleted %d existing entries", deleted)

        # Generate embeddings in batch
        texts = [d["content"] for d in data]
        logger.info("Generating embeddings for %d texts...", len(texts))
        embeddings = await embed_batch(texts)
        logger.info("Embeddings generated")

        # Upsert entries
        created_count = 0
        updated_count = 0
        for i, d in enumerate(data):
            dedupe_key = compute_dedupe_key(
                d["category"], d["content"],
                d.get("personas"), d.get("contexts"),
            )

            existing = await rag_repository.find_by_dedupe_key(session, dedupe_key)
            emb_json = embedding_to_json(embeddings[i])

            if existing:
                # Update existing entry
                existing.content = d["content"]
                existing.original_text = d.get("original_text")
                existing.alternative = d.get("alternative")
                existing.trigger_phrases = d.get("trigger_phrases")
                existing.personas = d.get("personas")
                existing.contexts = d.get("contexts")
                existing.tone_levels = d.get("tone_levels")
                existing.sections = d.get("sections")
                existing.yellow_labels = d.get("yellow_labels")
                existing.embedding_blob = emb_json
                existing.enabled = True
                updated_count += 1
            else:
                # Create new entry
                entry = RagEntry(
                    category=d["category"],
                    content=d["content"],
                    original_text=d.get("original_text"),
                    alternative=d.get("alternative"),
                    trigger_phrases=d.get("trigger_phrases"),
                    dedupe_key=dedupe_key,
                    personas=d.get("personas"),
                    contexts=d.get("contexts"),
                    tone_levels=d.get("tone_levels"),
                    sections=d.get("sections"),
                    yellow_labels=d.get("yellow_labels"),
                    embedding_blob=emb_json,
                    enabled=True,
                )
                await rag_repository.save_entry(session, entry)
                created_count += 1

        await session.commit()
        logger.info("Seed complete: %d created, %d updated", created_count, updated_count)

        # Show final counts
        final_counts = await rag_repository.count_by_category(session)
        logger.info("Final DB counts:")
        for cat, cnt in sorted(final_counts.items()):
            logger.info("  %s: %d", cat, cnt)


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed RAG entries")
    parser.add_argument("--category", type=str, help="Seed only this category")
    parser.add_argument("--reset", action="store_true", help="Delete all entries before seeding")
    parser.add_argument("--dry-run", action="store_true", help="Count only, no DB changes")
    args = parser.parse_args()

    asyncio.run(seed(
        target_category=args.category,
        reset=args.reset,
        dry_run=args.dry_run,
    ))


if __name__ == "__main__":
    main()
