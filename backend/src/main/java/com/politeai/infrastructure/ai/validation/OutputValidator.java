package com.politeai.infrastructure.ai.validation;

import com.politeai.domain.transform.model.*;
import com.politeai.domain.transform.model.ValidationIssue.Severity;
import com.politeai.infrastructure.ai.pipeline.template.StructureSection;
import com.politeai.infrastructure.ai.pipeline.template.StructureTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rule-based post-processing validator for LLM output.
 * Checks 11 rules and returns validation results.
 */
@Slf4j
@Component
public class OutputValidator {

    // Rule 1: Emoji detection (Unicode emoji ranges + skin tone modifiers + ZWJ sequences)
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\x{1F600}-\\x{1F64F}" +  // Emoticons
            "\\x{1F300}-\\x{1F5FF}" +    // Misc Symbols and Pictographs
            "\\x{1F680}-\\x{1F6FF}" +    // Transport and Map
            "\\x{1F1E0}-\\x{1F1FF}" +    // Flags
            "\\x{2702}-\\x{27B0}" +      // Dingbats
            "\\x{FE00}-\\x{FE0F}" +      // Variation Selectors
            "\\x{1F3FB}-\\x{1F3FF}" +    // Skin tone modifiers
            "\\x{200D}" +                // Zero-Width Joiner (ZWJ emoji sequences)
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
    // These are phrases typically used by the receiver (service provider), not the sender.
    private static final List<String> PERSPECTIVE_PHRASES = List.of(
            "확인해 드리겠습니다",
            "접수되었습니다",
            "처리해 드리겠습니다",
            "안내해 드리겠습니다",
            "도와드리겠습니다",
            "답변드리겠습니다",
            "알려드리겠습니다",
            "연락드리겠습니다",
            "보내드리겠습니다",
            "전달드리겠습니다",
            "안내 드리겠습니다",
            "처리 드리겠습니다"
    );

    // Rule 9: Core number pattern — 3+ digit numbers, with or without commas
    private static final Pattern CORE_NUMBER_PATTERN = Pattern.compile(
            "\\d{1,3}(?:,\\d{3})+|\\d{3,}"
    );

    // Rule 10: Date/time patterns
    private static final List<Pattern> DATE_PATTERNS = List.of(
            Pattern.compile("\\d{4}[./-]\\d{1,2}([./-]\\d{1,2})?"),  // 2026-03-01, 2026.3.1
            Pattern.compile("\\d{1,2}월\\s*\\d{1,2}일"),              // 3월 1일
            Pattern.compile("\\d{1,2}:\\d{2}")                        // 15:30
    );

    // Rule 11: Stopwords (excluded from meaning word extraction)
    private static final Set<String> STOPWORDS = Set.of(
            "은", "는", "이", "가", "을", "를", "에", "의", "와", "과",
            "로", "도", "만", "까지", "부터", "에서", "처럼", "보다",
            "그리고", "하지만", "또한", "그래서", "그런데", "따라서",
            "문제", "확인", "요청", "부분", "경우", "상황", "내용",
            "것", "수", "등", "및", "위해", "대해", "통해"
    );

    private static final Pattern KOREAN_WORD = Pattern.compile("[가-힣]{2,}");

    /**
     * Validate the LLM output against all rules (backward compat — no redaction map, no yellow texts).
     */
    public ValidationResult validate(String finalText,
                                     String originalText,
                                     List<LockedSpan> spans,
                                     String rawLlmOutput,
                                     Persona persona) {
        return validate(finalText, originalText, spans, rawLlmOutput, persona, Collections.emptyMap(), List.of());
    }

    /**
     * Validate the LLM output against all rules (backward compat — no yellow texts).
     */
    public ValidationResult validate(String finalText,
                                     String originalText,
                                     List<LockedSpan> spans,
                                     String rawLlmOutput,
                                     Persona persona,
                                     Map<String, String> redactionMap) {
        return validate(finalText, originalText, spans, rawLlmOutput, persona, redactionMap, List.of());
    }

