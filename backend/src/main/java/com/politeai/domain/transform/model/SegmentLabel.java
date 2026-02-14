package com.politeai.domain.transform.model;

public enum SegmentLabel {
    // GREEN (보존) — 메시지의 뼈대, 반드시 포함, 문체만 다듬기
    CORE_FACT, CORE_INTENT, REQUEST, APOLOGY, COURTESY,
    // YELLOW (수정) — 내용 보존, 전달 방식 변경 (라벨별 차등 전략)
    ACCOUNTABILITY, SELF_EXPLAIN, NEGATIVE_FEEDBACK, EMOTIONAL, SPECULATION, OVER_EXPLANATION,
    // RED (삭제) — 내용 자체가 불필요하고 해로움
    AGGRESSION, PERSONAL_ATTACK, PRIVATE_TMI, PURE_GRUMBLE;

    public enum Tier { GREEN, YELLOW, RED }

    public Tier tier() {
        return switch (this) {
            case CORE_FACT, CORE_INTENT, REQUEST, APOLOGY, COURTESY -> Tier.GREEN;
            case ACCOUNTABILITY, SELF_EXPLAIN, NEGATIVE_FEEDBACK, EMOTIONAL, SPECULATION, OVER_EXPLANATION -> Tier.YELLOW;
            case AGGRESSION, PERSONAL_ATTACK, PRIVATE_TMI, PURE_GRUMBLE -> Tier.RED;
        };
    }
}
