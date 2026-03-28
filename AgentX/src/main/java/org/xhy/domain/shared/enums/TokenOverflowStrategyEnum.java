package org.xhy.domain.shared.enums;

/** Supported token overflow handling strategies. */
public enum TokenOverflowStrategyEnum {
    NONE,
    SLIDING_WINDOW,
    SUMMARIZE,
    RELEVANCE_RECALL;

    public static boolean isValid(String value) {
        try {
            TokenOverflowStrategyEnum.valueOf(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static TokenOverflowStrategyEnum fromString(String value) {
        if (value == null) {
            return NONE;
        }

        try {
            return TokenOverflowStrategyEnum.valueOf(value);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
