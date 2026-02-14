package com.politeai.domain.transform.model;

public enum LockedSpanType {
    EMAIL("EMAIL"),
    URL("URL"),
    ACCOUNT("ACCOUNT"),
    DATE("DATE"),
    TIME("TIME"),
    TIME_HH_MM("TIME"),
    PHONE("PHONE"),
    MONEY("MONEY"),
    UNIT_NUMBER("NUMBER"),
    LARGE_NUMBER("NUMBER"),
    UUID("UUID"),
    FILE_PATH("FILE"),
    ISSUE_TICKET("TICKET"),
    VERSION("VERSION"),
    QUOTED_TEXT("QUOTE"),
    IDENTIFIER("ID"),
    HASH_COMMIT("HASH"),
    SEMANTIC("NAME");

    private final String placeholderPrefix;

    LockedSpanType(String placeholderPrefix) {
        this.placeholderPrefix = placeholderPrefix;
    }

    public String placeholderPrefix() {
        return placeholderPrefix;
    }
}
