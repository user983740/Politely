package com.politeai.infrastructure.ai.preprocessing;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Normalizes input text before transformation:
 * - Unicode NFC normalization
 * - Invisible/control character removal
 * - Whitespace normalization (collapse runs, trim)
 */
@Component
public class TextNormalizer {

    // Zero-width and invisible Unicode characters
    private static final Pattern INVISIBLE_CHARS = Pattern.compile(
            "[\\u200B\\u200C\\u200D\\uFEFF\\u00AD\\u2060\\u180E]"
    );

    // Control characters except common whitespace (\n, \r, \t)
    private static final Pattern CONTROL_CHARS = Pattern.compile(
            "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"
    );

    // Consecutive spaces (not newlines) → single space
    private static final Pattern MULTIPLE_SPACES = Pattern.compile("[ \\t]{2,}");

    // 3+ consecutive newlines → 2 newlines
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\\n{3,}");

    /**
     * Normalize the input text.
     *
     * @param text raw user input
     * @return normalized text
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 1. Unicode NFC normalization
        String result = Normalizer.normalize(text, Normalizer.Form.NFC);

        // 2. Remove invisible characters
        result = INVISIBLE_CHARS.matcher(result).replaceAll("");

        // 3. Remove control characters (except \n, \r, \t)
        result = CONTROL_CHARS.matcher(result).replaceAll("");

        // 4. Normalize \r\n to \n
        result = result.replace("\r\n", "\n").replace("\r", "\n");

        // 5. Collapse multiple spaces/tabs to single space
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");

        // 6. Collapse 3+ newlines to 2
        result = EXCESSIVE_NEWLINES.matcher(result).replaceAll("\n\n");

        // 7. Trim
        result = result.strip();

        return result;
    }
}
