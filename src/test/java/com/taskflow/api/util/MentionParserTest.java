package com.taskflow.api.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MentionParserTest {

    private final UUID ana = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();

    @Test
    void leeLasMencionesEnOrdenYSinRepetir() {
        String body = "@[Ana Pérez](" + ana + ") revisa esto con @[Luis](" + luis + "). Gracias @[Ana](" + ana + ")";

        assertThat(MentionParser.userIds(body)).containsExactly(ana, luis);
    }

    @Test
    void aceptaIdsEnMayusculas() {
        assertThat(MentionParser.userIds("@[Ana](" + ana.toString().toUpperCase() + ")")).containsExactly(ana);
    }

    @Test
    void ignoraLoQueNoTieneElFormato() {
        String body = "correo ana@empresa.com, @Ana, @[Ana](no-es-un-id), [Ana](" + ana + "), @[](" + luis + ")";

        assertThat(MentionParser.userIds(body)).isEmpty();
        assertThat(MentionParser.userIds(null)).isEmpty();
        assertThat(MentionParser.userIds("")).isEmpty();
    }

    @Test
    void noPasaDelMaximoDeMenciones() {
        String body = IntStream.range(0, MentionParser.MAX_MENTIONS + 10)
                .mapToObj(i -> "@[Persona " + i + "](" + UUID.randomUUID() + ")")
                .collect(Collectors.joining(" "));

        assertThat(MentionParser.userIds(body)).hasSize(MentionParser.MAX_MENTIONS);
    }
}
