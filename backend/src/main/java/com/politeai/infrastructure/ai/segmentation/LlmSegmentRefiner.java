package com.politeai.infrastructure.ai.segmentation;

import com.politeai.domain.transform.model.Segment;
import com.politeai.infrastructure.ai.AiTransformService;
import com.politeai.infrastructure.ai.LlmCallResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based segment refiner for long segments.
 *
 * After rule-based MeaningSegmenter, segments exceeding the length threshold
 * are batched into a single LLM call (gpt-4o-mini, temp=0) for semantic splitting.
 *
 * Flow:
 *   1. Filter segments > minLength (default 40 chars)
 *   2. Batch long segments into one prompt
 *   3. LLM inserts ||| delimiters at semantic boundaries
 *   4. Parse response, validate sub-texts exist in original
 *   5. Rebuild segment list with updated IDs (T1..Tn)
 *
 * If LLM fails or produces invalid output, original segments are kept as-is.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmSegmentRefiner {

    private static final String MODEL = "gpt-4o-mini";
    private static final double TEMPERATURE = 0.0;
    private static final int MAX_TOKENS = 600;

    private static final String SYSTEM_PROMPT =
            "당신은 한국어 텍스트 의미 분절 전문가입니다.\n\n" +
            "각 항목이 둘 이상의 독립된 의미 단위(완결된 생각/주장/사실)를 포함할 때만 분리하세요.\n" +
            "하나의 의미 단위라면 길더라도 원문 그대로 출력하세요. 무리하게 쪼개지 마세요.\n\n" +
            "규칙:\n" +
            "1. 분리 시 ||| 를 삽입하세요\n" +
            "2. 원문 텍스트를 정확히 보존하세요 (한 글자도 변경/추가/삭제 금지)\n" +
            "3. {{TYPE_N}} 형식 플레이스홀더(예: {{DATE_1}}, {{PHONE_1}})는 절대 분리하지 마세요\n" +
            "4. 너무 짧은 조각(10자 미만)이 생기지 않도록 하세요\n" +
            "5. [N] 번호를 유지하고, 각 항목을 한 줄에 출력하세요";

    private static final Pattern ENTRY_PATTERN = Pattern.compile("\\[(\\d+)]\\s*(.+)");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{[A-Z]+_\\d+\\}\\}");

    private final AiTransformService aiTransformService;

    @Value("${segmenter.refine.min-length:40}")
    private int minLength;

    public record RefineResult(List<Segment> segments, long promptTokens, long completionTokens) {}

    /**
     * Refine long segments using LLM. Returns refined segment list with re-indexed IDs.
     * If no segments exceed minLength, returns original segments with zero token usage.
     */
    public RefineResult refine(List<Segment> segments, String maskedText) {
        // Find long segments
        List<Integer> longIndices = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).text().length() > minLength) {
                longIndices.add(i);
            }
        }

        if (longIndices.isEmpty()) {
            log.debug("[SegmentRefiner] No segments > {} chars, skipping LLM", minLength);
            return new RefineResult(segments, 0, 0);
        }

        log.info("[SegmentRefiner] {} segments > {} chars, invoking LLM", longIndices.size(), minLength);

        // Build user message
        StringBuilder userMsg = new StringBuilder();
        for (int i = 0; i < longIndices.size(); i++) {
            Segment seg = segments.get(longIndices.get(i));
            userMsg.append("[").append(i + 1).append("] ").append(seg.text()).append("\n");
        }

        try {
            LlmCallResult result = aiTransformService.callOpenAIWithModel(
                    MODEL, SYSTEM_PROMPT, userMsg.toString(), TEMPERATURE, MAX_TOKENS, null);

            // Parse LLM response
            List<List<String>> parsedSplits = parseResponse(result.content(), longIndices.size(), segments, longIndices);

            // Rebuild full segment list
            List<Segment> refined = rebuildSegments(segments, longIndices, parsedSplits, maskedText);

            log.info("[SegmentRefiner] {} → {} segments (LLM split {} long segments)",
                    segments.size(), refined.size(), longIndices.size());

            return new RefineResult(refined, result.promptTokens(), result.completionTokens());

        } catch (Exception e) {
            log.warn("[SegmentRefiner] LLM call failed, keeping original segments: {}", e.getMessage());
            return new RefineResult(segments, 0, 0);
        }
    }

    /**
     * Parse LLM response into split parts per entry.
     * Returns list of string lists (one per long segment). If parsing fails for an entry,
     * returns the original segment text as a single-element list.
     */
    private List<List<String>> parseResponse(String response, int expectedCount,
                                              List<Segment> segments, List<Integer> longIndices) {
        List<List<String>> result = new ArrayList<>();

        // Initialize with originals as fallback
        for (int idx : longIndices) {
            result.add(List.of(segments.get(idx).text()));
        }

        String[] lines = response.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher m = ENTRY_PATTERN.matcher(line);
            if (!m.matches()) continue;

            int entryNum = Integer.parseInt(m.group(1));
            String content = m.group(2).trim();

            if (entryNum < 1 || entryNum > expectedCount) continue;

            int entryIdx = entryNum - 1;
            String originalText = segments.get(longIndices.get(entryIdx)).text();

            // Split by |||
            String[] parts = content.split("\\|\\|\\|");
            List<String> validParts = new ArrayList<>();

            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    validParts.add(trimmed);
                }
            }

            // Validate: all parts must be findable in the original segment text
            if (validParts.size() > 1 && validateParts(validParts, originalText)) {
                result.set(entryIdx, validParts);
            } else if (validParts.size() == 1) {
                // No split needed — keep original
                result.set(entryIdx, List.of(originalText));
            }
            // else: validation failed, keep original (already set)
        }

        return result;
    }

    /**
     * Validate that all parts can be found sequentially in the original text.
     * Allows minor whitespace differences.
     */
    private boolean validateParts(List<String> parts, String originalText) {
        int searchFrom = 0;
        for (String part : parts) {
            int pos = originalText.indexOf(part, searchFrom);
            if (pos < 0) {
                // Try with normalized whitespace
                String normalized = part.replaceAll("\\s+", " ");
                pos = originalText.indexOf(normalized, searchFrom);
                if (pos < 0) {
                    log.debug("[SegmentRefiner] Part '{}...' not found in original at offset {}",
                            part.substring(0, Math.min(30, part.length())), searchFrom);
                    return false;
                }
            }
            searchFrom = pos + part.length();
        }
        return true;
    }

    /**
     * Rebuild the full segment list by replacing long segments with their split parts.
     * Re-indexes all segments as T1..Tn and recalculates positions.
     */
    private List<Segment> rebuildSegments(List<Segment> original, List<Integer> longIndices,
                                           List<List<String>> splits, String maskedText) {
        List<Segment> result = new ArrayList<>();
        int longIdx = 0;
        int segId = 1;

        for (int i = 0; i < original.size(); i++) {
            if (longIdx < longIndices.size() && longIndices.get(longIdx) == i) {
                // This was a long segment — insert split parts
                List<String> parts = splits.get(longIdx);
                int searchFrom = original.get(i).start();

                for (String part : parts) {
                    int pos = maskedText.indexOf(part, searchFrom);
                    if (pos < 0) {
                        // Fallback: sequential position
                        pos = searchFrom;
                        log.warn("[SegmentRefiner] Split part not found in maskedText, using fallback pos {}", pos);
                    }
                    int end = Math.min(pos + part.length(), maskedText.length());
                    result.add(new Segment("T" + segId++, part, pos, end));
                    searchFrom = end;
                }
                longIdx++;
            } else {
                // Keep original segment with new ID
                Segment seg = original.get(i);
                result.add(new Segment("T" + segId++, seg.text(), seg.start(), seg.end()));
            }
        }

        return result;
    }
}