    /**
     * Validate the LLM output against all 11 rules.
     *
     * @param finalText          the final user-facing output (after unmask)
     * @param originalText       the original input text
     * @param spans              the extracted locked spans
     * @param rawLlmOutput       the Final LLM's raw output (before unmask, with placeholders)
     * @param persona            the target persona
     * @param redactionMap       mapping of [REDACTED:...] markers to original text
     * @param yellowSegmentTexts YELLOW segment original texts for content drop check
     * @return validation result with all issues
     */
    public ValidationResult validate(String finalText,
                                     String originalText,
                                     List<LockedSpan> spans,
                                     String rawLlmOutput,
                                     Persona persona,
                                     Map<String, String> redactionMap,
                                     List<String> yellowSegmentTexts) {
        List<ValidationIssue> issues = new ArrayList<>();

        checkEmoji(finalText, issues);
        checkForbiddenPhrases(finalText, issues);
        checkHallucinatedFacts(finalText, originalText, spans, issues);
        checkEndingRepetition(finalText, issues);
        checkLengthOverexpansion(finalText, originalText, issues);
        checkPerspectiveError(finalText, persona, issues);
        checkLockedSpanMissing(rawLlmOutput, finalText, spans, issues);
        checkRedactedReentry(finalText, rawLlmOutput, redactionMap, issues);
        checkCoreNumberMissing(finalText, originalText, spans, issues);
        checkCoreDateMissing(finalText, originalText, spans, issues);
        checkSoftenContentDropped(finalText, yellowSegmentTexts, issues);
        // S2 check is only called from the template-aware overload

        boolean passed = issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);

        if (!issues.isEmpty()) {
            log.info("Validation completed: {} issues ({} errors, {} warnings)",
                    issues.size(),
                    issues.stream().filter(i -> i.severity() == Severity.ERROR).count(),
                    issues.stream().filter(i -> i.severity() == Severity.WARNING).count());
        }

