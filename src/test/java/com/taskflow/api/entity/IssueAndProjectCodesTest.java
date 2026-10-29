package com.taskflow.api.entity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Códigos que se calculan en la entidad: los de las dependencias abiertas (que llegan de la base de datos separados por
 * coma) y el código que recibirá la próxima tarea del proyecto.
 */
class IssueAndProjectCodesTest {

    @Test
    void losCodigosDeLasDependenciasSeSeparanEnUnaLista() {
        Issue issue = new Issue();
        ReflectionTestUtils.setField(issue, "blockedByCodesText", "AMC-4,WEB-2");
        ReflectionTestUtils.setField(issue, "blockingCodesText", "WEB-7");

        assertThat(issue.getBlockedByCodes()).containsExactly("AMC-4", "WEB-2");
        assertThat(issue.getBlockingCodes()).containsExactly("WEB-7");
    }

    @Test
    void sinDependenciasLasListasVienenVacias() {
        Issue issue = new Issue();

        assertThat(issue.getBlockedByCodes()).isEmpty();
        assertThat(issue.getBlockingCodes()).isEmpty();
    }

    @Test
    void elProximoCodigoEsElSiguienteAlContador() {
        Project project = new Project();
        project.setIdentifier("WEB");
        ReflectionTestUtils.setField(project, "issueCounter", 25);

        assertThat(project.getNextIssueCode()).isEqualTo("WEB-26");
    }
}
