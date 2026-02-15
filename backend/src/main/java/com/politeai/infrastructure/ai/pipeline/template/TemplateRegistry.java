package com.politeai.infrastructure.ai.pipeline.template;

import com.politeai.domain.transform.model.Persona;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.politeai.infrastructure.ai.pipeline.template.StructureSection.*;

@Component
public class TemplateRegistry {

    private static final Map<Persona, StructureTemplate.SectionSkipRule> BOSS_PROF_OFFICIAL_RULES = Map.of(
            Persona.BOSS, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of()),
            Persona.PROFESSOR, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of()),
            Persona.OFFICIAL, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of())
    );

    private static final Map<Persona, StructureTemplate.SectionSkipRule> CLIENT_EXPAND_S1_S2 = Map.of(
            Persona.CLIENT, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(), Set.of(S1_ACKNOWLEDGE, S2_OUR_EFFORT)),
            Persona.BOSS, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of()),
            Persona.PROFESSOR, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of()),
            Persona.OFFICIAL, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of())
    );

    private static final Map<Persona, StructureTemplate.SectionSkipRule> PARENT_EXPAND_S1 = Map.of(
            Persona.PARENT, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(), Set.of(S1_ACKNOWLEDGE)),
            Persona.BOSS, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of()),
            Persona.PROFESSOR, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of()),
            Persona.OFFICIAL, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of())
    );

    private final Map<String, StructureTemplate> templates = new LinkedHashMap<>();

    public TemplateRegistry() {
        register(new StructureTemplate("T01_GENERAL", "일반 전달",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S3_FACTS, S5_REQUEST, S6_OPTIONS, S8_CLOSING),
                "범용 템플릿. 특정 패턴 없이 사실 전달 + 요청 + 대안 구조.",
                BOSS_PROF_OFFICIAL_RULES));

        register(new StructureTemplate("T02_DATA_REQUEST", "자료 요청",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S3_FACTS, S5_REQUEST, S8_CLOSING),
                "요청 사유를 먼저 밝히고, 구체적 자료/기한/형식을 명시. 부담을 줄이는 완곡 표현.",
                BOSS_PROF_OFFICIAL_RULES));

        register(new StructureTemplate("T03_NAGGING_REMINDER", "독촉/리마인더",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S3_FACTS, S5_REQUEST, S8_CLOSING),
                "이전 요청 상기 + 회신 기한. 비난 없이 사실 기반 리마인드. S1은 짧게.",
                Map.of(
                    Persona.BOSS, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of()),
                    Persona.PROFESSOR, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of()),
                    Persona.OFFICIAL, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of()),
                    Persona.CLIENT, new StructureTemplate.SectionSkipRule(Set.of(), Set.of(S1_ACKNOWLEDGE), Set.of())
                )));

        register(new StructureTemplate("T04_SCHEDULE", "일정 조율/지연",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S3_FACTS, S4_RESPONSIBILITY, S6_OPTIONS, S8_CLOSING),
                "사과 → 지연 원인(사실) → 새 일정 제안. 변명 최소화, 대안 집중.",
                PARENT_EXPAND_S1));

        register(new StructureTemplate("T05_APOLOGY", "사과/수습",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S2_OUR_EFFORT, S3_FACTS, S6_OPTIONS, S8_CLOSING),
                "진심 사과 → 내부 확인 노력 → 원인 → 해결/재발 방지. S2 필수.",
                CLIENT_EXPAND_S1_S2));

        register(new StructureTemplate("T06_REJECTION", "거절/불가 안내",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S7_POLICY, S3_FACTS, S6_OPTIONS, S8_CLOSING),
                "공감 → 정책/규정 근거 → 대안 제시. 감정 배제, 거절 이유 명확.",
                CLIENT_EXPAND_S1_S2));

        register(new StructureTemplate("T07_ANNOUNCEMENT", "공지/안내",
                List.of(S0_GREETING, S3_FACTS, S5_REQUEST, S8_CLOSING),
                "두괄식. 핵심 정보(일시/장소/대상) 먼저. 행동 요청으로 마무리. S1 생략.",
                Map.of()));

        register(new StructureTemplate("T08_FEEDBACK", "피드백",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S3_FACTS, S5_REQUEST, S6_OPTIONS, S8_CLOSING),
                "긍정 인정 → 개선점(요청 형태) → 기대 효과. 비판 아닌 성장 지향.",
                PARENT_EXPAND_S1));

        register(new StructureTemplate("T09_BLAME_SEPARATION", "책임 분리",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S2_OUR_EFFORT, S3_FACTS, S4_RESPONSIBILITY, S6_OPTIONS, S8_CLOSING),
                "공감 → 내부 확인 → 사실 나열 → 귀책 방향(주어 전환) → 해결안. 비난 제거 필수.",
                CLIENT_EXPAND_S1_S2));

        register(new StructureTemplate("T10_RELATIONSHIP_RECOVERY", "관계 회복",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S3_FACTS, S6_OPTIONS, S8_CLOSING),
                "깊은 공감·사과 → 상황 인정 → 협력 제안. 감정 간접 전환 중시.",
                PARENT_EXPAND_S1));

        register(new StructureTemplate("T11_REFUND_REJECTION", "환불 거절",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S2_OUR_EFFORT, S3_FACTS, S7_POLICY, S6_OPTIONS, S8_CLOSING),
                "공감 → 내부 점검 → 사실 → 정책 근거 → 대안. S2 필수(점검 노력 표시).",
                CLIENT_EXPAND_S1_S2));

        register(new StructureTemplate("T12_WARNING_PREVENTION", "경고/재발 방지",
                List.of(S0_GREETING, S1_ACKNOWLEDGE, S3_FACTS, S5_REQUEST, S6_OPTIONS, S8_CLOSING),
                "문제 인정 → 사실/경과 → 구체적 요청(재발 방지) → 기대 효과.",
                BOSS_PROF_OFFICIAL_RULES));
    }

    private void register(StructureTemplate template) {
        templates.put(template.id(), template);
    }

    public StructureTemplate get(String templateId) {
        return templates.getOrDefault(templateId, templates.get("T01_GENERAL"));
    }

    public StructureTemplate getDefault() {
        return templates.get("T01_GENERAL");
    }

    public Collection<StructureTemplate> all() {
        return Collections.unmodifiableCollection(templates.values());
    }
}
