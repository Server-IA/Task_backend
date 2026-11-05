package com.taskflow.api.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Los nombres que escribe una persona se guardan sin espacios de más. */
class HumanNamesTest {

    @Test
    void recortaLosBordesYColapsaLosEspaciosDeAdentro() {
        assertThat(HumanNames.clean("  Ana    Núñez  ")).isEqualTo("Ana Núñez");
    }

    @Test
    void tabulacionesSaltosDeLineaYEspaciosDurosCuentanComoUnEspacio() {
        assertThat(HumanNames.clean("Equipo\t\tDiseño\n\u00A0Web\u00A0")).isEqualTo("Equipo Diseño Web");
    }

    @Test
    void unNombreLimpioQuedaIgual() {
        assertThat(HumanNames.clean("Portal de clientes")).isEqualTo("Portal de clientes");
    }

    @Test
    void soloEspaciosQuedaVacioYNullSigueNull() {
        assertThat(HumanNames.clean(" \t ")).isEmpty();
        assertThat(HumanNames.clean(null)).isNull();
    }
}
