package com.politeai.infrastructure.ai.preprocessing;

import com.politeai.domain.transform.model.LockedSpan;
import com.politeai.domain.transform.model.LockedSpanType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts locked spans (dates, times, numbers, emails, URLs, etc.) from Korean text.
 * These spans must be preserved exactly through the LLM transformation.
 *
 * Patterns are applied in priority order; overlapping matches keep the longer one.
 */
@Component
public class LockedSpanExtractor {

    private record PatternEntry(Pattern pattern, LockedSpanType type) {}

    /**
     * Patterns in priority order (higher priority = earlier in list).
     */
    private static final List<PatternEntry> PATTERNS = List.of(
            // 1. Email
            new PatternEntry(
                    Pattern.compile("[\\w.+\\-]+@[\\w\\-]+\\.[\\w.]+"),
                    LockedSpanType.EMAIL
            ),
            // 2. URL
            new PatternEntry(
                    Pattern.compile("(?:https?://|www\\.)[\\w\\-.~:/?#\\[\\]@!$&'()*+,;=%]+"),
                    LockedSpanType.URL
            ),
            // 3. Phone number (before account to avoid false matches)
            new PatternEntry(
                    Pattern.compile("0\\d{1,2}[\\-.]\\d{3,4}[\\-.]\\d{4}"),
                    LockedSpanType.PHONE
            ),
            // 4. Account number (2-6 digits - 2-6 digits - 4-12 digits)
            new PatternEntry(
                    Pattern.compile("\\d{2,6}-\\d{2,6}-\\d{4,12}"),
                    LockedSpanType.ACCOUNT
            ),
            // 5. Korean date ("2024년 2월 4일", "2월 4일", "2024.02.04")
            new PatternEntry(
                    Pattern.compile("(?:\\d{2,4}년\\s*)?\\d{1,2}월\\s*\\d{1,2}일|\\d{2,4}년\\s*\\d{1,2}월|\\d{4}[./\\-]\\d{1,2}[./\\-]\\d{1,2}"),
                    LockedSpanType.DATE
            ),
            // 6. Korean time ("12~3시", "오후 2시~5시", "새벽 5~7시")
            new PatternEntry(
                    Pattern.compile("(?:오전|오후|새벽|저녁|밤)?\\s*\\d{1,2}(?:시\\s*\\d{1,2}분?)?(?:\\s*~\\s*\\d{1,2}(?:시(?:\\s*\\d{1,2}분?)?)?)?(?:시|분)"),
                    LockedSpanType.TIME
            ),
            // 7. HH:MM
            new PatternEntry(
                    Pattern.compile("\\d{1,2}:\\d{2}"),
                    LockedSpanType.TIME_HH_MM
            ),
            // 8. Money ("10만원", "150,000원")
            new PatternEntry(
                    Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?\\s*(?:만\\s*)?원"),
                    LockedSpanType.MONEY
            ),
            // 9. Numbers with units ("6자리", "3개", "10%")
            new PatternEntry(
                    Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?\\s*(?:자리|개|건|명|장|통|호|층|평|kg|cm|mm|km|%|주|일|개월|년|시간|분|초)"),
                    LockedSpanType.UNIT_NUMBER
            ),
            // 10. Large standalone numbers (5+ digits or comma-formatted)
            new PatternEntry(
                    Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d{5,}"),
                    LockedSpanType.LARGE_NUMBER
            ),
            // 11. UUID
            new PatternEntry(
                    Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
                    LockedSpanType.UUID
            ),
            // 12. File path / filename with extension (case-insensitive)
            new PatternEntry(
                    Pattern.compile("(?:[\\w./\\\\-]+/)?[\\w.-]+\\.(?:pdf|doc|docx|xls|xlsx|ppt|pptx|csv|txt|md|json|xml|yaml|yml|html|css|js|ts|tsx|jsx|java|py|rb|go|rs|cpp|c|h|hpp|sh|bat|sql|log|zip|tar|gz|rar|7z|png|jpg|jpeg|gif|svg|mp4|mp3|wav|avi|exe|app|msi|dmg|apk|ipa|iso|img|bak|cfg|ini|env|toml|lock|pid)\\b", Pattern.CASE_INSENSITIVE),
                    LockedSpanType.FILE_PATH
            ),
            // 13. Issue/ticket references (#123, JIRA-456)
            new PatternEntry(
                    Pattern.compile("#\\d{1,6}|[A-Z]{2,10}-\\d{1,6}"),
                    LockedSpanType.ISSUE_TICKET
            ),
            // 14. Version numbers (v2.1.0, 3.14.1)
            new PatternEntry(
                    Pattern.compile("v?\\d{1,4}\\.\\d{1,4}(?:\\.\\d{1,4})?"),
                    LockedSpanType.VERSION
            ),
            // 15. Quoted text (2-60 chars inside matched quotes — same type open/close)
            new PatternEntry(
                    Pattern.compile("\"([^\"]{2,60})\"|'([^']{2,60})'|\u201C([^\u201C\u201D]{2,60})\u201D|\u2018([^\u2018\u2019]{2,60})\u2019"),
                    LockedSpanType.QUOTED_TEXT
            ),
            // 16. Identifiers: camelCase, snake_case, PascalCase (>=4 chars), fn()
            new PatternEntry(
                    Pattern.compile("\\b(?:[a-z][a-zA-Z0-9]*[A-Z][a-zA-Z0-9]*|[A-Z][a-z][a-zA-Z0-9]{2,}|[a-z]+(?:_[a-z]+)+)(?:\\(\\))?\\b"),
                    LockedSpanType.IDENTIFIER
            ),
            // 17. Git commit hashes (7-40 hex chars)
            new PatternEntry(
                    Pattern.compile("\\b[0-9a-f]{7,40}\\b"),
                    LockedSpanType.HASH_COMMIT
            )
    );

