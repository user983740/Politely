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
 *   3. Weak boundary split: .!?;…—– + whitespace/EOL
 *   4. Korean sentence ending split: ~120 patterns (시제어미 character-class 일반화)
 *   5. Korean transition words: ~109 접속부사 (단어 단위만, 다어절 구 제외)
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

    // Weak boundary: sentence-ending punctuation, ellipsis, semicolon, dashes
    private static final Pattern WEAK_BOUNDARY = Pattern.compile(
            "(?<=[.!?;])\\s+|(?<=[.!?;])$|(?<=…)\\s*|(?<=\\.{3})\\s*|(?<=[—–])\\s*", Pattern.MULTILINE
    );

    // Korean sentence endings — ~120 patterns with character-class generalization
    // 시제어미([았었했됐...]+어/지/음/다) = character class로 일반화, 나머지는 개별 패턴
    private static final Pattern KOREAN_ENDING = Pattern.compile(
            "(?<=" +
                    // === 합쇼체 (formal polite) ===
                    "겠습니다|하십시오|겠습니까|" +
                    "습니다|입니다|됩니다|합니다|답니다|랍니다|십니다|" +
                    "습니까|입니까|됩니까|합니까|십니까|십시오|" +
                    // === 해요체 — 3음절 복합 어미 ===
                    "는데요|거든요|잖아요|니까요|라서요|던가요|텐데요|다고요|라고요|냐고요|자고요|은데요|던데요|" +
                    // === 해요체 — 2음절 X요 ===
                    "세요|에요|해요|예요|네요|군요|지요|어요|아요|게요|래요|나요|가요|고요|서요|걸요|대요|까요|셔요|구요|" +
                    // === 해체 — 시제어미+어 (character-class) ===
                    "[았었했됐갔왔봤줬났겠셨]어|" +
                    // === 해체 — 상태/부정 ===
                    "같어|않아|없어|있어|못해|" +
                    // === 해체 — 시제어미+지 (character-class) ===
                    "[았었했됐겠셨]지|" +
                    // === 해체 — 종결 어미 (중문 연결어미 라서/지만/어서/아서/해서 제외) ===
                    "거든|잖아|는데|인데|한데|은데|던데|텐데|더라|니까|" +
                    // === 해체 — 의지/제안/명령/감탄 ===
                    "할래|할게|갈게|볼게|줄게|을래|을게|을걸|" +
                    "하자|해라|해봐|구나|구먼|이야|거야|건데|" +
                    "다며|다더라|그치|시죠|던가|" +
                    // === 하게체 ===
                    "하게|하네|하세|" +
                    // === 명사형 — 시제어미+음 (character-class) ===
                    "[했됐봤왔갔줬났]음|" +
                    // === 명사형 — 상태/고정 패턴 ===
                    "같음|있음|없음|아님|맞음|모름|드림|올림|알림|바람|나름|받음|보냄|" +
                    // === 평서형 — 시제어미+다 (character-class) ===
                    "[했됐봤왔갔줬났겠]다|" +
                    // === 평서형 — 기본형+다 ===
                    "있다|없다|같다|한다|된다|간다|온다|는다|" +
                    // === 명사형 — 2음절 ===
                    "됨|임|함|" +
                    // === 기타 ===
                    "죠|ㅋㅋ|ㅎㅎ|ㅠㅠ|ㅜㅜ" +
            ")(?:\\s+|[.!?…~;]\\s*)"
    );

    // Korean transition words — ~109 단어 단위 접속부사 (다어절 구 제거, 고어/문어체/중복 제거)
    private static final Pattern TRANSITION_WORD = Pattern.compile(
            "(?<=\\s|[,;]\\s?)(?=(?:" +
                    // === 나열/추가 ===
                    "그리고|또한|게다가|더불어|아울러|더구나|심지어|나아가|마찬가지로|" +
                    // === 대조/양보 ===
                    "그런데|근데|하지만|그러나|그래도|반면|한편|오히려|대신|그렇지만|그럼에도|반대로|역시|차라리|하긴|그나마|" +
                    // === 인과/결과 ===
                    "그래서|따라서|그러므로|결국|그러니까|그러니|결과적으로|덕분에|그니까|그래갖고|" +
                    // === 조건/가정 ===
                    "그러면|그럼|그렇다면|만약|만일|아니면|혹시|가령|설령|설사|또는|혹은|설마|" +
                    // === 전환/화제 ===
                    "아무튼|어쨌든|어쨌거나|아무래도|그나저나|어차피|하다못해|암튼|됐고|" +
                    // === 부연/예시 ===
                    "즉|다만|사실|물론|솔직히|참고로|예컨대|이를테면|말하자면|소위|이른바|요는|" +
                    // === 순서/시간 ===
                    "우선|먼저|다음으로|마지막으로|이후|이어서|동시에|앞서|첫째|둘째|셋째|끝으로|그다음|그전에|나중에|마침내|드디어|그러다가|향후|처음에|" +
                    // === 강조 ===
                    "특히|무엇보다|더욱이|과연|" +
                    // === 태도/확신 ===
                    "확실히|분명|아마|당연히|" +
                    // === 요약/이유 ===
                    "결론적으로|요약하면|종합하면|한마디로|왜냐하면|왜냐면|덧붙여|" +
                    // === 비즈니스 ===
                    "추가로|별도로|거듭|" +
                    // === 구어체 ===
                    "있잖아|있잖아요" +
            ")\\s)"
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
     * Tracks global offset per chunk to avoid indexOf collisions with duplicate text.
     */
    private List<String> refineByPattern(List<String> chunks, String fullText,
                                          Pattern pattern, List<int[]> protectedRanges) {
        // Pre-compute global offsets for each chunk to avoid indexOf collisions
        int[] globalOffsets = computeChunkOffsets(chunks, fullText);

        List<String> result = new ArrayList<>();
        for (int ci = 0; ci < chunks.size(); ci++) {
            String chunk = chunks.get(ci);
            int chunkGlobalOffset = globalOffsets[ci];
            Matcher m = pattern.matcher(chunk);
            int lastEnd = 0;
            boolean split = false;

            while (m.find()) {
                int globalPos = chunkGlobalOffset + m.start();
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
     * Compute global offsets for each chunk by sequential search from the last match end.
     * Avoids indexOf collisions when duplicate text exists.
     */
    private int[] computeChunkOffsets(List<String> chunks, String fullText) {
        int[] offsets = new int[chunks.size()];
        int searchFrom = 0;
        for (int i = 0; i < chunks.size(); i++) {
            int pos = fullText.indexOf(chunks.get(i), searchFrom);
            offsets[i] = (pos >= 0) ? pos : searchFrom;
            searchFrom = offsets[i] + chunks.get(i).length();
        }
        return offsets;
    }

    /**
     * Force-split segments longer than MAX_SEGMENT_LENGTH at the nearest weak boundary.
     * Uses sequential offset tracking to avoid indexOf collisions.
     */
    private List<String> forceSplitLong(List<String> chunks, String fullText, List<int[]> protectedRanges) {
        int[] globalOffsets = computeChunkOffsets(chunks, fullText);

        List<String> result = new ArrayList<>();
        for (int ci = 0; ci < chunks.size(); ci++) {
            String chunk = chunks.get(ci);
            if (chunk.length() <= MAX_SEGMENT_LENGTH) {
                result.add(chunk);
                continue;
            }

            int chunkGlobalOffset = globalOffsets[ci];

            // Try to find a split point near the middle
            int mid = chunk.length() / 2;
            int bestSplit = -1;
            int bestDist = Integer.MAX_VALUE;

            // Look for space/comma near middle
            for (int i = Math.max(10, mid - 60); i < Math.min(chunk.length() - 5, mid + 60); i++) {
                char c = chunk.charAt(i);
                if ((c == ' ' || c == ',' || c == '\n') && !isInProtectedRange(chunkGlobalOffset + i, protectedRanges)) {
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
     * Uses sequential offset tracking to correctly handle duplicate text.
     */
    private List<Segment> buildSegments(List<String> chunks, String maskedText) {
        List<Segment> segments = new ArrayList<>();
        int searchFrom = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int start = maskedText.indexOf(chunk, searchFrom);
            if (start < 0) {
                // Fallback: use sequential position, but clamp to text bounds
                start = Math.min(searchFrom, maskedText.length());
                log.warn("[Segmenter] Chunk '{}...' not found at offset {}, using fallback position {}",
                        chunk.substring(0, Math.min(20, chunk.length())), searchFrom, start);
            }
            int end = Math.min(start + chunk.length(), maskedText.length());
            segments.add(new Segment("T" + (i + 1), chunk, start, end));
            searchFrom = end;
        }

        return segments;
    }
}
