package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.ChecklistItemRequest;
import com.taskflow.api.dto.request.IssueTemplateRequest;
import com.taskflow.api.dto.request.UpdateIssueTemplateRequest;
import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.TemplateChecklistItem;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.IssueTemplateRepository;
import com.taskflow.api.repository.LabelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueTemplateServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private IssueTemplateRepository templateRepository;
    @Mock
    private LabelRepository labelRepository;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private IssueTemplateServiceImpl templateService;

    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(templateRepository.save(any(IssueTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(templateRepository.saveAndFlush(any(IssueTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private IssueTemplate template(String name, int position) {
        IssueTemplate template = new IssueTemplate();
        template.setId(UUID.randomUUID());
        template.setProject(project);
        template.setName(name);
        template.setPosition(position);
        lenient().when(templateRepository.findByIdAndProjectId(template.getId(), project.getId()))
                .thenReturn(Optional.of(template));
        return template;
    }

    private Label label(String name) {
        Label label = new Label();
        label.setId(UUID.randomUUID());
        label.setProject(project);
        label.setName(name);
        return label;
    }

    private void existing(IssueTemplate... templates) {
        when(templateRepository.findAllByProjectIdOrderByPositionAscCreatedAtAsc(project.getId()))
                .thenReturn(List.of(templates));
    }

    @Nested
    class Create {

        @Test
        void guardaLaPlantillaAlFinalConSusEtiquetasYChecklist() {
            existing(template("Reporte de bug", 0), template("Historia de usuario", 1));
            Label ui = label("UI");
            Label backend = label("Backend");
            when(labelRepository.findAllByProjectIdAndIdIn(eq(project.getId()), anyCollection()))
                    .thenReturn(List.of(ui, backend));
            IssueTemplateRequest request = new IssueTemplateRequest("  Incidente  ", "[Incidente] ",
                    "  ## Impacto  ", IssuePriority.URGENT, List.of(ui.getId(), backend.getId()),
                    List.of(new ChecklistItemRequest(ChecklistItemKind.CHECK, " Avisar al equipo "),
                            new ChecklistItemRequest(ChecklistItemKind.CRITERION, "Dado …")));

            templateService.create(SLUG, project.getId(), request);

            ArgumentCaptor<IssueTemplate> saved = ArgumentCaptor.forClass(IssueTemplate.class);
            verify(templateRepository).save(saved.capture());
            IssueTemplate template = saved.getValue();
            assertThat(template.getProject()).isSameAs(project);
            assertThat(template.getName()).isEqualTo("Incidente");
            // El prefijo conserva su espacio final; la descripción se recorta
            assertThat(template.getTitlePrefix()).isEqualTo("[Incidente] ");
            assertThat(template.getDescription()).isEqualTo("## Impacto");
            assertThat(template.getPriority()).isEqualTo(IssuePriority.URGENT);
            assertThat(template.getPosition()).isEqualTo(2);
            assertThat(template.getLabels()).extracting(Label::getName).containsExactly("Backend", "UI");
            assertThat(template.getChecklist())
                    .extracting(TemplateChecklistItem::getKind, TemplateChecklistItem::getText)
                    .containsExactly(tuple(ChecklistItemKind.CHECK, "Avisar al equipo"),
                            tuple(ChecklistItemKind.CRITERION, "Dado …"));
        }

        @Test
        void sinPrioridadOConNoneNoFijaPrioridadYSinPrefijoQuedaNull() {
            existing();
            IssueTemplateRequest request = new IssueTemplateRequest("Spike", "   ", null, IssuePriority.NONE,
                    null, null);

            templateService.create(SLUG, project.getId(), request);

            ArgumentCaptor<IssueTemplate> saved = ArgumentCaptor.forClass(IssueTemplate.class);
            verify(templateRepository).save(saved.capture());
            assertThat(saved.getValue().getPriority()).isNull();
            assertThat(saved.getValue().getTitlePrefix()).isNull();
            assertThat(saved.getValue().getPosition()).isZero();
            assertThat(saved.getValue().getLabels()).isEmpty();
            assertThat(saved.getValue().getChecklist()).isEmpty();
        }

        @Test
        void rechazaUnNombreRepetidoEnElProyecto() {
            when(templateRepository.existsByProjectIdAndNameIgnoreCase(project.getId(), "reporte de bug"))
                    .thenReturn(true);

            assertThatThrownBy(() -> templateService.create(SLUG, project.getId(),
                    new IssueTemplateRequest("reporte de bug", null, null, null, null, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Ya existe una plantilla llamada 'reporte de bug'");
            verify(templateRepository, never()).save(any());
        }

        @Test
        void rechazaEtiquetasDeOtroProyecto() {
            when(labelRepository.findAllByProjectIdAndIdIn(eq(project.getId()), anyCollection())).thenReturn(List.of());

            assertThatThrownBy(() -> templateService.create(SLUG, project.getId(),
                    new IssueTemplateRequest("Bug", null, null, null, List.of(UUID.randomUUID()), null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Las etiquetas deben pertenecer al proyecto");
        }
    }

    @Nested
    class Update {

        @Test
        void soloCambiaLosCamposEnviados() {
            IssueTemplate bug = template("Reporte de bug", 0);
            bug.setTitlePrefix("[Bug] ");
            bug.setPriority(IssuePriority.HIGH);
            bug.setDescription("## Pasos");
            bug.getChecklist().add(new TemplateChecklistItem(ChecklistItemKind.CHECK, "Corregir"));

            templateService.update(SLUG, project.getId(), bug.getId(),
                    new UpdateIssueTemplateRequest(null, null, "## Pasos para reproducir", null, null, null, null));

            assertThat(bug.getName()).isEqualTo("Reporte de bug");
            assertThat(bug.getTitlePrefix()).isEqualTo("[Bug] ");
            assertThat(bug.getPriority()).isEqualTo(IssuePriority.HIGH);
            assertThat(bug.getDescription()).isEqualTo("## Pasos para reproducir");
            assertThat(bug.getChecklist()).hasSize(1);
        }

        @Test
        void vacioQuitaElPrefijoYNoneLaPrioridadYLaChecklistSeReemplaza() {
            IssueTemplate bug = template("Reporte de bug", 0);
            bug.setTitlePrefix("[Bug] ");
            bug.setPriority(IssuePriority.HIGH);
            bug.getChecklist().add(new TemplateChecklistItem(ChecklistItemKind.CHECK, "Corregir"));

            templateService.update(SLUG, project.getId(), bug.getId(), new UpdateIssueTemplateRequest(null, "",
                    null, IssuePriority.NONE, null,
                    List.of(new ChecklistItemRequest(ChecklistItemKind.CRITERION, "Dado …")), null));

            assertThat(bug.getTitlePrefix()).isNull();
            assertThat(bug.getPriority()).isNull();
            assertThat(bug.getChecklist()).extracting(TemplateChecklistItem::getText).containsExactly("Dado …");
        }

        @Test
        void cambiarSoloMayusculasDelNombreNoChocaConsigoMisma() {
            IssueTemplate bug = template("Reporte de bug", 0);

            templateService.update(SLUG, project.getId(), bug.getId(),
                    new UpdateIssueTemplateRequest("Reporte de Bug", null, null, null, null, null, null));

            assertThat(bug.getName()).isEqualTo("Reporte de Bug");
            verify(templateRepository, never()).existsByProjectIdAndNameIgnoreCase(any(), anyString());
        }

        @Test
        void rechazaRenombrarAUnNombreQueYaExiste() {
            IssueTemplate bug = template("Reporte de bug", 0);
            when(templateRepository.existsByProjectIdAndNameIgnoreCase(project.getId(), "Tarea técnica"))
                    .thenReturn(true);

            assertThatThrownBy(() -> templateService.update(SLUG, project.getId(), bug.getId(),
                    new UpdateIssueTemplateRequest("Tarea técnica", null, null, null, null, null, null)))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void reordenarRenumeraLasPlantillas() {
            IssueTemplate bug = template("Reporte de bug", 0);
            IssueTemplate story = template("Historia de usuario", 1);
            IssueTemplate tech = template("Tarea técnica", 2);
            existing(bug, story, tech);

            templateService.update(SLUG, project.getId(), tech.getId(),
                    new UpdateIssueTemplateRequest(null, null, null, null, null, null, 0));

            assertThat(List.of(tech.getPosition(), bug.getPosition(), story.getPosition())).containsExactly(0, 1, 2);
        }
    }

    @Test
    void alEliminarRenumeraLasQueQuedan() {
        IssueTemplate bug = template("Reporte de bug", 0);
        IssueTemplate story = template("Historia de usuario", 1);
        IssueTemplate tech = template("Tarea técnica", 2);
        existing(bug, story, tech);

        templateService.delete(SLUG, project.getId(), bug.getId());

        verify(templateRepository).delete(bug);
        assertThat(List.of(story.getPosition(), tech.getPosition())).containsExactly(0, 1);
    }
}
