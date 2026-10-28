package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.ChecklistItemRequest;
import com.taskflow.api.dto.request.UpdateChecklistItemRequest;
import com.taskflow.api.dto.response.ChecklistItemResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueChecklistItem;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueChecklistItemRepository;
import com.taskflow.api.repository.IssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.taskflow.api.entity.enums.ChecklistItemKind.CHECK;
import static com.taskflow.api.entity.enums.ChecklistItemKind.CRITERION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueChecklistServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private IssueChecklistItemRepository itemRepository;
    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private IssueMapper issueMapper;

    @InjectMocks
    private IssueChecklistServiceImpl checklistService;

    private Project project;
    private Issue issue;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(issueRepository.findByIdAndProjectId(issue.getId(), project.getId()))
                .thenReturn(Optional.of(issue));
        lenient().when(issueMapper.toChecklistItemResponse(any(IssueChecklistItem.class))).thenAnswer(invocation -> {
            IssueChecklistItem item = invocation.getArgument(0);
            return new ChecklistItemResponse(item.getId(), item.getKind(), item.getText(), item.isDone(),
                    item.getPosition());
        });
        lenient().when(itemRepository.saveAndFlush(any(IssueChecklistItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(itemRepository.save(any(IssueChecklistItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private IssueChecklistItem item(ChecklistItemKind kind, String text, int position) {
        IssueChecklistItem item = new IssueChecklistItem(issue, kind, text, position);
        item.setId(UUID.randomUUID());
        lenient().when(itemRepository.findByIdAndIssueId(item.getId(), issue.getId())).thenReturn(Optional.of(item));
        return item;
    }

    private void siblings(ChecklistItemKind kind, IssueChecklistItem... items) {
        when(itemRepository.findAllByIssueIdAndKindOrderByPositionAscCreatedAtAsc(issue.getId(), kind))
                .thenReturn(List.of(items));
    }

    @Test
    void listaPrimeroLosCriteriosYLuegoLaChecklistCadaUnoEnSuOrden() {
        List<IssueChecklistItem> stored = List.of(
                item(CHECK, "Reproducir", 0),
                item(CRITERION, "Dado A", 0),
                item(CHECK, "Corregir", 1),
                item(CRITERION, "Dado B", 1));
        when(itemRepository.findAllByIssueIdOrderByPositionAscCreatedAtAsc(issue.getId())).thenReturn(stored);

        List<ChecklistItemResponse> items = checklistService.getItems(SLUG, project.getId(), issue.getId());

        assertThat(items).extracting(ChecklistItemResponse::text)
                .containsExactly("Dado A", "Dado B", "Reproducir", "Corregir");
    }

    @Test
    void agregaAlFinalDeLosDeSuTipo() {
        siblings(CHECK, item(CHECK, "Reproducir", 0), item(CHECK, "Corregir", 1));

        ChecklistItemResponse created = checklistService.create(SLUG, project.getId(), issue.getId(),
                new ChecklistItemRequest(CHECK, "  Probar en otro navegador  "));

        assertThat(created.position()).isEqualTo(2);
        assertThat(created.text()).isEqualTo("Probar en otro navegador");
        assertThat(created.done()).isFalse();
    }

    @Test
    void elPrimeroDeUnTipoQuedaEnLaPosicionCero() {
        siblings(CRITERION);

        ChecklistItemResponse created = checklistService.create(SLUG, project.getId(), issue.getId(),
                new ChecklistItemRequest(CRITERION, "Dado …, cuando …, entonces …"));

        assertThat(created.position()).isZero();
        assertThat(created.kind()).isEqualTo(CRITERION);
    }

    @Test
    void rechazaPasarDelMaximoPorTarea() {
        when(itemRepository.countByIssueId(issue.getId()))
                .thenReturn((long) IssueChecklistServiceImpl.MAX_ITEMS_PER_ISSUE);

        assertThatThrownBy(() -> checklistService.create(SLUG, project.getId(), issue.getId(),
                new ChecklistItemRequest(CHECK, "Uno más")))
                .isInstanceOf(ConflictException.class);
        verify(itemRepository, never()).save(any());
    }

    @Test
    void reordenarMueveElElementoYRenumeraSoloLosDeSuTipo() {
        IssueChecklistItem a = item(CHECK, "A", 0);
        IssueChecklistItem b = item(CHECK, "B", 1);
        IssueChecklistItem c = item(CHECK, "C", 2);
        siblings(CHECK, a, b, c);

        checklistService.update(SLUG, project.getId(), issue.getId(), c.getId(),
                new UpdateChecklistItemRequest(null, null, 0));

        assertThat(List.of(c.getPosition(), a.getPosition(), b.getPosition())).containsExactly(0, 1, 2);
    }

    @Test
    void unaPosicionMayorQueElUltimoLoDejaAlFinal() {
        IssueChecklistItem a = item(CRITERION, "A", 0);
        IssueChecklistItem b = item(CRITERION, "B", 1);
        siblings(CRITERION, a, b);

        ChecklistItemResponse moved = checklistService.update(SLUG, project.getId(), issue.getId(), a.getId(),
                new UpdateChecklistItemRequest(null, null, 99));

        assertThat(moved.position()).isEqualTo(1);
        assertThat(b.getPosition()).isZero();
    }

    @Test
    void marcaComoHechoYEditaElTexto() {
        IssueChecklistItem a = item(CHECK, "A", 0);

        ChecklistItemResponse updated = checklistService.update(SLUG, project.getId(), issue.getId(), a.getId(),
                new UpdateChecklistItemRequest("  Escribir la prueba ", true, null));

        assertThat(updated.done()).isTrue();
        assertThat(updated.text()).isEqualTo("Escribir la prueba");
        assertThat(updated.position()).isZero();
    }

    @Test
    void rechazaUnTextoEnBlanco() {
        IssueChecklistItem a = item(CHECK, "A", 0);

        assertThatThrownBy(() -> checklistService.update(SLUG, project.getId(), issue.getId(), a.getId(),
                new UpdateChecklistItemRequest("   ", null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void alEliminarRenumeraLosQueQuedan() {
        IssueChecklistItem a = item(CHECK, "A", 0);
        IssueChecklistItem b = item(CHECK, "B", 1);
        IssueChecklistItem c = item(CHECK, "C", 2);
        siblings(CHECK, a, b, c);

        checklistService.delete(SLUG, project.getId(), issue.getId(), a.getId());

        verify(itemRepository).delete(a);
        assertThat(List.of(b.getPosition(), c.getPosition())).containsExactly(0, 1);
    }

    @Test
    void alCrearUnaTareaGuardaSusElementosEnOrdenPorTipo() {
        when(itemRepository.saveAll(anyList())).thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));

        checklistService.createAll(issue, List.of(
                new ChecklistItemRequest(CRITERION, "Dado A"),
                new ChecklistItemRequest(CHECK, "Paso 1"),
                new ChecklistItemRequest(CHECK, " Paso 2 "),
                new ChecklistItemRequest(CRITERION, "Dado B")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IssueChecklistItem>> saved = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(saved.capture());
        assertThat(saved.getValue())
                .extracting(IssueChecklistItem::getKind, IssueChecklistItem::getText, IssueChecklistItem::getPosition)
                .containsExactly(
                        tuple(CRITERION, "Dado A", 0),
                        tuple(CHECK, "Paso 1", 0),
                        tuple(CHECK, "Paso 2", 1),
                        tuple(CRITERION, "Dado B", 1));
        assertThat(saved.getValue()).allSatisfy(item -> {
            assertThat(item.getIssue()).isSameAs(issue);
            assertThat(item.isDone()).isFalse();
        });
    }

    @Test
    void sinChecklistNoGuardaNada() {
        assertThat(checklistService.createAll(issue, null)).isEmpty();
        verify(itemRepository, never()).saveAll(anyList());
    }
}
