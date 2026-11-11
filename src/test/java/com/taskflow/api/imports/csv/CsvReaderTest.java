package com.taskflow.api.imports.csv;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvReaderTest {

    private static CsvReader.Table read(String text) {
        return CsvReader.read(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void leeCamposEntreComillasConComasSaltosDeLineaYComillasDobladas() {
        CsvReader.Table table = read("""
                Summary,Description,Status
                "Pagos, fase 2","Primera línea
                segunda línea con ""comillas\""\",Done
                Login,,To Do
                """);

        assertThat(table.delimiter()).isEqualTo(',');
        assertThat(table.header()).containsExactly("Summary", "Description", "Status");
        assertThat(table.rows()).containsExactly(
                List.of("Pagos, fase 2", "Primera línea\nsegunda línea con \"comillas\"", "Done"),
                List.of("Login", "", "To Do"));
    }

    @Test
    void detectaPuntoYComaYTabulacionComoSeparador() {
        assertThat(read("Título;Estado\nUna;Lista\n").delimiter()).isEqualTo(';');
        assertThat(read("Título\tEstado\nUna\tLista\n").rows()).containsExactly(List.of("Una", "Lista"));
        // Una coma dentro de comillas no cuenta como separador
        assertThat(read("\"a,b,c\";x\n1;2\n").delimiter()).isEqualTo(';');
    }

    @Test
    void conservaColumnasRepetidasYRellenaLasFilasCortas() {
        CsvReader.Table table = read("Summary,Labels,Labels,Sprint\nUna,backend,urgente\n");

        assertThat(table.header()).containsExactly("Summary", "Labels", "Labels", "Sprint");
        assertThat(table.rows()).containsExactly(List.of("Una", "backend", "urgente", ""));
    }

    @Test
    void saltaLineasEnBlancoYAceptaFinDeLineaDeWindowsYArchivoSinSaltoFinal() {
        CsvReader.Table table = read("Título,Estado\r\n\r\nUna,Lista\r\nDos,Pendiente");

        assertThat(table.rows()).containsExactly(List.of("Una", "Lista"), List.of("Dos", "Pendiente"));
    }

    @Test
    void quitaLaMarcaDeUtf8YLeeWindows1252SiNoEsUtf8() {
        byte[] withBom = ("﻿Título,Estado\nDiseño,Listo\n").getBytes(StandardCharsets.UTF_8);
        assertThat(CsvReader.read(withBom).header()).containsExactly("Título", "Estado");

        byte[] latin = "Título;Descripción\nDiseño;Año nuevo\n".getBytes(Charset.forName("windows-1252"));
        CsvReader.Table table = CsvReader.read(latin);
        assertThat(table.header()).containsExactly("Título", "Descripción");
        assertThat(table.rows()).containsExactly(List.of("Diseño", "Año nuevo"));
    }

    @Test
    void unArchivoVacioNoTieneColumnasNiFilas() {
        CsvReader.Table table = read("");

        assertThat(table.header()).isEmpty();
        assertThat(table.rows()).isEmpty();
    }
}
