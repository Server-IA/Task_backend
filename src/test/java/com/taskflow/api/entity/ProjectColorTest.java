package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.ProjectColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectColorTest {

    @Test
    void elPrimerProyectoEsTurquesa() {
        assertThat(ProjectColor.leastUsed(List.of())).isEqualTo(ProjectColor.TEAL);
    }

    @Test
    void elMenosUsadoYEntreEmpatadosElPrimeroDeLaPaleta() {
        assertThat(ProjectColor.leastUsed(List.of(ProjectColor.TEAL))).isEqualTo(ProjectColor.AMBER);
        assertThat(ProjectColor.leastUsed(List.of(ProjectColor.TEAL, ProjectColor.BLUE))).isEqualTo(ProjectColor.AMBER);
        // Con toda la paleta usada una vez y el turquesa dos, sigue el ámbar
        List<ProjectColor> used = new ArrayList<>(Arrays.asList(ProjectColor.values()));
        used.add(ProjectColor.TEAL);
        assertThat(ProjectColor.leastUsed(used)).isEqualTo(ProjectColor.AMBER);
    }

    @Test
    void ignoraLosNulos() {
        assertThat(ProjectColor.leastUsed(Arrays.asList(null, ProjectColor.TEAL))).isEqualTo(ProjectColor.AMBER);
    }
}
