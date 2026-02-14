package com.politeai.infrastructure.ai.segmentation;

import com.politeai.domain.transform.model.Segment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 정밀도 우선 7단계 계층적 텍스트 분절기. LLM 호출 없음.
 *
 * 파이프라인:
 *   1. 강한 구조적 경계 (신뢰도: 1.0)
 *      — 빈줄, 명시적 구분자(---/===/___), 불릿, 번호 목록
 *   2. 한국어 종결어미 (신뢰도: 0.95)
 *      — ~120개 패턴 + 연결어미 분절 억제
 *   3. 약한 구두점 경계 (신뢰도: 0.9)
 *      — .!?;…—– 뒤에 공백 또는 줄끝
 *   4. 길이 기반 안전 분절 (신뢰도: 0.85)
 *      — 250자 초과 세그먼트를 가장 가까운 약한 경계에서 분절, 조사 회피
 *   5. 열거 감지 (신뢰도: 0.9)
 *      — 쉼표 리스트, 구분자 리스트, 병렬 ~고 구조 (120자 초과만)
 *   6. 담화표지어 분절 (신뢰도: 0.88)
 *      — ~39개 표지어, 문장 시작 위치만, 150자 초과만, 복합어 배제
 *   7. 과잉 분절 병합
 *      — 3개 이상 연속 5자 미만 세그먼트 병합, 플레이스홀더 경계 보호
 *
 * SplitUnit을 통한 위치 추적 (indexOf 미사용). 신뢰도 점수는 향후 활용 예정.
 * 긴 세그먼트(200자 초과)는 파이프라인에서 LlmSegmentRefiner가 추가 정제.
 */
@Slf4j
@Component
public class MeaningSegmenter {

    // ── Internal records ──

    private record SplitUnit(String text, int start, int end, double confidence) {}

    private record ProtectedRange(int start, int end, ProtectionType type) {}

    private enum ProtectionType { PLACEHOLDER, PARENTHETICAL, QUOTED }

    // ── Configurable thresholds ──

    @Value("${segmenter.max-segment-length:250}")
    private int maxSegmentLength;

    @Value("${segmenter.discourse-marker-min-length:150}")
    private int discourseMarkerMinLength;

    @Value("${segmenter.enumeration-min-length:120}")
    private int enumerationMinLength;

    private static final int MIN_SEGMENT_LENGTH = 5;
    private static final int MIN_SHORT_CONSECUTIVE = 3;

