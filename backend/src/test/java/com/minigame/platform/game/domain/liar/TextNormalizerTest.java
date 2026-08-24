package com.minigame.platform.game.domain.liar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {

    @Test
    void normalizes_compatibility_characters_case_whitespace_and_general_punctuation() {
        assertThat(TextNormalizer.normalize(" Ｆｏｏ,\tBar! ")).isEqualTo("foobar");
    }
}