        return new ValidationResult(passed, issues);
    }

    /**
     * Validate with template-aware S2 presence check.
     */
    public ValidationResult validate(String finalText,
                                     String originalText,
                                     List<LockedSpan> spans,
                                     String rawLlmOutput,
                                     Persona persona,
                                     Map<String, String> redactionMap,
                                     List<String> yellowSegmentTexts,
                                     StructureTemplate template,
                                     List<StructureSection> effectiveSections,
                                     List<LabeledSegment> labeledSegments) {
        // Run all standard validations
        ValidationResult baseResult = validate(finalText, originalText, spans, rawLlmOutput, persona, redactionMap, yellowSegmentTexts);

        // Additional: S2 presence check
        List<ValidationIssue> allIssues = new ArrayList<>(baseResult.issues());
        checkSectionS2Missing(finalText, effectiveSections, labeledSegments, allIssues);

        boolean passed = allIssues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
        return new ValidationResult(passed, allIssues);
    }

    // Rule 12: Section S2 missing — template required S2 but output lacks check/effort statement
    private static final Pattern S2_EFFORT_PATTERN = Pattern.compile(
            "확인|점검|검토|살펴|조사|파악|내부.*결과|담당.*확인|로그.*기준"
    );

    private void checkSectionS2Missing(String finalText,
                                        List<StructureSection> effectiveSections,
                                        List<LabeledSegment> labeledSegments,
                                        List<ValidationIssue> issues) {
        if (effectiveSections == null || labeledSegments == null) return;

        // Only check if template includes S2
        boolean templateHasS2 = effectiveSections.contains(StructureSection.S2_OUR_EFFORT);
        if (!templateHasS2) return;

        // Only check if labels have ACCOUNTABILITY or NEGATIVE_FEEDBACK
        boolean hasRelevantLabels = labeledSegments.stream()
                .anyMatch(ls -> ls.label() == SegmentLabel.ACCOUNTABILITY
                        || ls.label() == SegmentLabel.NEGATIVE_FEEDBACK);
        if (!hasRelevantLabels) return;

        // Check output for effort/check expressions
        if (!S2_EFFORT_PATTERN.matcher(finalText).find()) {
            issues.add(new ValidationIssue(
                    ValidationIssueType.SECTION_S2_MISSING,
                    Severity.WARNING,
                    "S2(내부 확인/점검) 섹션 누락: 템플릿에 포함되어 있으나 출력에 확인/점검 표현 없음",
                    null
            ));
        }
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
    // Allowlist: common number patterns that LLMs legitimately generate
    private static final Pattern SAFE_NUMBER_CONTEXT = Pattern.compile(
            "\\d{2,4}년|제\\d+|\\d+호|\\d+층|\\d+차|\\d+번째"
    );

    // Korean spelled-out large numbers that could be hallucinated
    private static final Pattern KOREAN_NUMBER_PATTERN = Pattern.compile(
            "(?:약\\s*)?(?:\\d+)?(?:십|백|천|만|억|조)\\s*(?:십|백|천|만|억|조)?\\s*(?:원|명|개|건|일|시간|분|배)"
    );

    private void checkHallucinatedFacts(String output, String originalText,
                                        List<LockedSpan> spans, List<ValidationIssue> issues) {
        // Check 1: Numeric patterns (3+ digits)
        Pattern numberPattern = Pattern.compile("\\d{3,}");
        Matcher matcher = numberPattern.matcher(output);

        while (matcher.find()) {
            String found = matcher.group();

            // Skip if the number exists in original text or locked spans
            boolean existsInOriginal = originalText.contains(found);
            boolean existsInSpans = spans != null && spans.stream()
                    .anyMatch(s -> s.originalText().contains(found));
            if (existsInOriginal || existsInSpans) continue;

            // Skip safe contextual patterns (e.g., years like "2024년")
            int contextStart = Math.max(0, matcher.start() - 2);
            int contextEnd = Math.min(output.length(), matcher.end() + 3);
            String context = output.substring(contextStart, contextEnd);
            if (SAFE_NUMBER_CONTEXT.matcher(context).find()) continue;

            issues.add(new ValidationIssue(
                    ValidationIssueType.HALLUCINATED_FACT,
                    Severity.WARNING,
                    "원문에 없는 숫자/날짜 감지: \"" + found + "\"",
                    found
            ));
        }

        // Check 2: Korean spelled-out numbers (백만원, 십만명 etc.)
        Matcher koreanMatcher = KOREAN_NUMBER_PATTERN.matcher(output);
        while (koreanMatcher.find()) {
            String found = koreanMatcher.group();
            if (!originalText.contains(found)) {
                // Check if any substantial substring exists in original
                String core = found.replaceAll("\\s+", "").replaceAll("^약", "");
                if (!originalText.replace(" ", "").contains(core)) {
                    issues.add(new ValidationIssue(
                            ValidationIssueType.HALLUCINATED_FACT,
                            Severity.WARNING,
                            "원문에 없는 한국어 수량 표현 감지: \"" + found + "\"",
                            found
                    ));
                }
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
    private static final int MAX_ABSOLUTE_OUTPUT_LENGTH = 6000;

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

        // Absolute cap regardless of input size
        if (output.length() > MAX_ABSOLUTE_OUTPUT_LENGTH) {
            issues.add(new ValidationIssue(
                    ValidationIssueType.LENGTH_OVEREXPANSION,
                    Severity.WARNING,
                    String.format("출력 길이 절대 상한 초과: %d자 (상한: %d자)",
                            output.length(), MAX_ABSOLUTE_OUTPUT_LENGTH),
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
    private void checkLockedSpanMissing(String rawLlmOutput, String finalText,
                                        List<LockedSpan> spans, List<ValidationIssue> issues) {
        if (spans == null || spans.isEmpty() || rawLlmOutput == null) {
            return;
        }

        for (LockedSpan span : spans) {
            // Check if placeholder exists in the raw LLM output
            if (rawLlmOutput.contains(span.placeholder())) {
                continue;
            }

            // Check flexible placeholder pattern (e.g., {{ DATE-1 }})
            String prefix = span.type().placeholderPrefix();
            Pattern flexible = Pattern.compile(
                    "\\{\\{\\s*" + prefix + "[-_]?" + span.index() + "\\s*\\}\\}"
            );
            if (flexible.matcher(rawLlmOutput).find()) {
                continue;
            }

            // Check if original text appears in raw LLM output
            if (rawLlmOutput.contains(span.originalText())) {
                continue;
            }

            // Final fallback: check if original text exists in final output
            // (covers cases where unmask succeeded via other means)
            if (finalText != null && finalText.contains(span.originalText())) {
                continue;
            }

            issues.add(new ValidationIssue(
                    ValidationIssueType.LOCKED_SPAN_MISSING,
                    Severity.ERROR,
                    "LockedSpan 누락: " + span.placeholder() + " (\"" + span.originalText() + "\")",
                    span.placeholder()
            ));
        }
    }

    // Censorship trace phrases — must not appear in final output
    private static final List<String> CENSORSHIP_TRACES = List.of(
            "[삭제됨]", "[REDACTED", "삭제된 내용", "제거된 부분", "삭제된 부분",
            "일부 내용을 삭제", "부적절한 내용이 제거"
    );

    // Rule 8: Redacted content reentry (JSON format — no markers, only reentry + trace detection)
    private void checkRedactedReentry(String finalText, String rawLlmOutput,
                                       Map<String, String> redactionMap,
                                       List<ValidationIssue> issues) {
        // Check 1: Original redacted text (>=6 chars) should not appear in output
        // Normalized comparison: keep only Korean/English/digits
        if (redactionMap != null && !redactionMap.isEmpty()) {
            String normalizedOutput = normalizeForReentry(finalText);
            for (Map.Entry<String, String> entry : redactionMap.entrySet()) {
                String originalText = entry.getValue();
                if (originalText.length() >= 6) {
                    String normalizedOriginal = normalizeForReentry(originalText);
                    if (normalizedOriginal.length() >= 4 && normalizedOutput.contains(normalizedOriginal)) {
                        issues.add(new ValidationIssue(
                                ValidationIssueType.REDACTED_REENTRY,
                                Severity.ERROR,
                                "제거된 내용 재유입: \"" + originalText.substring(0, Math.min(30, originalText.length())) + "...\"",
                                entry.getKey()
                        ));
                    }
                }
            }
        }

        // Check 2: Censorship trace phrases in output
        for (String trace : CENSORSHIP_TRACES) {
            if (finalText.contains(trace)) {
                issues.add(new ValidationIssue(
                        ValidationIssueType.REDACTION_TRACE,
                        Severity.ERROR,
                        "검열 흔적 문구 감지: \"" + trace + "\"",
                        trace
                ));
            }
        }
    }

    /**
     * Normalize text for reentry comparison: keep only Korean, English, and digits.
     */
    private String normalizeForReentry(String text) {
        return text.replaceAll("[^가-힣a-zA-Z0-9]", "");
    }

    // Rule 9: Core number missing — numbers from originalText that should be preserved
    private void checkCoreNumberMissing(String finalText, String originalText,
                                         List<LockedSpan> spans, List<ValidationIssue> issues) {
        Matcher matcher = CORE_NUMBER_PATTERN.matcher(originalText);

        // Collect numbers from LockedSpan.originalText() (Rule 7 already covers these)
        Set<String> lockedNumbers = collectLockedNumbers(spans, CORE_NUMBER_PATTERN);

        while (matcher.find()) {
            String number = matcher.group();  // "1,200" or "1200"
            // Normalize: remove commas
            String normalized = number.replaceAll(",", "");
            if (lockedNumbers.contains(normalized)) continue;

            // Check if output contains the number as-is or comma-stripped
            if (finalText.contains(number) || finalText.contains(normalized)) continue;
            // Also compare with comma-stripped output
            if (finalText.replaceAll(",", "").contains(normalized)) continue;

            // SAFE_NUMBER_CONTEXT: extended window (-8 ~ +8) for context check
            int ctxStart = Math.max(0, matcher.start() - 8);
            int ctxEnd = Math.min(originalText.length(), matcher.end() + 8);
            if (SAFE_NUMBER_CONTEXT.matcher(originalText.substring(ctxStart, ctxEnd)).find()) continue;

            issues.add(new ValidationIssue(
                    ValidationIssueType.CORE_NUMBER_MISSING, Severity.WARNING,
                    "원문 숫자 누락: \"" + number + "\"", number));
        }
    }

    // Rule 10: Core date/time missing — dates/times from originalText that should be preserved
    private void checkCoreDateMissing(String finalText, String originalText,
                                       List<LockedSpan> spans, List<ValidationIssue> issues) {
        Set<String> lockedTexts = collectLockedTexts(spans);

        for (Pattern pattern : DATE_PATTERNS) {
            Matcher matcher = pattern.matcher(originalText);
            while (matcher.find()) {
                String dateStr = matcher.group();
                if (lockedTexts.stream().anyMatch(s -> s.contains(dateStr))) continue;

                // 1st: exact match in output
                if (finalText.contains(dateStr)) continue;

                // 2nd: separator normalization (. / - unified)
                String normalizedDate = dateStr.replaceAll("[./-]", "-");
                String normalizedOutput = finalText.replaceAll("[./-]", "-");
                if (normalizedOutput.contains(normalizedDate)) continue;

                // 3rd: numeric sequence comparison (handles 0-padding: 03 vs 3)
                List<Integer> dateNums = extractDateNumbers(dateStr);
                Matcher outMatcher = pattern.matcher(finalText);
                boolean numericMatch = false;
                while (outMatcher.find()) {
                    if (extractDateNumbers(outMatcher.group()).equals(dateNums)) {
                        numericMatch = true;
                        break;
                    }
                }
                if (numericMatch) continue;

                issues.add(new ValidationIssue(
                        ValidationIssueType.CORE_DATE_MISSING, Severity.WARNING,
                        "원문 날짜/시간 누락: \"" + dateStr + "\"", dateStr));
            }
        }
    }

    // Rule 11: SOFTEN content dropped — YELLOW segment content completely missing
    private void checkSoftenContentDropped(String finalText, List<String> yellowSegmentTexts,
                                            List<ValidationIssue> issues) {
        if (yellowSegmentTexts == null || yellowSegmentTexts.isEmpty()) return;

        for (String segText : yellowSegmentTexts) {
            if (segText.length() < 15) continue;

            // Extract meaning words (Korean 2+ chars, excluding stopwords)
            List<String> meaningWords = extractMeaningWords(segText);
            if (meaningWords.size() < 2) continue;

            // Pass condition (OR — any one is enough):
            // 1. At least one meaning word (or its stem without Korean particles) exists in output
            boolean hasWordMatch = meaningWords.stream().anyMatch(word ->
                    containsWithParticleVariation(finalText, word));
            if (hasWordMatch) continue;

            // 2. A core number (3+ digits) from the segment exists in output
            boolean hasNumberMatch = Pattern.compile("\\d{3,}").matcher(segText).results()
                    .anyMatch(m -> finalText.contains(m.group()));
            if (hasNumberMatch) continue;

            issues.add(new ValidationIssue(
                    ValidationIssueType.SOFTEN_CONTENT_DROPPED, Severity.WARNING,
                    "SOFTEN 대상 내용 완전 소실: \"" + segText.substring(0, Math.min(30, segText.length())) + "...\"",
                    null));
        }
    }

    // --- Retry hint builders ---

    /**
     * Build a specific retry hint for LOCKED_SPAN_MISSING errors.
     * Lists each missing placeholder with its original text so the LLM knows exactly what to include.
     *
     * @param issues      validation issues from the current run
     * @param lockedSpans the full list of locked spans
     * @return a hint string to append to the retry prompt, or empty string if no LOCKED_SPAN_MISSING errors
     */
    public String buildLockedSpanRetryHint(List<ValidationIssue> issues, List<LockedSpan> lockedSpans) {
        List<ValidationIssue> missingSpanIssues = issues.stream()
                .filter(i -> i.type() == ValidationIssueType.LOCKED_SPAN_MISSING
                        && i.severity() == Severity.ERROR)
                .toList();

        if (missingSpanIssues.isEmpty() || lockedSpans == null || lockedSpans.isEmpty()) {
            return "";
        }

        // Collect the missing placeholders from matchedText (which stores the placeholder string)
        Set<String> missingPlaceholders = missingSpanIssues.stream()
                .map(ValidationIssue::matchedText)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Build detailed hint with placeholder → original text mapping
        StringBuilder hint = new StringBuilder();
        hint.append("\n\n[고정 표현 누락 오류] 다음 고정 표현이 출력에 반드시 포함되어야 합니다:\n");

        for (LockedSpan span : lockedSpans) {
            if (missingPlaceholders.contains(span.placeholder())) {
                hint.append("- ").append(span.placeholder())
                        .append(" → \"").append(span.originalText()).append("\"\n");
            }
        }
        hint.append("위 플레이스홀더를 변환 결과에 반드시 자연스럽게 포함하세요. 절대 누락하지 마세요.");

        return hint.toString();
    }

    // --- Helper methods ---

    private Set<String> collectLockedNumbers(List<LockedSpan> spans, Pattern numberPattern) {
        Set<String> result = new HashSet<>();
        if (spans == null) return result;
        for (LockedSpan span : spans) {
            Matcher m = numberPattern.matcher(span.originalText());
            while (m.find()) result.add(m.group().replaceAll(",", ""));
        }
        return result;
    }

    private Set<String> collectLockedTexts(List<LockedSpan> spans) {
        if (spans == null) return Set.of();
        return spans.stream().map(LockedSpan::originalText).collect(Collectors.toSet());
    }

    private List<Integer> extractDateNumbers(String dateStr) {
        return Pattern.compile("\\d+").matcher(dateStr).results()
                .map(m -> Integer.parseInt(m.group()))
                .toList();
    }

    /**
     * Check if the text contains the word, accounting for Korean particle variations.
     * Korean particles (조사: 에서, 을, 를, etc.) attach directly to nouns,
     * so "디자인팀에서" should match "디자인팀" in the output.
     * Tries progressively shorter prefixes (removing up to 2 trailing chars).
     */
    private boolean containsWithParticleVariation(String text, String word) {
        if (text.contains(word)) return true;
        for (int len = word.length() - 1; len >= Math.max(2, word.length() - 2); len--) {
            if (text.contains(word.substring(0, len))) return true;
        }
        return false;
    }

    private List<String> extractMeaningWords(String text) {
        List<String> words = new ArrayList<>();
        Matcher m = KOREAN_WORD.matcher(text);
        while (m.find()) {
            String word = m.group();
            if (!STOPWORDS.contains(word)) words.add(word);
        }
        return words;
    }
}
