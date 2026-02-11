package com.politeai.application.transform;

import com.politeai.application.transform.exception.TierRestrictionException;
import com.politeai.domain.transform.model.Persona;
import com.politeai.domain.transform.model.SituationContext;
import com.politeai.domain.transform.model.ToneLevel;
import com.politeai.domain.transform.model.TransformResult;
import com.politeai.domain.transform.service.TransformService;
import com.politeai.domain.user.model.UserTier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TransformAppService {

    private final TransformService transformService;

    @Value("${tier.free.max-text-length}")
    private int freeMaxTextLength;

    @Value("${tier.paid.max-text-length}")
    private int paidMaxTextLength;

    public TransformResult transform(Persona persona,
                                     List<SituationContext> contexts,
                                     ToneLevel toneLevel,
                                     String originalText,
                                     String userPrompt,
                                     UserTier tier) {
        int maxLength = tier == UserTier.PAID ? paidMaxTextLength : freeMaxTextLength;
        if (originalText.length() > maxLength) {
            throw new TierRestrictionException(
                    String.format("프리티어는 최대 %d자까지 입력할 수 있습니다.", freeMaxTextLength));
        }
        if (tier == UserTier.FREE && userPrompt != null && !userPrompt.isBlank()) {
            throw new TierRestrictionException("추가 요청 프롬프트는 프리미엄 기능입니다.");
        }

        return transformService.transform(persona, contexts, toneLevel, originalText, userPrompt);
    }

    public TransformResult partialRewrite(String selectedText,
                                          String fullContext,
                                          Persona persona,
                                          List<SituationContext> contexts,
                                          ToneLevel toneLevel,
                                          String userPrompt,
                                          UserTier tier) {
        if (tier == UserTier.FREE) {
            throw new TierRestrictionException("부분 재변환은 프리미엄 기능입니다.");
        }

        return transformService.partialRewrite(selectedText, fullContext, persona, contexts, toneLevel, userPrompt);
    }

    public int getMaxTextLength(UserTier tier) {
        return tier == UserTier.PAID ? paidMaxTextLength : freeMaxTextLength;
    }
}
