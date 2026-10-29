package com.taskflow.api.util;

import com.taskflow.api.util.DescriptionTasks.Toggle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Los extractos y títulos recortados no parten un emoji en dos (QA de la API). */
class TextCutTest {

    private static final String EMOJI = "😀";

    /** Sin pares sustitutos sueltos: cada mitad de un emoji va acompañada de la otra. */
    private static boolean wellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void noParteUnEmojiAlCortar() {
        String text = "ab" + EMOJI + "cd";
        assertThat(TextCut.prefix(text, 3)).isEqualTo("ab");
        assertThat(TextCut.prefix(text, 4)).isEqualTo("ab" + EMOJI);
        assertThat(TextCut.prefix(text, 2)).isEqualTo("ab");
        assertThat(TextCut.prefix(text, 100)).isSameAs(text);
        assertThat(TextCut.prefix(null, 3)).isNull();
        assertThat(TextCut.prefix(EMOJI, 1)).isEmpty();
    }

    @Test
    void elExtractoDeUnComentarioConEmojisQuedaBienFormado() {
        // 199 letras y emojis: el corte en 200 caía en medio del primer emoji y la bandeja recibía «\ud83d…»
        String body = "b".repeat(199) + EMOJI.repeat(20);
        String excerpt = MentionParser.excerpt(body, 200);
        assertThat(wellFormed(excerpt)).isTrue();
        assertThat(excerpt).isEqualTo("b".repeat(199) + "…");
    }

    @Test
    void laCasillaLargaConEmojisQuedaBienFormadaEnElHistorial() {
        String text = "a".repeat(DescriptionTasks.MAX_TEXT - 2) + EMOJI.repeat(5);
        DescriptionTasks.Diff diff = DescriptionTasks.diff("- [ ] " + text, "- [x] " + text);
        assertThat(diff.toggles()).hasSize(1);
        Toggle toggle = diff.toggles().getFirst();
        assertThat(wellFormed(toggle.text())).isTrue();
        assertThat(toggle.text()).isEqualTo("a".repeat(DescriptionTasks.MAX_TEXT - 2) + "…");
    }
}
