package com.minigame.platform.game.domain.liar;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

public final class TextNormalizer {
    private TextNormalizer() {
    }

    public static String normalize(String value) {
        var normalized = Normalizer.normalize(Objects.requireNonNull(value, "value"), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        var result = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            if (!Character.isWhitespace(codePoint) && !isPunctuation(codePoint)) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private static boolean isPunctuation(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                 Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }
}
