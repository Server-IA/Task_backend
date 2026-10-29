package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.UpdateModuleRequest;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectModule;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.mapper.PlanningMapper;
import com.taskflow.api.repository.ProjectModuleRepository;
import com.taskflow.api.service.ActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Editar las fechas de un módulo: quitar las dos, solo una, y que la objetivo no quede antes del inicio.
 */
@ExtendWith(MockitoExtension.class)
class ModuleServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private ProjectModuleRepository moduleRepository;
    @Mock
    private ActivityService activityService;
    @Mock
    private PlanningSupport support;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private PlanningMapper mapper;

    @InjectMocks
    private ModuleServiceImpl moduleService;

    private Project project;
    private ProjectModule module;
    private final LocalDate start = LocalDate.of(2026, 11, 2);
    private final LocalDate target = LocalDate.of(2026, 11, 30);

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        module = new ProjectModule();
        module.setId(UUID.randomUUID());
        module.setProject(project);
        module.setName("Pagos");
        module.setStartDate(start);
        module.setTargetDate(target);
        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(moduleRepository.findByIdAndProjectId(module.getId(), project.getId()))
                .thenReturn(Optional.of(module));
        lenient().when(moduleRepository.saveAndFlush(any(ProjectModule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void update(UpdateModuleRequest request) {
        moduleService.update(SLUG, project.getId(), module.getId(), request);
    }

    @Test
    void quitaSoloElInicio() {
        update(new UpdateModuleRequest(null, null, null, null, null, true, null));

        assertThat(module.getStartDate()).isNull();
        assertThat(module.getTargetDate()).isEqualTo(target);
    }

    @Test
    void quitaSoloLaFechaObjetivo() {
        update(new UpdateModuleRequest(null, null, null, null, null, null, true));

        assertThat(module.getStartDate()).isEqualTo(start);
        assertThat(module.getTargetDate()).isNull();
    }

    @Test
    void clearDatesSigueQuitandoLasDos() {
        update(new UpdateModuleRequest(null, null, null, null, true));

        assertThat(module.getStartDate()).isNull();
        assertThat(module.getTargetDate()).isNull();
    }

    @Test
    void quitarUnaYCambiarLaOtraALaVez() {
        update(new UpdateModuleRequest(null, null, null, target.plusDays(5), null, true, null));

        assertThat(module.getStartDate()).isNull();
        assertThat(module.getTargetDate()).isEqualTo(target.plusDays(5));
    }

    @Test
    void laFechaObjetivoNoPuedeQuedarAntesDelInicio() {
        assertThatThrownBy(() -> update(new UpdateModuleRequest(null, null, null, start.minusDays(1), null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> update(new UpdateModuleRequest(null, null, target.plusDays(1), null, null)))
                .isInstanceOf(BadRequestException.class);
        // Nada cambió
        assertThat(module.getStartDate()).isEqualTo(start);
        assertThat(module.getTargetDate()).isEqualTo(target);
        verify(moduleRepository, never()).saveAndFlush(any());
    }

    @Test
    void enviarUnaFechaYPedirQuitarlaEsUnError() {
        assertThatThrownBy(() -> update(new UpdateModuleRequest(null, null, start.plusDays(1), null, null, true,
                null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("quitarla");
        assertThatThrownBy(() -> update(new UpdateModuleRequest(null, null, null, target, true)))
                .isInstanceOf(BadRequestException.class);
        assertThat(module.getStartDate()).isEqualTo(start);
    }

    @Test
    void aceptaClearEndDateComoNombreAlternativo() {
        UpdateModuleRequest request = JsonMapper.builder().build()
                .readValue("{\"clearEndDate\": true}", UpdateModuleRequest.class);

        assertThat(request.clearTargetDate()).isTrue();
    }
}