    // ── Patterns ──

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{[A-Z]+_\\d+\\}\\}");

    // Stage 1: Strong boundaries (4 sub-patterns)
    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\n+");
    private static final Pattern EXPLICIT_SEPARATOR = Pattern.compile("(?:^|\\n)[-=_]{3,}\\s*(?:\\n|$)", Pattern.MULTILINE);
    private static final Pattern BULLET = Pattern.compile("(?<=\\n)(?:[-*\\u2022]\\s)");
    private static final Pattern NUMBERED_LIST = Pattern.compile(
            "(?<=\\n)(?:\\d{1,3}[.)][\\s]|[\\u2460-\\u2473]\\s?)");

    // Stage 2: Korean sentence endings (sub-patterns)
    private static final Pattern ENDING_FORMAL = Pattern.compile(
            "(?<=겠습니다|하십시오|겠습니까|" +
                    "습니다|입니다|됩니다|합니다|답니다|랍니다|십니다|" +
                    "습니까|입니까|됩니까|합니까|십니까|십시오" +
            ")(?:\\s+|[.!?\\u2026~;]\\s*)"
    );
    private static final Pattern ENDING_POLITE = Pattern.compile(
            "(?<=는데요|거든요|잖아요|니까요|라서요|던가요|텐데요|다고요|라고요|냐고요|자고요|은데요|던데요|" +
                    "세요|에요|해요|예요|네요|군요|지요|어요|아요|게요|래요|나요|가요|고요|서요|걸요|대요|까요|셔요|구요" +
            ")(?:\\s+|[.!?\\u2026~;]\\s*)"
    );
    private static final Pattern ENDING_CASUAL = Pattern.compile(
            "(?<=[았었했됐갔왔봤줬났겠셨]어|같어|않아|없어|있어|못해|" +
                    "[았었했됐겠셨]지|" +
                    "거든|잖아|는데|인데|한데|은데|던데|텐데|더라|니까|" +
                    "할래|할게|갈게|볼게|줄게|을래|을게|을걸|" +
                    "하자|해라|해봐|구나|구먼|이야|거야|건데|" +
                    "다며|다더라|그치|시죠|던가" +
            ")(?:\\s+|[.!?\\u2026~;]\\s*)"
    );
    private static final Pattern ENDING_NARRATIVE = Pattern.compile(
            "(?<=하게|하네|하세|" +
                    "[했됐봤왔갔줬났]음|같음|있음|없음|아님|맞음|모름|드림|올림|알림|바람|나름|받음|보냄|" +
                    "[했됐봤왔갔줬났겠]다|있다|없다|같다|한다|된다|간다|온다|는다|" +
                    "됨|임|함|" +
                    "죠|ㅋㅋ|ㅎㅎ|ㅠㅠ|ㅜㅜ" +
            ")(?:\\s+|[.!?\\u2026~;]\\s*)"
    );
    private static final List<Pattern> KOREAN_ENDING_PATTERNS = List.of(
            ENDING_FORMAL, ENDING_POLITE, ENDING_CASUAL, ENDING_NARRATIVE
    );

    // Ambiguous endings that can be connective (suppress split when mid-sentence)
    private static final Set<String> AMBIGUOUS_ENDINGS = Set.of(
            "는데", "인데", "한데", "은데", "던데", "텐데", "니까", "거든", "고", "건데"
    );

    // Discourse markers — used to detect sentence boundaries after ambiguous endings
    private static final Set<String> DISCOURSE_MARKERS_SET = Set.of(
            "그리고", "또한", "게다가", "더구나", "심지어",
            "그런데", "근데", "하지만", "그러나", "그래도", "반면", "한편", "오히려", "그렇지만",
            "그래서", "그러므로", "결국", "그러니까", "그러니", "결과적으로",
            "그러면", "그럼", "그렇다면", "만약", "만일", "아니면",
            "아무튼", "어쨌든", "어쨌거나", "그나저나", "암튼",
            "마지막으로", "끝으로", "첫째", "둘째", "셋째",
            "결론적으로", "왜냐하면", "왜냐면"
    );

    // Stage 3: Weak punctuation
    private static final Pattern WEAK_BOUNDARY = Pattern.compile(
            "(?<=[.!?;])\\s+|(?<=[.!?;])$|(?<=\\u2026)\\s*|(?<=\\.{3})\\s*|(?<=[\u2014\u2013])\\s*",
            Pattern.MULTILINE
    );

    // Stage 4: Korean postpositions (avoid splitting right after these)
    private static final Set<String> POSTPOSITIONS = Set.of(
            "은", "는", "이", "가", "을", "를", "에", "의", "와", "과",
            "로", "도", "만", "까지", "부터", "에서", "처럼", "보다",
            "마다", "밖에", "조차", "든지", "이나", "에게", "한테", "께"
    );

    // Stage 5: Enumeration patterns
    private static final Pattern COMMA_LIST = Pattern.compile(",\\s*");
    private static final Pattern DELIMITER_LIST = Pattern.compile("[/\\u00B7|]\\s*");
    private static final Pattern PARALLEL_GO = Pattern.compile(
            "(?<=[가-힣])고\\s+(?=[가-힣])");

    // Stage 6: Discourse markers (sentence-start only)
    private static final String DISCOURSE_MARKER_ALTERNATIVES =
            "그리고|또한|게다가|더구나|심지어|" +
            "그런데|근데|하지만|그러나|그래도|반면|한편|오히려|그렇지만|" +
            "그래서|그러므로|결국|그러니까|그러니|결과적으로|" +
            "그러면|그럼|그렇다면|만약|만일|아니면|" +
            "아무튼|어쨌든|어쨌거나|그나저나|암튼|" +
            "마지막으로|끝으로|첫째|둘째|셋째|" +
            "결론적으로|왜냐하면|왜냐면";

    private static final Pattern DISCOURSE_MARKER_SPLIT = Pattern.compile(
            "(?<=(?:[.!?;\\u2026]\\s)|(?:\\n))(?=" +
                    "(?:" + DISCOURSE_MARKER_ALTERNATIVES + ")\\s)"
    );

    // Compound suffixes that should NOT be split even when they start with a marker word
    private static final Set<String> COMPOUND_SUFFIXES = Set.of(
            "그런데도", "그래서인지", "그러나마나", "하지만서도", "그래도역시"
    );

    // Parenthetical and quoted ranges
    private static final Pattern PAREN_PATTERN = Pattern.compile("\\([^)]*\\)");
    private static final Pattern QUOTE_PATTERN = Pattern.compile(
            "\"[^\"]*\"|'[^']*'|\u201C[^\u201D]*\u201D|\u2018[^\u2019]*\u2019"
    );

    // ── Public API ──

    public List<Segment> segment(String maskedText) {
        if (maskedText == null || maskedText.isBlank()) {
            return List.of();
        }

        List<ProtectedRange> protectedRanges = collectProtectedRanges(maskedText);

        // Start with a single SplitUnit spanning the entire text
        List<SplitUnit> units = List.of(new SplitUnit(maskedText, 0, maskedText.length(), 1.0));

        // Stage 1: Strong structural boundaries
        units = splitStrongBoundaries(units, maskedText, protectedRanges);

        // Stage 2: Korean sentence endings (with connective suppression)
        units = splitKoreanEndings(units, maskedText, protectedRanges);

        // Stage 3: Weak punctuation boundaries
        units = splitWeakBoundaries(units, maskedText, protectedRanges);

        // Stage 4: Length-based safety split
        units = forceSplitLong(units, maskedText, protectedRanges);

        // Stage 5: Enumeration detection
        units = splitEnumerations(units, maskedText, protectedRanges);

        // Stage 6: Discourse markers (length-restricted)
        units = splitDiscourseMarkers(units, maskedText, protectedRanges);

        // Stage 7: Merge over-segmented runs
        units = mergeShortUnits(units, maskedText);

        // Convert to Segment list
        List<Segment> segments = toSegments(units);

        double avgConf = units.stream().mapToDouble(SplitUnit::confidence).average().orElse(1.0);
        double minConf = units.stream().mapToDouble(SplitUnit::confidence).min().orElse(1.0);
        log.info("[Segmenter] {} segments from {} chars — avg confidence={}, min={}",
                segments.size(), maskedText.length(),
                String.format("%.2f", avgConf), String.format("%.2f", minConf));

        return segments;
    }

    // ── Protected range collection ──

    private List<ProtectedRange> collectProtectedRanges(String text) {
        List<ProtectedRange> ranges = new ArrayList<>();

        // 1. Placeholders — absolute protection
        Matcher pm = PLACEHOLDER_PATTERN.matcher(text);
        while (pm.find()) {
            ranges.add(new ProtectedRange(pm.start(), pm.end(), ProtectionType.PLACEHOLDER));
        }

        // 2. Parentheticals — weak protection
        Matcher paren = PAREN_PATTERN.matcher(text);
        while (paren.find()) {
            if (!overlapsPlaceholder(paren.start(), paren.end(), ranges)) {
                ranges.add(new ProtectedRange(paren.start(), paren.end(), ProtectionType.PARENTHETICAL));
            }
        }

        // 3. Quoted text — weak protection
        Matcher quote = QUOTE_PATTERN.matcher(text);
        while (quote.find()) {
            if (!overlapsPlaceholder(quote.start(), quote.end(), ranges)) {
                ranges.add(new ProtectedRange(quote.start(), quote.end(), ProtectionType.QUOTED));
            }
        }

        return ranges;
    }

    private boolean overlapsPlaceholder(int start, int end, List<ProtectedRange> ranges) {
        for (ProtectedRange r : ranges) {
            if (r.type() == ProtectionType.PLACEHOLDER && start < r.end() && end > r.start()) {
                return true;
            }
        }
        return false;
    }

    private boolean isInProtected(int globalPos, List<ProtectedRange> ranges, boolean strongBoundary) {
        for (ProtectedRange r : ranges) {
            if (globalPos >= r.start() && globalPos < r.end()) {
                // Placeholders always protected; weak protection only blocks non-strong splits
                if (r.type() == ProtectionType.PLACEHOLDER) return true;
                if (!strongBoundary) return true;
            }
        }
        return false;
    }

    // ── Stage 1: Strong structural boundaries ──

    private List<SplitUnit> splitStrongBoundaries(List<SplitUnit> units, String fullText,
                                                   List<ProtectedRange> protectedRanges) {
        List<SplitUnit> result = units;
        // Apply 4 sub-patterns sequentially: blank lines → separators → bullets → numbered lists
        result = applySplitPattern(result, BLANK_LINE, fullText, protectedRanges, 1.0, true);
        result = applySplitPattern(result, EXPLICIT_SEPARATOR, fullText, protectedRanges, 1.0, true);
        result = applySplitPattern(result, BULLET, fullText, protectedRanges, 1.0, true);
        result = applySplitPattern(result, NUMBERED_LIST, fullText, protectedRanges, 1.0, true);
        return result;
    }

    // ── Stage 2: Korean sentence endings with connective filter ──

    private List<SplitUnit> splitKoreanEndings(List<SplitUnit> units, String fullText,
                                                List<ProtectedRange> protectedRanges) {
        List<SplitUnit> current = units;
        for (Pattern endingPattern : KOREAN_ENDING_PATTERNS) {
            current = applySplitPatternWithConnectiveFilter(
                    current, endingPattern, fullText, protectedRanges, 0.95);
        }
        return current;
    }

    /**
     * Split by Korean ending pattern but suppress split when the ending is an ambiguous
     * connective (e.g., ~는데, ~니까, ~거든) used mid-sentence rather than as a sentence terminator.
     */
    private List<SplitUnit> applySplitPatternWithConnectiveFilter(
            List<SplitUnit> units, Pattern pattern, String fullText,
            List<ProtectedRange> protectedRanges, double stageConfidence) {
        List<SplitUnit> result = new ArrayList<>();

        for (SplitUnit unit : units) {
            if (unit.text().length() < 3) {
                result.add(unit);
                continue;
            }

            Matcher m = pattern.matcher(unit.text());
            int lastEnd = 0;
            List<int[]> splitPoints = new ArrayList<>();

            while (m.find()) {
                int globalPos = unit.start() + m.start();
                if (isInProtected(globalPos, protectedRanges, false)) continue;

                // Extract the matched ending text (lookbehind captures the ending before the match)
                String endingText = extractEndingBefore(unit.text(), m.start());
                int textLenBefore = m.start() - lastEnd;

                if (AMBIGUOUS_ENDINGS.contains(endingText)) {
                    // Check if this is genuinely connective (mid-sentence)
                    if (!shouldSplitAmbiguousEnding(unit.text(), m.end(), textLenBefore)) {
                        continue; // Suppress split — this is connective usage
                    }
                }

                splitPoints.add(new int[]{m.start(), m.end()});
                lastEnd = m.end();
            }

            if (splitPoints.isEmpty()) {
                result.add(unit);
            } else {
                int prevEnd = 0;
                for (int[] sp : splitPoints) {
                    String sub = unit.text().substring(prevEnd, sp[0]).trim();
                    if (!sub.isEmpty()) {
                        int subStart = unit.start() + findSubstringStart(unit.text(), prevEnd, sub);
                        result.add(new SplitUnit(sub, subStart, subStart + sub.length(),
                                Math.min(unit.confidence(), stageConfidence)));
                    }
                    prevEnd = sp[1];
                }
                String tail = unit.text().substring(prevEnd).trim();
                if (!tail.isEmpty()) {
                    int tailStart = unit.start() + findSubstringStart(unit.text(), prevEnd, tail);
                    result.add(new SplitUnit(tail, tailStart, tailStart + tail.length(),
                            Math.min(unit.confidence(), stageConfidence)));
                }
            }
        }
        return result;
    }

    /**
     * Extract the Korean ending text that precedes the pattern match position.
     * This finds which specific ending triggered the lookbehind match.
     */
    private String extractEndingBefore(String text, int matchStart) {
        // Try 2-char and 3-char endings (covers all AMBIGUOUS_ENDINGS)
        for (int len = 3; len >= 2; len--) {
            if (matchStart >= len) {
                String candidate = text.substring(matchStart - len, matchStart);
                if (AMBIGUOUS_ENDINGS.contains(candidate)) {
                    return candidate;
                }
            }
        }
        // 1-char ending ("고")
        if (matchStart >= 1) {
            String candidate = text.substring(matchStart - 1, matchStart);
            if (AMBIGUOUS_ENDINGS.contains(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * Determine whether an ambiguous ending should split:
     * - At end of chunk → split (terminal usage)
     * - Followed by discourse marker → split (new sentence)
     * - Text before > 250 chars → split (length safety)
     * - Otherwise → suppress (connective usage)
     */
    private boolean shouldSplitAmbiguousEnding(String chunkText, int afterMatchEnd, int textLenBefore) {
        // Length safety: if preceding text is long enough, split anyway
        if (textLenBefore > 250) return true;

        String remaining = chunkText.substring(afterMatchEnd).trim();

        // At end of chunk → terminal usage → split
        if (remaining.isEmpty()) return true;

        // Followed by discourse marker → new sentence → split
        for (String marker : DISCOURSE_MARKERS_SET) {
            if (remaining.startsWith(marker + " ") || remaining.startsWith(marker + "\n")
                    || remaining.equals(marker)) {
                return true;
            }
        }

        return false;
    }

    // ── Stage 3: Weak punctuation boundaries ──

    private List<SplitUnit> splitWeakBoundaries(List<SplitUnit> units, String fullText,
                                                 List<ProtectedRange> protectedRanges) {
        return applySplitPattern(units, WEAK_BOUNDARY, fullText, protectedRanges, 0.9, false);
    }

    // ── Stage 4: Length-based safety split ──

    private List<SplitUnit> forceSplitLong(List<SplitUnit> units, String fullText,
                                            List<ProtectedRange> protectedRanges) {
        List<SplitUnit> current = new ArrayList<>(units);
        // Iterate until no more splits needed (max 5 passes)
        for (int pass = 0; pass < 5; pass++) {
            List<SplitUnit> result = new ArrayList<>();
            boolean didSplit = false;

            for (SplitUnit unit : current) {
                if (unit.text().length() <= maxSegmentLength) {
                    result.add(unit);
                    continue;
                }

                String chunk = unit.text();
                int mid = chunk.length() / 2;
                int bestSplit = -1;
                int bestDist = Integer.MAX_VALUE;

                int searchStart = Math.max(10, mid - 60);
                int searchEnd = Math.min(chunk.length() - 5, mid + 60);

                for (int i = searchStart; i < searchEnd; i++) {
                    char c = chunk.charAt(i);
                    if ((c == ' ' || c == ',' || c == '\n')
                            && !isInProtected(unit.start() + i, protectedRanges, false)) {
                        // Check postposition avoidance
                        if (isAfterPostposition(chunk, i)) continue;

                        int dist = Math.abs(i - mid);
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestSplit = i + 1;
                        }
                    }
                }

                // If postposition avoidance rejected all candidates, retry without avoidance
                if (bestSplit < 0) {
                    for (int i = searchStart; i < searchEnd; i++) {
                        char c = chunk.charAt(i);
                        if ((c == ' ' || c == ',' || c == '\n')
                                && !isInProtected(unit.start() + i, protectedRanges, false)) {
                            int dist = Math.abs(i - mid);
                            if (dist < bestDist) {
                                bestDist = dist;
                                bestSplit = i + 1;
                            }
                        }
                    }
                }

                if (bestSplit > 0) {
                    String left = chunk.substring(0, bestSplit).trim();
                    String right = chunk.substring(bestSplit).trim();
                    if (!left.isEmpty()) {
                        int leftStart = unit.start() + findSubstringStart(chunk, 0, left);
                        result.add(new SplitUnit(left, leftStart, leftStart + left.length(),
                                Math.min(unit.confidence(), 0.85)));
                    }
                    if (!right.isEmpty()) {
                        int rightStart = unit.start() + findSubstringStart(chunk, bestSplit, right);
                        result.add(new SplitUnit(right, rightStart, rightStart + right.length(),
                                Math.min(unit.confidence(), 0.85)));
                    }
                    didSplit = true;
                } else {
                    result.add(unit);
                }
            }
            current = result;
            if (!didSplit) break;
        }
        return current;
    }

    /**
     * Check if the character at position i is immediately preceded by a Korean postposition.
     * If so, splitting here would break the noun+postposition unit.
     */
    private boolean isAfterPostposition(String chunk, int splitPos) {
        // Check 1~3 chars before splitPos for postposition match
        for (int len = 3; len >= 1; len--) {
            int start = splitPos - len;
            if (start < 0) continue;
            String candidate = chunk.substring(start, splitPos);
            if (POSTPOSITIONS.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    // ── Stage 5: Enumeration detection ──

    private List<SplitUnit> splitEnumerations(List<SplitUnit> units, String fullText,
                                               List<ProtectedRange> protectedRanges) {
        List<SplitUnit> result = new ArrayList<>();

        for (SplitUnit unit : units) {
            if (unit.text().length() <= enumerationMinLength) {
                result.add(unit);
                continue;
            }

            // Try comma list
            List<SplitUnit> commaSplit = trySplitByDelimiter(unit, COMMA_LIST, protectedRanges, 3, 15);
            if (commaSplit != null) {
                result.addAll(commaSplit);
                continue;
            }

            // Try delimiter list (/ · |)
            List<SplitUnit> delimSplit = trySplitByDelimiter(unit, DELIMITER_LIST, protectedRanges, 3, 15);
            if (delimSplit != null) {
                result.addAll(delimSplit);
                continue;
            }

            // Try parallel ~고 structure
            List<SplitUnit> goSplit = trySplitByDelimiter(unit, PARALLEL_GO, protectedRanges, 3, 15);
            if (goSplit != null) {
                result.addAll(goSplit);
                continue;
            }

            result.add(unit);
        }
        return result;
    }

    /**
     * Try splitting a unit by a delimiter pattern. Returns null if conditions not met:
     * - At least minParts resulting parts
     * - Each part at least minPartLength chars
     */
    private List<SplitUnit> trySplitByDelimiter(SplitUnit unit, Pattern delimiter,
                                                 List<ProtectedRange> protectedRanges,
                                                 int minParts, int minPartLength) {
        String text = unit.text();
        Matcher m = delimiter.matcher(text);
        List<int[]> matchPositions = new ArrayList<>();

        while (m.find()) {
            int globalPos = unit.start() + m.start();
            if (!isInProtected(globalPos, protectedRanges, false)) {
                matchPositions.add(new int[]{m.start(), m.end()});
            }
        }

        if (matchPositions.size() < minParts - 1) return null;

        // Build parts
        List<SplitUnit> parts = new ArrayList<>();
        int prevEnd = 0;
        for (int[] mp : matchPositions) {
            String part = text.substring(prevEnd, mp[0]).trim();
            if (!part.isEmpty()) {
                int partStart = unit.start() + findSubstringStart(text, prevEnd, part);
                parts.add(new SplitUnit(part, partStart, partStart + part.length(),
                        Math.min(unit.confidence(), 0.9)));
            }
            prevEnd = mp[1];
        }
        String tail = text.substring(prevEnd).trim();
        if (!tail.isEmpty()) {
            int tailStart = unit.start() + findSubstringStart(text, prevEnd, tail);
            parts.add(new SplitUnit(tail, tailStart, tailStart + tail.length(),
                    Math.min(unit.confidence(), 0.9)));
        }

        if (parts.size() < minParts) return null;

        // Check all parts meet minimum length
        for (SplitUnit p : parts) {
            if (p.text().length() < minPartLength) return null;
        }

        return parts;
    }

    // ── Stage 6: Discourse markers (length-restricted) ──

    private List<SplitUnit> splitDiscourseMarkers(List<SplitUnit> units, String fullText,
                                                    List<ProtectedRange> protectedRanges) {
        List<SplitUnit> result = new ArrayList<>();

        for (SplitUnit unit : units) {
            if (unit.text().length() <= discourseMarkerMinLength) {
                result.add(unit);
                continue;
            }

            Matcher m = DISCOURSE_MARKER_SPLIT.matcher(unit.text());
            int lastEnd = 0;
            List<Integer> splitPoints = new ArrayList<>();

            while (m.find()) {
                int globalPos = unit.start() + m.start();
                if (isInProtected(globalPos, protectedRanges, false)) continue;

                // Check compound word exclusion
                String remaining = unit.text().substring(m.end());
                if (isCompoundMarker(remaining)) continue;

                // Check that marker is followed by space then content (not just the marker alone)
                if (remaining.trim().length() <= 4) continue;

                splitPoints.add(m.end());
                lastEnd = m.end();
            }

            if (splitPoints.isEmpty()) {
                result.add(unit);
            } else {
                int prevEnd = 0;
                for (int sp : splitPoints) {
                    String sub = unit.text().substring(prevEnd, sp).trim();
                    if (!sub.isEmpty()) {
                        int subStart = unit.start() + findSubstringStart(unit.text(), prevEnd, sub);
                        result.add(new SplitUnit(sub, subStart, subStart + sub.length(),
                                Math.min(unit.confidence(), 0.88)));
                    }
                    prevEnd = sp;
                }
                String tail = unit.text().substring(prevEnd).trim();
                if (!tail.isEmpty()) {
                    int tailStart = unit.start() + findSubstringStart(unit.text(), prevEnd, tail);
                    result.add(new SplitUnit(tail, tailStart, tailStart + tail.length(),
                            Math.min(unit.confidence(), 0.88)));
                }
            }
        }
        return result;
    }

    /**
     * Check if the remaining text starts with a compound word that contains a discourse marker.
     * e.g., "그런데도", "그래서인지" — should not be split.
     */
    private boolean isCompoundMarker(String remaining) {
        String trimmed = remaining.trim();
        for (String compound : COMPOUND_SUFFIXES) {
            if (trimmed.startsWith(compound)) return true;
        }
        // Also check: marker + hangul without space = compound
        for (String marker : DISCOURSE_MARKERS_SET) {
            if (trimmed.startsWith(marker) && trimmed.length() > marker.length()) {
                char next = trimmed.charAt(marker.length());
                if (next != ' ' && next != '\n' && isHangul(next)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isHangul(char c) {
        return (c >= '\uAC00' && c <= '\uD7A3')   // Hangul Syllables
                || (c >= '\u3131' && c <= '\u318E'); // Hangul Compatibility Jamo
    }

    // ── Stage 7: Merge short segments ──

    private List<SplitUnit> mergeShortUnits(List<SplitUnit> units, String fullText) {
        if (units.size() <= 1) return units;

        List<SplitUnit> result = new ArrayList<>();
        int i = 0;
        while (i < units.size()) {
            int shortStart = i;
            while (i < units.size() && units.get(i).text().length() < MIN_SEGMENT_LENGTH) {
                i++;
            }
            int shortCount = i - shortStart;

            if (shortCount >= MIN_SHORT_CONSECUTIVE) {
                // Check that we don't merge across placeholder boundaries
                List<List<SplitUnit>> groups = groupByPlaceholderBoundary(units, shortStart, i, fullText);
                for (List<SplitUnit> group : groups) {
                    if (group.size() >= MIN_SHORT_CONSECUTIVE) {
                        result.add(mergeGroup(group));
                    } else {
                        result.addAll(group);
                    }
                }
            } else {
                for (int j = shortStart; j < i; j++) {
                    result.add(units.get(j));
                }
            }

            if (i < units.size()) {
                result.add(units.get(i));
                i++;
            }
        }
        return result;
    }

    /**
     * Split a range of short units into sub-groups that don't cross placeholder boundaries.
     */
    private List<List<SplitUnit>> groupByPlaceholderBoundary(List<SplitUnit> units,
                                                              int from, int to, String fullText) {
        List<List<SplitUnit>> groups = new ArrayList<>();
        List<SplitUnit> current = new ArrayList<>();

        for (int i = from; i < to; i++) {
            SplitUnit unit = units.get(i);
            boolean containsPlaceholder = PLACEHOLDER_PATTERN.matcher(unit.text()).find();

            if (containsPlaceholder && !current.isEmpty()) {
                groups.add(current);
                current = new ArrayList<>();
            }

            current.add(unit);

            if (containsPlaceholder) {
                groups.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private SplitUnit mergeGroup(List<SplitUnit> group) {
        int start = group.getFirst().start();
        int end = group.getLast().end();
        StringBuilder text = new StringBuilder();
        double minConf = Double.MAX_VALUE;
        for (SplitUnit u : group) {
            if (!text.isEmpty()) text.append(" ");
            text.append(u.text());
            minConf = Math.min(minConf, u.confidence());
        }
        return new SplitUnit(text.toString(), start, end, minConf);
    }

    // ── Generic split utility ──

    /**
     * Apply a split pattern to each unit. Position tracking via SplitUnit offsets.
     */
    private List<SplitUnit> applySplitPattern(List<SplitUnit> units, Pattern pattern,
                                               String fullText, List<ProtectedRange> protectedRanges,
                                               double stageConfidence, boolean isStrongBoundary) {
        List<SplitUnit> result = new ArrayList<>();

        for (SplitUnit unit : units) {
            if (unit.text().length() < 3) {
                result.add(unit);
                continue;
            }

            Matcher m = pattern.matcher(unit.text());
            int lastEnd = 0;
            boolean split = false;

            while (m.find()) {
                int globalPos = unit.start() + m.start();
                if (isInProtected(globalPos, protectedRanges, isStrongBoundary)) continue;

                String sub = unit.text().substring(lastEnd, m.start()).trim();
                if (!sub.isEmpty()) {
                    int subStart = unit.start() + findSubstringStart(unit.text(), lastEnd, sub);
                    result.add(new SplitUnit(sub, subStart, subStart + sub.length(),
                            Math.min(unit.confidence(), stageConfidence)));
                    split = true;
                }
                lastEnd = m.end();
            }

            if (split) {
                String tail = unit.text().substring(lastEnd).trim();
                if (!tail.isEmpty()) {
                    int tailStart = unit.start() + findSubstringStart(unit.text(), lastEnd, tail);
                    result.add(new SplitUnit(tail, tailStart, tailStart + tail.length(),
                            Math.min(unit.confidence(), stageConfidence)));
                }
            } else {
                result.add(unit);
            }
        }
        return result;
    }

    // ── Position helpers ──

    /**
     * Find the start position of a trimmed substring within the parent text,
     * starting from a known offset. This handles the gap between lastEnd and
     * the actual start of trimmed text.
     */
    private int findSubstringStart(String parent, int searchFrom, String trimmed) {
        // After trimming, the actual start is at or after searchFrom
        int pos = parent.indexOf(trimmed, searchFrom);
        return (pos >= 0) ? pos : searchFrom;
    }

    // ── Output conversion ──

    private List<Segment> toSegments(List<SplitUnit> units) {
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            SplitUnit u = units.get(i);
            segments.add(new Segment("T" + (i + 1), u.text(), u.start(), u.end()));
        }
        return segments;
    }
}
