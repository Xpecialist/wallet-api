package com.example.wallet.dto;

/** Character rules shared by {@link PlainText}, {@link NoNul} and {@link Username}. */
final class TextRules {
    private TextRules() {}

    /** @return {@code true} if the text contains U+0000, which PostgreSQL cannot store (would be an HTTP 500) */
    static boolean hasNul(String s) {
        return s.indexOf('\u0000') >= 0;
    }

    /**
     * @return {@code true} if the text has no control (Cc), format (Cf, e.g. zero-width, soft hyphen, bidi
     *         override), line/paragraph separator (Zl, Zp) or unpaired surrogate code point; plain spaces are fine
     */
    static boolean isPlain(String s) {
        return s.codePoints().noneMatch(cp -> {
            int t = Character.getType(cp);
            return t == Character.CONTROL || t == Character.FORMAT || t == Character.LINE_SEPARATOR
                    || t == Character.PARAGRAPH_SEPARATOR || t == Character.SURROGATE;
        });
    }

    /** @return {@code true} if the text, ignoring leading and trailing plain spaces, is only ASCII letters, digits, {@code . _ -} */
    static boolean isUsername(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == ' ') from++;
        while (to > from && s.charAt(to - 1) == ' ') to--;
        if (from == to) return false;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