    /**
     * Extract all locked spans from the given text.
     * Overlapping matches are resolved by keeping the longer match.
     *
     * @param text the input text
     * @return list of non-overlapping locked spans, sorted by start position
     */
    public List<LockedSpan> extract(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // Collect all raw matches
        List<RawMatch> rawMatches = new ArrayList<>();
        for (PatternEntry entry : PATTERNS) {
            Matcher matcher = entry.pattern.matcher(text);
            while (matcher.find()) {
                rawMatches.add(new RawMatch(
                        matcher.start(),
                        matcher.end(),
                        matcher.group(),
                        entry.type
                ));
            }
        }

        // Sort by start position, then by length descending (longer first)
        rawMatches.sort(Comparator
                .comparingInt(RawMatch::start)
                .thenComparingInt(m -> -(m.end - m.start)));

        // Remove overlapping matches (keep the longer one)
        List<RawMatch> resolved = resolveOverlaps(rawMatches);

        // Convert to LockedSpan with index and placeholder
        List<LockedSpan> spans = new ArrayList<>();
        for (int i = 0; i < resolved.size(); i++) {
            RawMatch m = resolved.get(i);
            spans.add(new LockedSpan(
                    i,
                    m.text,
                    "{{LOCKED_" + i + "}}",
                    m.type,
                    m.start,
                    m.end
            ));
        }

        return spans;
    }

    private List<RawMatch> resolveOverlaps(List<RawMatch> sorted) {
        List<RawMatch> result = new ArrayList<>();
        int lastEnd = -1;

        for (RawMatch match : sorted) {
            if (match.start >= lastEnd) {
                // No overlap
                result.add(match);
                lastEnd = match.end;
            } else if (match.end > lastEnd) {
                // Partial overlap — skip (the earlier, longer match was already kept)
            }
            // Fully contained — skip
        }

        return result;
    }

    private record RawMatch(int start, int end, String text, LockedSpanType type) {}
}
