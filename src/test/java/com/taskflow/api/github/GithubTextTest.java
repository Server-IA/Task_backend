package com.taskflow.api.github;

import com.taskflow.api.github.GithubText.Code;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GithubTextTest {

    @Test
    void reconoceElCodigoDeLaTareaEnUnaRamaOUnTitulo() {
        assertThat(GithubText.codes("web-22-rotacion-del-token")).containsExactly(new Code("WEB", 22));
        assertThat(GithubText.codes("feature/WEB-22-rotacion")).containsExactly(new Code("WEB", 22));
        assertThat(GithubText.codes("[API-7] Corrige el total", "Cierra API-7 y WEB-3.")).containsExactly(new Code("API", 7), new Code("WEB", 3));
    }

    @Test
    void noConfundeOtrasCosasConUnCodigo() {
        // Un número suelto, una fecha, una versión o un código pegado a más letras no nombran una tarea
        assertThat(GithubText.codes("release-2026-11", "v2-1", "1234-56", null)).extracting(Code::identifier).doesNotContain("2026", "1234");
        assertThat(GithubText.codes("fix-typo")).isEmpty();
        assertThat(GithubText.codes("WEB-22abc")).isEmpty();
        assertThat(GithubText.codes("xWEB-22")).containsExactly(new Code("XWEB", 22));
    }

    @Test
    void proponeElNombreDeLaRamaDeUnaTarea() {
        assertThat(GithubText.branchName("WEB-22", "Rotación del token de sesión")).isEqualTo("web-22-rotacion-del-token-de-sesion");
        assertThat(GithubText.branchName("WEB-22", "  ¿¡Qué pasa!?  ")).isEqualTo("web-22-que-pasa");
        assertThat(GithubText.branchName("WEB-22", "")).isEqualTo("web-22");
        assertThat(GithubText.branchName("WEB-22", "a".repeat(200))).hasSize(80);
        assertThat(GithubText.branchName("WEB-22", "palabra ".repeat(40))).doesNotEndWith("-").hasSizeLessThanOrEqualTo(80);
        // El nombre propuesto siempre nombra a su tarea y sirve como rama
        assertThat(GithubText.codes(GithubText.branchName("WEB-22", "Añadir ñandú"))).containsExactly(new Code("WEB", 22));
        assertThat(GithubText.validBranch(GithubText.branchName("WEB-22", "Añadir ñandú"))).isTrue();
    }

    @Test
    void elNombreDeLaRamaPuedeLlevarElTipoOSoloElCodigo() {
        assertThat(GithubText.branchName(GithubText.TYPE_CODE_TITLE, "WEB-22", "Rotación del token", false)).isEqualTo("feature/web-22-rotacion-del-token");
        assertThat(GithubText.branchName(GithubText.TYPE_CODE_TITLE, "WEB-22", "El total sale mal", true)).isEqualTo("fix/web-22-el-total-sale-mal");
        assertThat(GithubText.branchName(GithubText.CODE, "WEB-22", "Rotación del token", true)).isEqualTo("web-22");
        assertThat(GithubText.branchName("no-existe", "WEB-22", "Rotación", false)).isEqualTo("web-22-rotacion");
        // Con el tipo por delante sigue nombrando a su tarea y sirviendo como rama
        String typed = GithubText.branchName(GithubText.TYPE_CODE_TITLE, "WEB-22", "Añadir ñandú", false);
        assertThat(GithubText.codes(typed)).containsExactly(new Code("WEB", 22));
        assertThat(GithubText.validBranch(typed)).isTrue();
    }

    @Test
    void sabeSiUnNombreSirveComoRama() {
        assertThat(GithubText.validBranch("feature/web-22_x.1")).isTrue();
        assertThat(GithubText.validBranch("main")).isTrue();
        for (String bad : new String[]{null, "", " ", "con espacio", "a..b", "/a", "a/", ".a", "a.", "a//b", "a.lock", "-a", "a~b", "a:b", "a?b",
                "a\\b", "ñ"}) {
            assertThat(GithubText.validBranch(bad)).as("«%s»", bad).isFalse();
        }
    }

    @Test
    void leeLosDatosQuePideUnWorkflowManual() {
        String yaml = """
                name: Deploy
                on:
                  push:
                    branches: [main]
                  workflow_dispatch:
                    inputs:
                      entorno:   # a dónde
                        description: 'Entorno'
                        required: true
                        type: choice
                        options:
                          - staging
                          - produccion
                      version:
                        description: Versión
                jobs:
                  deploy:
                    runs-on: ubuntu-latest
                """;

        assertThat(GithubText.dispatchInputs(yaml)).containsExactly("entorno", "version");
    }

    @Test
    void unWorkflowManualSinDatosNoPideNada() {
        assertThat(GithubText.dispatchInputs("on: workflow_dispatch\njobs:\n  a:\n    runs-on: x\n")).isEmpty();
        assertThat(GithubText.dispatchInputs("on: [push, workflow_dispatch]\njobs: {}\n")).isEmpty();
        assertThat(GithubText.dispatchInputs("on:\n  workflow_dispatch:\n  push:\njobs: {}\n")).isEmpty();
        assertThat(GithubText.dispatchInputs("on:\r\n  workflow_dispatch: {}\r\njobs: {}\r\n")).isEmpty();
    }

    @Test
    void unWorkflowQueNoSeEjecutaAManoSeDistingue() {
        assertThat(GithubText.dispatchInputs("on:\n  push:\njobs:\n  a:\n    runs-on: x\n")).isNull();
        assertThat(GithubText.dispatchInputs("")).isNull();
        // Nombrarlo en un comentario o dentro de un trabajo no lo hace manual
        assertThat(GithubText.dispatchInputs("# workflow_dispatch\non: push\njobs:\n  a:\n    if: github.event_name == 'workflow_dispatch'\n")).isNull();
    }
}
