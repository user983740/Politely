package com.politeai.infrastructure.ai.validation;

import com.politeai.domain.transform.model.*;
import com.politeai.domain.transform.model.ValidationIssue.Severity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based post-processing validator for LLM output.
 * Checks 7 rules and returns validation results.
 */
@Slf4j
@Component
public class OutputValidator {

    // Rule 1: Emoji detection (Unicode emoji ranges)
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\x{1F600}-\\x{1F64F}" +  // Emoticons
            "\\x{1F300}-\\x{1F5FF}" +    // Misc Symbols and Pictographs
            "\\x{1F680}-\\x{1F6FF}" +    // Transport and Map
            "\\x{1F1E0}-\\x{1F1FF}" +    // Flags
            "\\x{2702}-\\x{27B0}" +      // Dingbats
            "\\x{FE00}-\\x{FE0F}" +      // Variation Selectors
            "\\x{1F900}-\\x{1F9FF}" +    // Supplemental Symbols
            "\\x{1FA00}-\\x{1FA6F}" +    // Chess Symbols
            "\\x{1FA70}-\\x{1FAFF}" +    // Symbols Extended-A
            "\\x{2600}-\\x{26FF}" +      // Misc symbols
            "\\x{2700}-\\x{27BF}" +      // Dingbats
            "\\x{231A}-\\x{231B}" +      // Watch, Hourglass
            "\\x{23E9}-\\x{23F3}" +      // Media controls
            "\\x{23F8}-\\x{23FA}" +      // Media controls
            "\\x{25AA}-\\x{25AB}" +      // Squares
            "\\x{25B6}\\x{25C0}" +       // Play buttons
            "\\x{25FB}-\\x{25FE}" +      // Squares
            "\\x{2614}-\\x{2615}" +      // Umbrella, Hot Beverage
            "\\x{2648}-\\x{2653}" +      // Zodiac
            "\\x{267F}\\x{2693}" +       // Wheelchair, Anchor
            "\\x{26A1}\\x{26AA}-\\x{26AB}" +
            "\\x{26BD}-\\x{26BE}" +
            "\\x{26C4}-\\x{26C5}" +
            "\\x{26CE}-\\x{26D4}" +
            "\\x{26EA}\\x{26F2}-\\x{26F3}" +
            "\\x{26F5}\\x{26FA}\\x{26FD}" +
            "\\x{2934}-\\x{2935}" +
            "\\x{2B05}-\\x{2B07}" +
            "\\x{2B1B}-\\x{2B1C}" +
            "\\x{2B50}\\x{2B55}" +
            "\\x{3030}\\x{303D}\\x{3297}\\x{3299}]"
    );

    // Rule 2: Forbidden phrases (LLM meta-commentary)
    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "변환 결과",
            "다음과 같이",
            "도움이 되셨으면",
            "변환해 드리겠",
            "아래와 같이",
            "다음은 변환",
            "변환된 텍스트",
            "이렇게 변환",
            "존댓말로 바꾸",
            "다듬어 보았"
    );

    // Rule 4: Korean sentence endings — capture only the suffix (group 1)
    // Longer endings must come before shorter ones in alternation
    private static final Pattern ENDING_PATTERN = Pattern.compile(
            "[가-힣]*?(드리겠습니다|겠습니다|드립니다|할게요|합니다|됩니다|됩니까|십시오|습니다|니다|세요|에요|해요|예요|네요|군요|는데요|거든요|잖아요|지요|죠|요)[.!?]?\\s*$",
            Pattern.MULTILINE
    );

    // Specific problematic pattern
    private static final Pattern DEURIGET_PATTERN = Pattern.compile("드리겠습니다");

    // Rule 6: Perspective error hints (receiver-perspective phrases)
    private static final List<String> PERSPECTIVE_PHRASES = List.of(
            "확인해 드리겠습니다",
            "접수되었습니다",
            "처리해 드리겠습니다",
            "안내해 드리겠습니다",
            "도와드리겠습니다",
            "답변드리겠습니다"
    );

    /**
     * Validate the LLM output against all rules.
     *
     * @param output       the LLM output text (after unmask)
     * @param originalText the original input text
     * @param spans        the extracted locked spans
     * @param maskedOutput the LLM output before unmask (for placeholder check)
     * @param persona      the target persona
     * @return validation result with all issues
     */
    public ValidationResult validate(String output,
                                     String originalText,
                                     List<LockedSpan> spans,
                                     String maskedOutput,
                                     Persona persona) {
        List<ValidationIssue> issues = new ArrayList<>();

        checkEmoji(output, issues);
        checkForbiddenPhrases(output, issues);
        checkHallucinatedFacts(output, originalText, spans, issues);
        checkEndingRepetition(output, issues);
        checkLengthOverexpansion(output, originalText, issues);
        checkPerspectiveError(output, persona, issues);
        checkLockedSpanMissing(maskedOutput, spans, issues);

        boolean passed = issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);

        if (!issues.isEmpty()) {
            log.info("Validation completed: {} issues ({} errors, {} warnings)",
                    issues.size(),
                    issues.stream().filter(i -> i.severity() == Severity.ERROR).count(),
                    issues.stream().filter(i -> i.severity() == Severity.WARNING).count());
        }

        return new ValidationResult(passed, issues);
    }

    // Rule 1: Emoji detection
    private void checkEmoji(String output, List<ValidationIssue> issues) {
        Matcher matcher = EMOJI_PATTERN.matcher(output);
        while (matcher.find()) {
            issues.add(new ValidationIssue(
                    ValidationIssueType.EMOJI,
                    Severity.ERROR,
                    "이모지 감지: \"" + matcher.group() + "\"",
                    matcher.group()
            ));
        }
    }

    // Rule 2: Forbidden phrases
    private void checkForbiddenPhrases(String output, List<ValidationIssue> issues) {
        for (String phrase : FORBIDDEN_PHRASES) {
            if (output.contains(phrase)) {
                issues.add(new ValidationIssue(
                        ValidationIssueType.FORBIDDEN_PHRASE,
                        Severity.ERROR,
                        "금지 구문 감지: \"" + phrase + "\"",
                        phrase
                ));
            }
        }
    }

    // Rule 3: Hallucinated facts (new numbers/dates not in input)
    private void checkHallucinatedFacts(String output, String originalText,
                                        List<LockedSpan> spans, List<ValidationIssue> issues) {
        // Extract number-like patterns from output
        Pattern numberPattern = Pattern.compile("\\d{2,}");
        Matcher matcher = numberPattern.matcher(output);

        while (matcher.find()) {
            String found = matcher.group();
            // Check if this number exists in the original text or locked spans
            boolean existsInOriginal = originalText.contains(found);
            boolean existsInSpans = spans != null && spans.stream()
                    .anyMatch(s -> s.originalText().contains(found));

            if (!existsInOriginal && !existsInSpans) {
                issues.add(new ValidationIssue(
                        ValidationIssueType.HALLUCINATED_FACT,
                        Severity.WARNING,
                        "원문에 없는 숫자/날짜 감지: \"" + found + "\"",
                        found
                ));
            }
        }
    }

    // Rule 4: Ending repetition
    private void checkEndingRepetition(String output, List<ValidationIssue> issues) {
        // Check consecutive same endings
        Matcher matcher = ENDING_PATTERN.matcher(output);
        List<String> endings = new ArrayList<>();
        while (matcher.find()) {
            endings.add(matcher.group(1));
        }

        // Check 3 consecutive same endings
        for (int i = 0; i <= endings.size() - 3; i++) {
            if (endings.get(i).equals(endings.get(i + 1)) && endings.get(i).equals(endings.get(i + 2))) {
                issues.add(new ValidationIssue(
                        ValidationIssueType.ENDING_REPETITION,
                        Severity.WARNING,
                        "동일 종결어미 3회 연속: \"" + endings.get(i) + "\"",
                        endings.get(i)
                ));
                break; // Report once
            }
        }

        // Check "드리겠습니다" frequency
        Matcher deuriget = DEURIGET_PATTERN.matcher(output);
        int count = 0;
        while (deuriget.find()) count++;
        if (count >= 3) {
            issues.add(new ValidationIssue(
                    ValidationIssueType.ENDING_REPETITION,
                    Severity.WARNING,
                    "\"드리겠습니다\" " + count + "회 사용 (3회 이상)",
                    "드리겠습니다"
            ));
        }
    }

    // Rule 5: Length overexpansion
    private void checkLengthOverexpansion(String output, String originalText, List<ValidationIssue> issues) {
        if (originalText.length() >= 20 && output.length() > originalText.length() * 3) {
            issues.add(new ValidationIssue(
                    ValidationIssueType.LENGTH_OVEREXPANSION,
                    Severity.WARNING,
                    String.format("출력 길이 과확장: 입력 %d자 → 출력 %d자 (%.1f배)",
                            originalText.length(), output.length(),
                            (double) output.length() / originalText.length()),
                    null
            ));
        }
    }

    // Rule 6: Perspective error
    private void checkPerspectiveError(String output, Persona persona, List<ValidationIssue> issues) {
        // Only check when persona is NOT CLIENT or OFFICIAL (those are receiver-side personas)
        if (persona == Persona.CLIENT || persona == Persona.OFFICIAL) {
            return;
        }

        for (String phrase : PERSPECTIVE_PHRASES) {
            if (output.contains(phrase)) {
                issues.add(new ValidationIssue(
                        ValidationIssueType.PERSPECTIVE_ERROR,
                        Severity.WARNING,
                        "관점 오류 힌트: \"" + phrase + "\" (받는 사람이 " + persona + "일 때 부적절)",
                        phrase
                ));
            }
        }
    }

    // Rule 7: LockedSpan missing in output
    private void checkLockedSpanMissing(String maskedOutput, List<LockedSpan> spans,
                                        List<ValidationIssue> issues) {
        if (spans == null || spans.isEmpty() || maskedOutput == null) {
            return;
        }

        for (LockedSpan span : spans) {
            // Check if placeholder exists in the masked output
            if (!maskedOutput.contains(span.placeholder())) {
                // Also check flexible pattern
                Pattern flexible = Pattern.compile(
                        "\\{\\{\\s*LOCKED[_\\-]?" + span.index() + "\\s*\\}\\}"
                );
                if (!flexible.matcher(maskedOutput).find()) {
                    // Check if original text appears verbatim
                    if (!maskedOutput.contains(span.originalText())) {
                        issues.add(new ValidationIssue(
                                ValidationIssueType.LOCKED_SPAN_MISSING,
                                Severity.ERROR,
                                "LockedSpan 누락: " + span.placeholder() + " (\"" + span.originalText() + "\")",
                                span.placeholder()
                        ));
                    }
                }
            }
        }
    }
}
