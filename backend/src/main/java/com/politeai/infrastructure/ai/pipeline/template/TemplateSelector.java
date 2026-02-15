package com.politeai.infrastructure.ai.pipeline.template;

import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.Purpose;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateSelector {

    private final TemplateRegistry registry;

    public record TemplateSelectionResult(
        StructureTemplate template,
        boolean s2Enforced,
        List<StructureSection> effectiveSections
    ) {}

    // PURPOSE → template ID mapping
    private static final Map<Purpose, String> PURPOSE_TEMPLATE_MAP = Map.ofEntries(
            Map.entry(Purpose.INFO_DELIVERY, "T01_GENERAL"),
            Map.entry(Purpose.DATA_REQUEST, "T02_DATA_REQUEST"),
            Map.entry(Purpose.SCHEDULE_COORDINATION, "T04_SCHEDULE"),
            Map.entry(Purpose.APOLOGY_RECOVERY, "T05_APOLOGY"),
            Map.entry(Purpose.RESPONSIBILITY_SEPARATION, "T09_BLAME_SEPARATION"),
            Map.entry(Purpose.REJECTION_NOTICE, "T06_REJECTION"),
            Map.entry(Purpose.REFUND_REJECTION, "T11_REFUND_REJECTION"),
            Map.entry(Purpose.WARNING_PREVENTION, "T12_WARNING_PREVENTION"),
            Map.entry(Purpose.RELATIONSHIP_RECOVERY, "T10_RELATIONSHIP_RECOVERY"),
            Map.entry(Purpose.NEXT_ACTION_CONFIRM, "T01_GENERAL"),
            Map.entry(Purpose.ANNOUNCEMENT, "T07_ANNOUNCEMENT")
    );

    // CONTEXT → template ID mapping (primary context)
    private static final Map<SituationContext, String> CONTEXT_TEMPLATE_MAP = Map.ofEntries(
            Map.entry(SituationContext.REQUEST, "T02_DATA_REQUEST"),
            Map.entry(SituationContext.SCHEDULE_DELAY, "T04_SCHEDULE"),
            Map.entry(SituationContext.URGING, "T03_NAGGING_REMINDER"),
            Map.entry(SituationContext.REJECTION, "T06_REJECTION"),
            Map.entry(SituationContext.APOLOGY, "T05_APOLOGY"),
            Map.entry(SituationContext.COMPLAINT, "T09_BLAME_SEPARATION"),
            Map.entry(SituationContext.ANNOUNCEMENT, "T07_ANNOUNCEMENT"),
            Map.entry(SituationContext.FEEDBACK, "T08_FEEDBACK"),
            Map.entry(SituationContext.BILLING, "T09_BLAME_SEPARATION"),
            Map.entry(SituationContext.SUPPORT, "T05_APOLOGY"),
            Map.entry(SituationContext.CONTRACT, "T06_REJECTION"),
            Map.entry(SituationContext.RECRUITING, "T01_GENERAL"),
            Map.entry(SituationContext.CIVIL_COMPLAINT, "T09_BLAME_SEPARATION"),
            Map.entry(SituationContext.GRATITUDE, "T10_RELATIONSHIP_RECOVERY")
    );

    // Refund keywords for keyword override
    private static final Pattern REFUND_KEYWORDS = Pattern.compile(
            "환불|취소|반품|결제\\s*취소|카드\\s*취소|refund|cancel"
    );

    public TemplateSelectionResult selectTemplate(
            Persona persona,
            List<SituationContext> contexts,
            Topic topic,
            Purpose purpose,
            LabelStats labelStats,
            String maskedText) {

        String templateId;

        // 1. PURPOSE provided → direct mapping
        if (purpose != null) {
            templateId = PURPOSE_TEMPLATE_MAP.getOrDefault(purpose, "T01_GENERAL");
            log.info("[TemplateSelector] Selected by PURPOSE: {} → {}", purpose, templateId);
        }
        // 2. Primary CONTEXT → mapping
        else if (!contexts.isEmpty()) {
            SituationContext primaryContext = contexts.get(0);
            templateId = CONTEXT_TEMPLATE_MAP.getOrDefault(primaryContext, "T01_GENERAL");
            log.info("[TemplateSelector] Selected by CONTEXT: {} → {}", primaryContext, templateId);
        }
        // 3. Default
        else {
            templateId = "T01_GENERAL";
            log.info("[TemplateSelector] Default template: T01_GENERAL");
        }

        // 4. Topic override: REFUND_CANCEL + rejection-like → T11
        if (topic == Topic.REFUND_CANCEL && isRejectionLike(purpose, contexts)) {
            templateId = "T11_REFUND_REJECTION";
            log.info("[TemplateSelector] Topic override → T11_REFUND_REJECTION");
        }

        // 5. Keyword override: refund keywords + rejection labels
        if (!templateId.equals("T11_REFUND_REJECTION") && maskedText != null
                && REFUND_KEYWORDS.matcher(maskedText).find()
                && (labelStats.hasNegativeFeedback() || isRejectionLike(purpose, contexts))) {
            templateId = "T11_REFUND_REJECTION";
            log.info("[TemplateSelector] Keyword override → T11_REFUND_REJECTION");
        }

        StructureTemplate template = registry.get(templateId);

        // 6. S2 enforcement: ACCOUNTABILITY or NEGATIVE_FEEDBACK → inject S2 if missing
        boolean s2Enforced = false;
        List<StructureSection> sections = new ArrayList<>(template.sectionOrder());
        if ((labelStats.hasAccountability() || labelStats.hasNegativeFeedback())
                && !sections.contains(StructureSection.S2_OUR_EFFORT)) {
            // Insert S2 after S1 (or after S0 if S1 not present)
            int insertIdx = sections.indexOf(StructureSection.S1_ACKNOWLEDGE);
            if (insertIdx < 0) insertIdx = sections.indexOf(StructureSection.S0_GREETING);
            sections.add(insertIdx + 1, StructureSection.S2_OUR_EFFORT);
            s2Enforced = true;
            log.info("[TemplateSelector] S2 enforced for ACCOUNTABILITY/NEGATIVE_FEEDBACK");
        }

        // 7. Apply persona skip rules → produce effectiveSections
        List<StructureSection> effective = applySkipRules(sections, template, persona);

        return new TemplateSelectionResult(template, s2Enforced, effective);
    }

    private boolean isRejectionLike(Purpose purpose, List<SituationContext> contexts) {
        if (purpose == Purpose.REJECTION_NOTICE || purpose == Purpose.REFUND_REJECTION) return true;
        return contexts.contains(SituationContext.REJECTION);
    }

    private List<StructureSection> applySkipRules(List<StructureSection> sections,
                                                    StructureTemplate template,
                                                    Persona persona) {
        if (template.personaSkipRules() == null || !template.personaSkipRules().containsKey(persona)) {
            return sections;
        }

        StructureTemplate.SectionSkipRule rule = template.personaSkipRules().get(persona);
        return sections.stream()
                .filter(s -> !rule.skipSections().contains(s))
                .collect(Collectors.toList());
    }
}
