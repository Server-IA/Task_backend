package com.taskflow.api.storage;

import com.taskflow.api.dto.response.StorageReportResponse.FileGroup;
import com.taskflow.api.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static com.taskflow.api.storage.FileTypes.MB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Qué archivos se aceptan: la extensión decide el tipo, el declarado debe corresponderle y cada tipo tiene su límite.
 */
class FileTypesTest {

    @ParameterizedTest
    @CsvSource({
            "captura.png, image/png, image/png",
            "foto.JPG, image/jpeg, image/jpeg",
            "foto.jpeg, image/jpg, image/jpeg",
            "anim.gif, image/gif, image/gif",
            "hero.webp, image/webp, image/webp",
            "requisitos.pdf, application/pdf, application/pdf",
            "textos.docx, application/vnd.openxmlformats-officedocument.wordprocessingml.document, "
                    + "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "datos.xlsx, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, "
                    + "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "kickoff.pptx, application/vnd.openxmlformats-officedocument.presentationml.presentation, "
                    + "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "notas.txt, text/plain, text/plain",
            "README.md, '', text/markdown",
            "export.csv, application/vnd.ms-excel, text/csv",
            "iconos.zip, application/x-zip-compressed, application/zip",
    })
    void aceptaLosTiposPermitidosYGuardaElTipoCanonico(String name, String declared, String stored) {
        assertThat(FileTypes.validate(name, declared, 1000, false).contentType()).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"instalador.exe", "pagina.html", "pagina.htm", "logo.svg", "script.js", "sin-extension",
            "archivo.", "macro.docm", "app.msi", "run.bat"})
    void rechazaEjecutablesHtmlSvgYDesconocidos(String name) {
        assertThatThrownBy(() -> FileTypes.validate(name, "application/octet-stream", 1000, false))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(FileTypes.NOT_ALLOWED);
    }

    @Test
    void elTipoDeclaradoDebeCorresponderALaExtension() {
        // Un HTML renombrado a .png con su tipo real
        assertThatThrownBy(() -> FileTypes.validate("foto.png", "text/html", 1000, false))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(FileTypes.TYPE_MISMATCH);
        assertThatThrownBy(() -> FileTypes.validate("logo.png", "image/svg+xml", 1000, false))
                .hasMessage(FileTypes.TYPE_MISMATCH);
        // Sin tipo (o genérico) vale el de la extensión
        assertThat(FileTypes.validate("foto.png", null, 1000, false).contentType()).isEqualTo("image/png");
        assertThat(FileTypes.validate("foto.png", "application/octet-stream", 1000, false).contentType())
                .isEqualTo("image/png");
        assertThat(FileTypes.validate("foto.png", "IMAGE/PNG; charset=binary", 1000, false).contentType())
                .isEqualTo("image/png");
    }

    @Test
    void imagenesHasta5MbYLoDemasHasta10Mb() {
        assertThat(FileTypes.validate("a.png", "image/png", 5 * MB, false)).isNotNull();
        assertThatThrownBy(() -> FileTypes.validate("a.png", "image/png", 5 * MB + 1, false))
                .hasMessage("Pesa 5 MB y el límite es 5 MB.");
        assertThatThrownBy(() -> FileTypes.validate("hero.png", "image/png", (long) (7.5 * MB), false))
                .hasMessage("Pesa 7,5 MB y el límite es 5 MB.");
        assertThat(FileTypes.validate("a.pdf", "application/pdf", 10 * MB, false)).isNotNull();
        assertThatThrownBy(() -> FileTypes.validate("a.zip", "application/zip", 38 * MB, false))
                .hasMessage("Pesa 38 MB y el límite es 10 MB.");
        assertThatThrownBy(() -> FileTypes.validate("a.txt", "text/plain", 0, false))
                .hasMessage("El archivo está vacío.");
    }

    @Test
    void enLosTextosSoloImagenes() {
        assertThat(FileTypes.validate("pegada.png", "image/png", 100, true).isImage()).isTrue();
        assertThatThrownBy(() -> FileTypes.validate("requisitos.pdf", "application/pdf", 100, true))
                .hasMessage(FileTypes.ONLY_IMAGES);
        assertThatThrownBy(() -> FileTypes.validate("logo.svg", "image/svg+xml", 100, true))
                .hasMessage(FileTypes.ONLY_IMAGES);
    }

    @Test
    void laFotoDePerfilEsPngJpgOWebpDeHasta5Mb() {
        assertThat(FileTypes.validateAvatar("image/webp", 40_000).extension()).isEqualTo("webp");
        assertThat(FileTypes.validateAvatar("image/jpeg", 40_000).extension()).isEqualTo("jpg");
        assertThatThrownBy(() -> FileTypes.validateAvatar("image/gif", 40_000)).hasMessage(FileTypes.AVATAR_TYPES);
        assertThatThrownBy(() -> FileTypes.validateAvatar("image/svg+xml", 40_000))
                .hasMessage(FileTypes.AVATAR_TYPES);
        assertThatThrownBy(() -> FileTypes.validateAvatar("image/png", 6 * MB))
                .hasMessage("Pesa 6 MB y el límite es 5 MB.");
    }

    @Test
    void elContenidoDebeEmpezarComoSuTipo() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        byte[] html = "<!doctype html><script>".getBytes(StandardCharsets.US_ASCII);
        byte[] webp = "RIFF\u0010\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        byte[] wav = "RIFF\u0010\u0000\u0000\u0000WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);
        byte[] zip = {'P', 'K', 3, 4, 20, 0};

        assertThat(FileTypes.contentMatches("image/png", png)).isTrue();
        assertThat(FileTypes.contentMatches("image/png", html)).isFalse();
        assertThat(FileTypes.contentMatches("image/jpeg", png)).isFalse();
        assertThat(FileTypes.contentMatches("image/webp", webp)).isTrue();
        assertThat(FileTypes.contentMatches("image/webp", wav)).isFalse();
        assertThat(FileTypes.contentMatches("application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                .isTrue();
        assertThat(FileTypes.contentMatches(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip)).isTrue();
        assertThat(FileTypes.contentMatches("application/zip", html)).isFalse();
        // Los de texto no se comprueban
        assertThat(FileTypes.contentMatches("text/csv", html)).isTrue();
        // Un tipo que no se acepta nunca coincide
        assertThat(FileTypes.contentMatches("text/html", html)).isFalse();
        assertThat(FileTypes.contentMatches("image/png", new byte[0])).isFalse();
    }

    @Test
    void limpiaElNombre() {
        assertThat(FileTypes.cleanName("C:\\fakepath\\mockup perfil.png")).isEqualTo("mockup perfil.png");
        assertThat(FileTypes.cleanName("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(FileTypes.cleanName("tab\tbreak\n.pdf")).isEqualTo("tabbreak.pdf");
        assertThat(FileTypes.cleanName("  ")).isEqualTo("archivo");
        String longName = "a".repeat(300) + ".docx";
        assertThat(FileTypes.cleanName(longName)).hasSize(255).endsWith(".docx");
    }

    @Test
    void tamanosComoEnLaInterfaz() {
        assertThat(FileTypes.formatSize(512)).isEqualTo("512 B");
        assertThat(FileTypes.formatSize(86 * 1024)).isEqualTo("86 KB");
        assertThat(FileTypes.formatSize((long) (2.4 * MB))).isEqualTo("2,4 MB");
        assertThat(FileTypes.formatSize(10 * MB)).isEqualTo("10 MB");
        assertThat(FileTypes.formatSize(2048 * MB)).isEqualTo("2 GB");
        assertThat(FileTypes.formatSize((long) (1.2 * 1024 * MB))).isEqualTo("1,2 GB");
    }

    @Test
    void agrupaPorTipoParaElAlmacenamiento() {
        assertThat(FileTypes.group("image/webp")).isEqualTo(FileGroup.IMAGES);
        assertThat(FileTypes.group("application/pdf")).isEqualTo(FileGroup.DOCUMENTS);
        assertThat(FileTypes.group("text/csv")).isEqualTo(FileGroup.DOCUMENTS);
        assertThat(FileTypes.group("application/zip")).isEqualTo(FileGroup.OTHER);
        assertThat(FileTypes.group("application/x-desconocido")).isEqualTo(FileGroup.OTHER);
    }
}
