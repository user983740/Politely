package com.politeai.domain.transform.model;

public enum SegmentLabel {
    // GREEN (보존) — 메시지의 뼈대, 반드시 포함, 문체만 다듬기
    CORE_FACT, CORE_INTENT, COURTESY,
    // YELLOW (수정) — 의미는 살리되 객관적/건조한 톤으로 재작성
    EMOTIONAL, OUT_OF_SCOPE, SPECULATION,
    // RED (삭제) — 완전히 도려냄, 서버가 REDACT
    BLAME, SELF_DEFENSE, PRIVATE_TMI, AGGRESSION, GRUMBLE;

    public enum Tier { GREEN, YELLOW, RED }

    public Tier tier() {
        return switch (this) {
            case CORE_FACT, CORE_INTENT, COURTESY -> Tier.GREEN;
            case EMOTIONAL, OUT_OF_SCOPE, SPECULATION -> Tier.YELLOW;
            case BLAME, SELF_DEFENSE, PRIVATE_TMI, AGGRESSION, GRUMBLE -> Tier.RED;
        };
    }
}
