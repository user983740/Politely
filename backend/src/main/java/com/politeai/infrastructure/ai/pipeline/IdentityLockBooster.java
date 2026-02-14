package com.politeai.infrastructure.ai.pipeline;

import com.politeai.domain.transform.model.LockedSpan;
import com.politeai.domain.transform.model.LockedSpanType;
import com.politeai.domain.transform.model.Persona;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import com.politeai.infrastructure.ai.PromptBuilder;
import com.politeai.infrastructure.ai.preprocessing.LockedSpanMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional LLM gating component: extracts semantic locked spans (proper nouns, filenames, etc.)
 * that regex patterns cannot catch. Reuses the Model 2 prompt pattern.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityLockBooster {

    private final AiTransformService aiTransformService;
    private final PromptBuilder promptBuilder;
    private final LockedSpanMasker spanMasker;

    private static final String MODEL = "gpt-4o-mini";
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 300;

    public record BoosterResult(
            String remaskedText,
            List<LockedSpan> allSpans,
            long promptTokens,
            long completionTokens
    ) {}

    private static final String SYSTEM_PROMPT = """
            당신은 텍스트에서 변경 불가능한 고유 표현을 추출하는 전문가입니다.
            정규식으로 잡을 수 없는, 대체하면 의미가 달라지는 고유 식별자만 찾습니다.

            이미 마스킹된 {{LOCKED_N}} 플레이스홀더는 무시하세요.
            날짜, 시간, 전화번호, 이메일, URL, 금액 등은 이미 처리되었으므로 제외하세요.

            ## 추출 대상 (고유 식별자만)
            - 사람/회사/기관의 고유 이름 (예: 김민수, ㈜한빛소프트)
            - 프로젝트/제품/서비스 고유 명칭 (예: Project Alpha, 스터디플랜 v2)
            - 파일명, 코드명, 시스템명 (예: report_final.xlsx, ERP)

            ## 제외 대상 (절대 추출 금지)
            - 일반 명사, 보통 명사, 일상 어휘
            - 관계/역할 호칭 (학부모, 담임, 교수, 팀장, 고객, 선생님 등)
            - 메타데이터에 이미 명시된 정보 (받는 사람, 상황 등)
            - 누구나 쓸 수 있는 범용 단어

            기준: "이 단어를 다른 말로 바꾸면 지칭 대상이 달라지는가?" → Yes만 추출.

            변경 불가 표현을 한 줄에 하나씩, "- " 접두사로 작성하세요.
            예: - 김민수
            변경 불가 표현이 없으면 "없음"이라고만 작성하세요.""";

    public BoosterResult boost(Persona persona, String normalizedText,
                                List<LockedSpan> currentSpans, String maskedText) {
        String userMessage = "받는 사람: " + promptBuilder.getPersonaLabel(persona) + "\n\n원문:\n" + maskedText;

        LlmCallResult result = aiTransformService.callOpenAIWithModel(
                MODEL, SYSTEM_PROMPT, userMessage, TEMPERATURE, MAX_TOKENS, null);

        List<LockedSpan> newSpans = parseSemanticSpans(normalizedText, currentSpans, result.content());

        if (newSpans.isEmpty()) {
            return new BoosterResult(maskedText, currentSpans, result.promptTokens(), result.completionTokens());
        }

        // Combine and re-index
        List<LockedSpan> allSpans = new ArrayList<>(currentSpans);
        allSpans.addAll(newSpans);
        allSpans.sort(Comparator.comparingInt(LockedSpan::startPos));

        List<LockedSpan> reindexed = new ArrayList<>();
        for (int i = 0; i < allSpans.size(); i++) {
            LockedSpan s = allSpans.get(i);
            reindexed.add(new LockedSpan(i, s.originalText(), "{{LOCKED_" + i + "}}", s.type(), s.startPos(), s.endPos()));
        }

        String remasked = spanMasker.mask(normalizedText, reindexed);
        log.info("[IdentityBooster] Added {} semantic spans (total: {})", newSpans.size(), reindexed.size());

        return new BoosterResult(remasked, reindexed, result.promptTokens(), result.completionTokens());
    }

    private List<LockedSpan> parseSemanticSpans(String normalizedText, List<LockedSpan> existingSpans, String output) {
        List<LockedSpan> result = new ArrayList<>();
        if (output == null || output.isBlank() || output.trim().equals("없음")) {
            return result;
        }

        // Collect all existing spans (including newly added ones) for overlap checking
        List<LockedSpan> allKnownSpans = new ArrayList<>(existingSpans);

        int nextIndex = existingSpans.size();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (!line.startsWith("- ")) continue;

            String text = line.substring(2).trim();
            if (text.isBlank() || text.length() < 2) continue;

            // Use word-boundary-aware search to avoid partial matches
            // For Korean: check that char before/after is not a Korean syllable
            // For ASCII: use standard word boundary
            Pattern spanPattern = buildWordBoundaryPattern(text);
            Matcher m = spanPattern.matcher(normalizedText);

            while (m.find()) {
                int pos = m.start();
                int endPos = m.end();

                // Check overlap with existing spans and already-added spans
                final int fPos = pos;
                final int fEndPos = endPos;
                boolean overlaps = allKnownSpans.stream()
                        .anyMatch(s -> fPos < s.endPos() && fEndPos > s.startPos());
                if (overlaps) continue;

                LockedSpan newSpan = new LockedSpan(
                        nextIndex++, text,
                        "{{LOCKED_" + (nextIndex - 1) + "}}",
                        LockedSpanType.SEMANTIC, pos, endPos
                );
                result.add(newSpan);
                allKnownSpans.add(newSpan);
            }
        }

        return result;
    }

    /**
     * Build a word-boundary-aware pattern for the given text.
     * For Korean text: ensures the match is not part of a longer Korean word.
     * For ASCII text: uses standard \b word boundaries.
     */
    private Pattern buildWordBoundaryPattern(String text) {
        String quoted = Pattern.quote(text);
        boolean startsWithKorean = isKoreanChar(text.charAt(0));
        boolean endsWithKorean = isKoreanChar(text.charAt(text.length() - 1));

        String prefix = startsWithKorean ? "(?<![가-힣ㄱ-ㅎㅏ-ㅣ])" : "\\b";
        String suffix = endsWithKorean ? "(?![가-힣ㄱ-ㅎㅏ-ㅣ])" : "\\b";

        return Pattern.compile(prefix + quoted + suffix);
    }

    private boolean isKoreanChar(char c) {
        return (c >= 0xAC00 && c <= 0xD7A3) || // 한글 음절
               (c >= 0x3131 && c <= 0x314E) || // 자음
               (c >= 0x314F && c <= 0x3163);   // 모음
    }
}
