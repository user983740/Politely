package com.politeai.infrastructure.ai.pipeline.template;

import java.util.List;

public enum StructureSection {
    S0_GREETING("인사",
        "COURTESY 기반 인사. 보내는 사람 정보 있으면 포함",
        List.of(),
        "1문장"),
    S1_ACKNOWLEDGE("공감/유감",
        "상대 상황이나 요청에 대한 공감·유감·감사",
        List.of(
            "말씀해 주신 내용 확인했습니다",
            "불편을 드린 점 유감입니다",
            "해당 상황에 대해 충분히 이해합니다",
            "말씀 주신 부분 관련하여"
        ),
        "1~2문장"),
    S2_OUR_EFFORT("내부 확인/점검",
        "우리 측 확인·점검·조치 노력. 비난 완화 장치",
        List.of(
            "내부 확인 결과",
            "로그 기준으로 보면",
            "설정값을 기준으로 점검해 보니",
            "담당 부서와 확인한 바로는"
        ),
        "1문장 가능"),
    S3_FACTS("핵심 사실",
        "CORE_FACT + ACCOUNTABILITY(재작성). 수치/날짜/원인 정확 보존",
        List.of(),
        "1~3문장"),
    S4_RESPONSIBILITY("책임 프레이밍",
        "귀책 방향 설정. 주어를 상황/시스템/프로세스로 전환",
        List.of(
            "확인 결과 ~부분에서 차이가 발생하여",
            "해당 프로세스상 ~이(가) 원인으로 파악됩니다",
            "시스템 점검 과정에서 ~이(가) 확인되었습니다"
        ),
        "1~2문장"),
    S5_REQUEST("요청/행동 요청",
        "REQUEST + NEGATIVE_FEEDBACK(재작성). 기한/조건 보존",
        List.of(),
        "1~2문장"),
    S6_OPTIONS("대안/다음 단계",
        "CORE_INTENT + 대안 제시. 구체적 해결 방향",
        List.of(),
        "1~3문장"),
    S7_POLICY("정책/한계/불가",
        "거절 근거. 정책/규정 기반 완곡 설명. 감정 배제",
        List.of(
            "현행 정책에 따르면",
            "운영 기준상",
            "관련 규정에 의거하여"
        ),
        "1~2문장"),
    S8_CLOSING("마무리",
        "감사 + 해결 의지 또는 서명. 짧게.",
        List.of(),
        "1문장");

    private final String label;
    private final String instruction;
    private final List<String> expressionPool;
    private final String lengthHint;

    StructureSection(String label, String instruction, List<String> expressionPool, String lengthHint) {
        this.label = label;
        this.instruction = instruction;
        this.expressionPool = expressionPool;
        this.lengthHint = lengthHint;
    }

    public String getLabel() { return label; }
    public String getInstruction() { return instruction; }
    public List<String> getExpressionPool() { return expressionPool; }
    public String getLengthHint() { return lengthHint; }
}
