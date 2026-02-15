package com.politeai.infrastructure.ai.pipeline.template;

import com.politeai.domain.transform.model.Persona;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record StructureTemplate(
    String id,
    String name,
    List<StructureSection> sectionOrder,
    String constraints,
    Map<Persona, SectionSkipRule> personaSkipRules
) {

    public record SectionSkipRule(
        Set<StructureSection> skipSections,
        Set<StructureSection> shortenSections,
        Set<StructureSection> expandSections
    ) {
        public static SectionSkipRule empty() {
            return new SectionSkipRule(Set.of(), Set.of(), Set.of());
        }
    }
}
