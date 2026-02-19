import re
import unicodedata

# Zero-width and invisible Unicode characters
_INVISIBLE_CHARS = re.compile(r"[\u200B\u200C\u200D\uFEFF\u00AD\u2060\u180E]")

# Control characters except common whitespace (\n, \r, \t)
_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]")

# Consecutive spaces (not newlines) -> single space
_MULTIPLE_SPACES = re.compile(r"[ \t]{2,}")

# 3+ consecutive newlines -> 2 newlines
_EXCESSIVE_NEWLINES = re.compile(r"\n{3,}")


def normalize(text: str) -> str:
    """7-step text normalization."""
    if not text:
        return text

    # 1. Unicode NFC normalization
    result = unicodedata.normalize("NFC", text)

    # 2. Remove invisible characters
    result = _INVISIBLE_CHARS.sub("", result)

    # 3. Remove control characters (except \n, \r, \t)
    result = _CONTROL_CHARS.sub("", result)

    # 4. Normalize \r\n to \n
    result = result.replace("\r\n", "\n").replace("\r", "\n")

    # 5. Collapse multiple spaces/tabs to single space
    result = _MULTIPLE_SPACES.sub(" ", result)

    # 6. Collapse 3+ newlines to 2
    result = _EXCESSIVE_NEWLINES.sub("\n\n", result)

    # 7. Trim
    result = result.strip()

    return result
