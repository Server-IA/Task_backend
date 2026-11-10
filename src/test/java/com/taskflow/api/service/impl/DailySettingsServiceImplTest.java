package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.DailySettingsRequest;
import com.taskflow.api.dto.response.DailySettingsResponse;
import com.taskflow.api.entity.DailySettings;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.repository.DailySettingsRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
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
 * Ajustes de la daily: valores por defecto, tiempos por persona, bloques visibles y puntos a tratar.
 */
@ExtendWith(MockitoExtension.class)
class DailySettingsServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private DailySettingsRepository settingsRepository;
    @Mock
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock
    private ResourceLookup lookup;

    @InjectMocks
    private DailySettingsServiceImpl service;

    private Project project;
    private User ana;
    private User luis;

    @BeforeEach
    void setUp() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setWorkspace(workspace);
        ana = user("Ana");
        luis = user("Luis");
        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(lookup.user(ana.getId())).thenReturn(ana);
        lenient().when(workspaceMemberRepository.findAllByWorkspaceIdWithUser(workspace.getId())).thenReturn(List.of(
                new WorkspaceMember(workspace, ana, WorkspaceRole.OWNER),
                new WorkspaceMember(workspace, luis, WorkspaceRole.MEMBER)));
        lenient().when(settingsRepository.saveAndFlush(any(DailySettings.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        return user;
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

        assertThat(settings.secondsPerPerson()).isEqualTo(120);
        assertThat(settings.personTimes()).isEmpty();
        assertThat(settings.sections()).isEqualTo(new DailySettingsResponse.Sections(true, true, true, true, true));
        assertThat(settings.agenda()).isEmpty();
    }

    @Test
    void guardaElTiempoGeneralLosPropiosLosBloquesYLosPuntos() {
        when(settingsRepository.findByProjectId(project.getId())).thenReturn(Optional.empty());

        DailySettingsResponse settings = save(new DailySettingsRequest(
                180,
                List.of(new DailySettingsRequest.PersonTime(luis.getId(), 300)),
                new DailySettingsRequest.Sections(false, true, true, true, false),
                List.of("  Demo del sprint  ", "Riesgos")));

        assertThat(settings.secondsPerPerson()).isEqualTo(180);
        assertThat(settings.personTimes()).containsExactly(new DailySettingsResponse.PersonTime(luis.getId(), 300));
        assertThat(settings.sections()).isEqualTo(new DailySettingsResponse.Sections(false, true, true, true, false));
        assertThat(settings.agenda()).containsExactly("Demo del sprint", "Riesgos");
    }

    @Test
    void unTiempoPropioIgualAlGeneralNoSeGuarda() {
        when(settingsRepository.findByProjectId(project.getId())).thenReturn(Optional.empty());

        DailySettingsResponse settings = save(new DailySettingsRequest(
                120, List.of(new DailySettingsRequest.PersonTime(luis.getId(), 120)), allSections(), null));

        assertThat(settings.personTimes()).isEmpty();
    }

    @Test
    void editaLaFilaQueYaExisteEnVezDeCrearOtra() {
        DailySettings existing = new DailySettings(project);
        existing.setPersonSeconds(Map.of(luis.getId().toString(), 60));
        when(settingsRepository.findByProjectId(project.getId())).thenReturn(Optional.of(existing));

        DailySettingsResponse settings = save(new DailySettingsRequest(90, List.of(), allSections(), List.of()));

        assertThat(existing.getSecondsPerPerson()).isEqualTo(90);
        assertThat(existing.getUpdatedBy()).isSameAs(ana);
        // Quien no viene en la lista vuelve al tiempo general
        assertThat(settings.personTimes()).isEmpty();
        verify(settingsRepository).saveAndFlush(existing);
    }

    @Test
    void rechazaOcultarAyerHoyYBloqueosALaVez() {
        assertThatThrownBy(() -> save(new DailySettingsRequest(
                120, null, new DailySettingsRequest.Sections(true, false, false, false, true), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("al menos uno");
        verify(settingsRepository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaUnTiempoPropioDeAlguienQueNoEsDelWorkspace() {
        assertThatThrownBy(() -> save(new DailySettingsRequest(
                120, List.of(new DailySettingsRequest.PersonTime(UUID.randomUUID(), 60)), allSections(), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no pertenece al workspace");
    }

    @Test
    void rechazaUnPuntoHechoSoloDeEspaciosInvisibles() {
        assertThatThrownBy(() -> save(new DailySettingsRequest(120, null, allSections(), List.of(" ​"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no puede estar vacío");
    }

    @Test
    void noDevuelveElTiempoDeQuienYaNoEstaEnElWorkspace() {
        DailySettings existing = new DailySettings(project);
        existing.setPersonSeconds(Map.of(luis.getId().toString(), 60, UUID.randomUUID().toString(), 45));
        when(settingsRepository.findByProjectId(project.getId())).thenReturn(Optional.of(existing));

        assertThat(service.get(SLUG, project.getId()).personTimes())
                .containsExactly(new DailySettingsResponse.PersonTime(luis.getId(), 60));
    }
}
