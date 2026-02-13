package com.politeai.infrastructure.ai.segmentation;

import com.politeai.domain.transform.model.Segment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based text segmenter. No LLM calls.
 *
 * Algorithm:
 *   1. Collect {{LOCKED_N}} placeholder positions (atomic, never split)
 *   2. Strong boundary split: \n\n+, bullets (- * •), numbered lists (1. 1))
 *   3. Weak boundary split: sentence-ending punctuation (.!?) + whitespace/EOL
 *   4. Korean sentence ending split: -습니다, -요, -죠 etc. + space
 *   5. Korean transition words: 그리고, 그런데, 하지만, 따라서 etc.
 *   6. Force-split long segments (>180 chars) at nearest weak boundary
 *   7. Merge consecutive short segments (<5 chars, 3+ in a row)
 *   8. Assign IDs: T1, T2, ..., Tn
 */
@Slf4j
@Component
public class MeaningSegmenter {

    private static final int MAX_SEGMENT_LENGTH = 180;
    private static final int MIN_SEGMENT_LENGTH = 5;
    private static final int MIN_SHORT_CONSECUTIVE = 3;

    // Placeholder pattern — must not be split
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{LOCKED_\\d+}}");

    // Strong boundaries
    private static final Pattern STRONG_BOUNDARY = Pattern.compile(
            "\\n\\n+|(?<=\\n)(?:[-*•]\\s)|(?<=\\n)(?:\\d{1,3}[.)][\\s])"
    );

    // Weak boundary: sentence-ending punctuation followed by space or end-of-line
    private static final Pattern WEAK_BOUNDARY = Pattern.compile(
            "(?<=[.!?])\\s+|(?<=[.!?])$", Pattern.MULTILINE
    );

    // Korean sentence endings followed by space
    private static final Pattern KOREAN_ENDING = Pattern.compile(
            "(?<=습니다|입니다|됩니다|겠습니다|십시오|세요|에요|해요|예요|네요|군요|는데요|거든요|잖아요|지요|어요|아요|죠)(?:\\s+|[.!?]\\s*)"
    );

    // Korean transition words at start of a clause (preceded by space or comma)
    private static final Pattern TRANSITION_WORD = Pattern.compile(
            "(?<=\\s|,\\s?)(?=(?:그리고|그런데|근데|하지만|그래서|따라서|그러나|그래도|그러면|그럼|또한|게다가|반면|한편|더불어|아울러|결국|즉|다만|단|물론|사실|솔직히|참고로|덧붙여|마지막으로|우선|먼저|다음으로|특히|결론적으로|요약하면|정리하면|아무튼|어쨌든|왜냐하면|왜냐면)\\s)"
    );

    public List<Segment> segment(String maskedText) {
        if (maskedText == null || maskedText.isBlank()) {
            return List.of();
        }

        // Collect placeholder ranges (protected zones)
        List<int[]> protectedRanges = findPlaceholderRanges(maskedText);

        // Step 1-2: Split by strong boundaries
        List<String> chunks = splitByPattern(maskedText, STRONG_BOUNDARY, protectedRanges);

        // Step 3: Split by weak boundaries (sentence endings)
        chunks = refineByPattern(chunks, maskedText, WEAK_BOUNDARY, protectedRanges);

        // Step 4: Split by Korean endings
        chunks = refineByPattern(chunks, maskedText, KOREAN_ENDING, protectedRanges);

        // Step 5: Split by transition words
        chunks = refineByPattern(chunks, maskedText, TRANSITION_WORD, protectedRanges);

        // Step 6: Force-split long segments
        chunks = forceSplitLong(chunks, maskedText, protectedRanges);

        // Step 7: Merge short consecutive segments
        chunks = mergeShortSegments(chunks);

        // Step 8: Build Segment list with positions
        List<Segment> segments = buildSegments(chunks, maskedText);

        log.info("[Segmenter] {} segments from {} chars", segments.size(), maskedText.length());
        return segments;
    }

    private List<int[]> findPlaceholderRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        while (m.find()) {
            ranges.add(new int[]{m.start(), m.end()});
        }
        return ranges;
    }

    private boolean isInProtectedRange(int pos, List<int[]> protectedRanges) {
        for (int[] range : protectedRanges) {
            if (pos >= range[0] && pos < range[1]) return true;
        }
        return false;
    }

    /**
     * Initial split by a boundary pattern.
     */
    private List<String> splitByPattern(String text, Pattern pattern, List<int[]> protectedRanges) {
        List<String> result = new ArrayList<>();
        Matcher m = pattern.matcher(text);

        int lastEnd = 0;
        while (m.find()) {
            if (isInProtectedRange(m.start(), protectedRanges)) continue;

            String chunk = text.substring(lastEnd, m.start()).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
            lastEnd = m.end();
        }

        String tail = text.substring(lastEnd).trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }

        return result.isEmpty() ? List.of(text.trim()) : result;
    }

    /**
     * Refine existing chunks by further splitting with another pattern.
     */
    private List<String> refineByPattern(List<String> chunks, String fullText,
                                          Pattern pattern, List<int[]> protectedRanges) {
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            Matcher m = pattern.matcher(chunk);
            int lastEnd = 0;
            boolean split = false;

            while (m.find()) {
                // Map local position to global to check protected ranges
                int globalPos = fullText.indexOf(chunk) + m.start();
                if (isInProtectedRange(globalPos, protectedRanges)) continue;

                String sub = chunk.substring(lastEnd, m.start()).trim();
                if (!sub.isEmpty()) {
                    result.add(sub);
                    split = true;
                }
                lastEnd = m.end();
            }

            if (split) {
                String tail = chunk.substring(lastEnd).trim();
                if (!tail.isEmpty()) {
                    result.add(tail);
                }
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * Force-split segments longer than MAX_SEGMENT_LENGTH at the nearest weak boundary.
     */
    private List<String> forceSplitLong(List<String> chunks, String fullText, List<int[]> protectedRanges) {
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() <= MAX_SEGMENT_LENGTH) {
                result.add(chunk);
                continue;
            }

            // Try to find a split point near the middle
            int mid = chunk.length() / 2;
            int bestSplit = -1;
            int bestDist = Integer.MAX_VALUE;

            // Look for space/comma near middle
            for (int i = Math.max(10, mid - 60); i < Math.min(chunk.length() - 5, mid + 60); i++) {
                char c = chunk.charAt(i);
                if ((c == ' ' || c == ',' || c == '\n') && !isInProtectedRange(fullText.indexOf(chunk) + i, protectedRanges)) {
                    int dist = Math.abs(i - mid);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestSplit = i + 1;
                    }
                }
            }

            if (bestSplit > 0) {
                String left = chunk.substring(0, bestSplit).trim();
                String right = chunk.substring(bestSplit).trim();
                if (!left.isEmpty()) result.add(left);
                if (!right.isEmpty()) result.add(right);
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * Merge consecutive short segments (< MIN_SEGMENT_LENGTH chars, 3+ in a row).
     */
    private List<String> mergeShortSegments(List<String> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < chunks.size()) {
            // Count consecutive short segments
            int shortStart = i;
            while (i < chunks.size() && chunks.get(i).length() < MIN_SEGMENT_LENGTH) {
                i++;
            }

            int shortCount = i - shortStart;
            if (shortCount >= MIN_SHORT_CONSECUTIVE) {
                // Merge all consecutive short segments
                StringBuilder merged = new StringBuilder();
                for (int j = shortStart; j < i; j++) {
                    if (!merged.isEmpty()) merged.append(" ");
                    merged.append(chunks.get(j));
                }
                result.add(merged.toString());
            } else {
                // Add them individually
                for (int j = shortStart; j < i; j++) {
                    result.add(chunks.get(j));
                }
            }

            if (i < chunks.size()) {
                result.add(chunks.get(i));
                i++;
            }
        }
        return result;
    }

    /**
     * Build Segment records with IDs and positions in the original masked text.
     */
    private List<Segment> buildSegments(List<String> chunks, String maskedText) {
        List<Segment> segments = new ArrayList<>();
        int searchFrom = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int start = maskedText.indexOf(chunk, searchFrom);
            if (start < 0) {
                // Fallback: use sequential position
                start = searchFrom;
            }
            int end = start + chunk.length();
            segments.add(new Segment("T" + (i + 1), chunk, start, end));
            searchFrom = end;
        }

        return segments;
    }
}
