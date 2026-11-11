package com.taskflow.api.imports.text;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AdfToMarkdownTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String convert(String content) {
        JsonNode document = JSON.readTree("{\"type\":\"doc\",\"version\":1,\"content\":[" + content + "]}");
        return AdfToMarkdown.convert(document);
    }

    private static String text(String value, String... marks) {
        String list = marks.length == 0 ? "" : ",\"marks\":[" + String.join(",", marks) + "]";
        return "{\"type\":\"text\",\"text\":\"" + value + "\"" + list + "}";
    }

    private static String paragraph(String... inline) {
        return "{\"type\":\"paragraph\",\"content\":[" + String.join(",", inline) + "]}";
    }

    @Test
    void parrafosTitulosYFormatoEnLinea() {
        String markdown = convert(
                "{\"type\":\"heading\",\"attrs\":{\"level\":2},\"content\":[" + text("Tokens") + "]},"
                        + paragraph(text("El access token dura "), text("15 minutos", "{\"type\":\"strong\"}"), text(" y va en "),
                        text("/auth", "{\"type\":\"code\"}"), text(". Ver "),
                        text("la guía", "{\"type\":\"link\",\"attrs\":{\"href\":\"https://ejemplo.test/guia\"}}"), text(".")));

        assertThat(markdown).isEqualTo("""
                ## Tokens

                El access token dura **15 minutos** y va en `/auth`. Ver [la guía](https://ejemplo.test/guia).""");
    }

    @Test
    void losMarcadoresQuedanPegadosAlTextoAunqueTraigaEspaciosEnLosBordes() {
        assertThat(convert(paragraph(text("antes"), text(" importante ", "{\"type\":\"strong\"}"), text("después"))))
                .isEqualTo("antes **importante** después");
    }

    @Test
    void listasConUnaListaAnidadaYListaNumeradaQueNoEmpiezaEnUno() {
        String nested = "{\"type\":\"bulletList\",\"content\":[{\"type\":\"listItem\",\"content\":[" + paragraph(text("hija")) + "]}]}";
        String markdown = convert(
                "{\"type\":\"bulletList\",\"content\":["
                        + "{\"type\":\"listItem\",\"content\":[" + paragraph(text("uno")) + "," + nested + "]},"
                        + "{\"type\":\"listItem\",\"content\":[" + paragraph(text("dos")) + "]}]},"
                        + "{\"type\":\"orderedList\",\"attrs\":{\"order\":3},\"content\":["
                        + "{\"type\":\"listItem\",\"content\":[" + paragraph(text("tres")) + "]},"
                        + "{\"type\":\"listItem\",\"content\":[" + paragraph(text("cuatro")) + "]}]}");

        assertThat(markdown).isEqualTo("""
                - uno
                  - hija
                - dos

                3. tres
                4. cuatro""");
    }

    @Test
    void listaDeTareasCodigoCitaYLinea() {
        String markdown = convert(
                "{\"type\":\"taskList\",\"content\":["
                        + "{\"type\":\"taskItem\",\"attrs\":{\"state\":\"DONE\"},\"content\":[" + text("Pruebas") + "]},"
                        + "{\"type\":\"taskItem\",\"attrs\":{\"state\":\"TODO\"},\"content\":[" + text("Revisar") + "]}]},"
                        + "{\"type\":\"codeBlock\",\"attrs\":{\"language\":\"java\"},\"content\":[" + text("int a = 1;\\nint b = 2;") + "]},"
                        + "{\"type\":\"blockquote\",\"content\":[" + paragraph(text("una cita")) + "]},"
                        + "{\"type\":\"rule\"}");

        assertThat(markdown).isEqualTo("""
                - [x] Pruebas
                - [ ] Revisar

                ```java
                int a = 1;
                int b = 2;
                ```

                > una cita

                ---""");
    }

    @Test
    void tablaConEncabezadoYCeldasDeUnaLinea() {
        String cell = "{\"type\":\"%s\",\"content\":[" + paragraph(text("%s")) + "]}";
        String markdown = convert("{\"type\":\"table\",\"content\":["
                + "{\"type\":\"tableRow\",\"content\":[" + cell.formatted("tableHeader", "Campo") + "," + cell.formatted("tableHeader", "Valor") + "]},"
                + "{\"type\":\"tableRow\",\"content\":[" + cell.formatted("tableCell", "a|b") + "," + cell.formatted("tableCell", "1") + "]}]}");

        assertThat(markdown).isEqualTo("""
                | Campo | Valor |
                | --- | --- |
                | a\\|b | 1 |""");
    }

    @Test
    void mencionesSaltosEmojisEnlacesYEstados() {
        String markdown = convert(paragraph(
                "{\"type\":\"mention\",\"attrs\":{\"id\":\"abc\",\"text\":\"@Ana Torres\"}}", text(" revisa esto"),
                "{\"type\":\"hardBreak\"}",
                "{\"type\":\"emoji\",\"attrs\":{\"shortName\":\":tada:\",\"text\":\"🎉\"}}", text(" "),
                "{\"type\":\"inlineCard\",\"attrs\":{\"url\":\"https://ejemplo.test/PORT-1\"}}", text(" "),
                "{\"type\":\"status\",\"attrs\":{\"text\":\"EN PRUEBAS\",\"color\":\"blue\"}}"));

        assertThat(markdown).isEqualTo("@Ana Torres revisa esto  \n🎉 <https://ejemplo.test/PORT-1> `EN PRUEBAS`");
    }

    @Test
    void unNodoDesconocidoAportaSuTextoYLosAdjuntosQuedanConSuNombre() {
        String markdown = convert(
                "{\"type\":\"algoNuevo\",\"content\":[" + paragraph(text("contenido que no se pierde")) + "]},"
                        + "{\"type\":\"mediaSingle\",\"content\":[{\"type\":\"media\",\"attrs\":{\"id\":\"x\",\"type\":\"file\",\"alt\":\"captura.png\"}}]}");

        assertThat(markdown).isEqualTo("contenido que no se pierde\n\n*(adjunto: captura.png)*");
    }

    @Test
    void sinDocumentoOVacioDevuelveCadenaVacia() {
        assertThat(AdfToMarkdown.convert(null)).isEmpty();
        assertThat(AdfToMarkdown.convert(JSON.readTree("null"))).isEmpty();
        assertThat(convert("")).isEmpty();
    }
}
