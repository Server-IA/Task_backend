package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.DailySettingsRequest;
import com.taskflow.api.dto.response.DailySettingsResponse;
import com.taskflow.api.entity.DailySettings;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.repository.DailySettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
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
 * Ajustes de la daily: valores por defecto, bloques visibles y puntos a tratar.
 */
@ExtendWith(MockitoExtension.class)
class DailySettingsServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private DailySettingsRepository settingsRepository;
    @Mock
    private ResourceLookup lookup;

    @InjectMocks
    private DailySettingsServiceImpl service;

    private Project project;
    private User ana;

    @BeforeEach
    void setUp() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setWorkspace(workspace);
        ana = new User();
        ana.setId(UUID.randomUUID());
        ana.setFullName("Ana");
        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(lookup.user(ana.getId())).thenReturn(ana);
        lenient().when(settingsRepository.saveAndFlush(any(DailySettings.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static DailySettingsRequest.Sections allSections() {
        return new DailySettingsRequest.Sections(true, true, true, true, true);
    }

    private DailySettingsResponse save(DailySettingsRequest request) {
        return service.save(SLUG, project.getId(), ana.getId(), request);
    }

    @Test
    void sinAjustesGuardadosDevuelveLosValoresPorDefecto() {
        when(settingsRepository.findByProjectId(project.getId())).thenReturn(Optional.empty());

        DailySettingsResponse settings = service.get(SLUG, project.getId());

        assertThat(settings.sections()).isEqualTo(new DailySettingsResponse.Sections(true, true, true, true, true));
        assertThat(settings.agenda()).isEmpty();
    }

    @Test
    void guardaLosBloquesYLosPuntos() {
        when(settingsRepository.findByProjectId(project.getId())).thenReturn(Optional.empty());

        DailySettingsResponse settings = save(new DailySettingsRequest(
                new DailySettingsRequest.Sections(false, true, true, true, false),
                List.of("  Demo del sprint  ", "Riesgos")));

        assertThat(settings.sections()).isEqualTo(new DailySettingsResponse.Sections(false, true, true, true, false));
        assertThat(settings.agenda()).containsExactly("Demo del sprint", "Riesgos");
    }

    @Test
    void editaLaFilaQueYaExisteEnVezDeCrearOtra() {
        DailySettings existing = new DailySettings(project);
        existing.setAgenda(List.of("Demo"));
        when(settingsRepository.findByProjectId(project.getId())).thenReturn(Optional.of(existing));

        DailySettingsResponse settings = save(new DailySettingsRequest(allSections(), List.of()));

        assertThat(existing.getUpdatedBy()).isSameAs(ana);
        // Un punto que no viene en la lista se quita
        assertThat(settings.agenda()).isEmpty();
        verify(settingsRepository).saveAndFlush(existing);
    }

    @Test
    void rechazaOcultarAyerHoyYBloqueosALaVez() {
        assertThatThrownBy(() -> save(new DailySettingsRequest(
                new DailySettingsRequest.Sections(true, false, false, false, true), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("al menos uno");
        verify(settingsRepository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaUnPuntoHechoSoloDeEspaciosInvisibles() {
        assertThatThrownBy(() -> save(new DailySettingsRequest(allSections(), List.of(" ​"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no puede estar vacío");
    }
}
