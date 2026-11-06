package com.taskflow.api.util;

import com.taskflow.api.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Los nombres que escribe una persona se guardan sin espacios de más. */
class HumanNamesTest {

    @Test
    void recortaLosBordesYColapsaLosEspaciosDeAdentro() {
        assertThat(HumanNames.clean("  Ana    Núñez  ")).isEqualTo("Ana Núñez");
    }

    @Test
    void tabulacionesSaltosDeLineaYEspaciosDurosCuentanComoUnEspacio() {
        assertThat(HumanNames.clean("Equipo\t\tDiseño\n Web ")).isEqualTo("Equipo Diseño Web");
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

    @Test
    void losEspaciosInvisiblesTambienCuentan() {
        assertThat(HumanNames.clean("​Ana⁠﻿ Núñez​")).isEqualTo("Ana Núñez");
        // Los de unión arman emojis compuestos (mujer + ZWJ + computador): se quedan
        String emoji = "👩‍💻";
        assertThat(HumanNames.clean("Equipo " + emoji)).isEqualTo("Equipo " + emoji);
    }

    @Test
    void requiredDevuelveElNombreLimpio() {
        assertThat(HumanNames.required("  Ana   Núñez ", "fullName")).isEqualTo("Ana Núñez");
    }

    @Test
    void requiredRechazaLosNombresQueQuedanVacios() {
        // @NotBlank e isBlank() solo ven los espacios de Java: estos pasaban y el nombre se guardaba vacío
        for (String name : List.of(" ", " ", " ", "\u0085", "   \t", "​", "﻿", "")) {
            assertThatThrownBy(() -> HumanNames.required(name, "name"))
                    .as("%s", name.codePoints().mapToObj(Integer::toHexString).toList())
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("El nombre no puede estar vacío")
                    .satisfies(ex -> assertThat(((BadRequestException) ex).getErrors()).containsKey("name"));
        }
    }
}
