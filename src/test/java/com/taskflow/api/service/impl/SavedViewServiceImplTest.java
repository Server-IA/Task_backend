package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateSavedViewRequest;
import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.ReorderSavedViewsRequest;
import com.taskflow.api.dto.request.UpdateSavedViewRequest;
import com.taskflow.api.dto.response.SavedViewResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.SavedView;
import com.taskflow.api.entity.SavedViewFilters;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.SavedViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private SavedViewRepository viewRepository;
    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ProjectAccess projectAccess;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private SavedViewServiceImpl viewService;

    private Project project;
    private Workspace workspace;
    private User member;
    private User otherMember;
    private User pm;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setWorkspace(workspace);
        member = user(false);
        otherMember = user(false);
        pm = user(true);
        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(projectMapper.toSavedViewResponse(any(SavedView.class), anyBoolean(), anyLong()))
                .thenAnswer(invocation -> {
                    SavedView view = invocation.getArgument(0);
                    return new SavedViewResponse(view.getId(), view.getName(), view.isShared(), null,
                            view.getFilters(), view.getPosition(), invocation.getArgument(1),
                            invocation.getArgument(2));
                });
        lenient().when(viewRepository.save(any(SavedView.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(viewRepository.saveAndFlush(any(SavedView.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User user(boolean manager) {
        User user = new User();
        user.setId(UUID.randomUUID());
        lenient().when(lookup.user(user.getId())).thenReturn(user);
        lenient().when(projectAccess.isManager(workspace, user.getId())).thenReturn(manager);
        return user;
    }

    private SavedView view(User owner, boolean shared, int position) {
        SavedView view = new SavedView();
        view.setId(UUID.randomUUID());
        view.setProject(project);
        view.setOwner(owner);
        view.setName("Vista " + position);
        view.setShared(shared);
        view.setPosition(position);
        view.setFilters(SavedViewFilters.empty());
        lenient().when(viewRepository.findByIdAndProjectId(view.getId(), project.getId())).thenReturn(Optional.of(view));
        return view;
    }

    private void sharedGroup(SavedView... views) {
        lenient().when(viewRepository.findAllByProjectIdAndSharedTrueOrderByPositionAscCreatedAtAsc(project.getId()))
                .thenReturn(Arrays.asList(views));
    }

    private void personalGroup(User owner, SavedView... views) {
        lenient().when(viewRepository.findAllByProjectIdAndOwnerIdAndSharedFalseOrderByPositionAscCreatedAtAsc(
                project.getId(), owner.getId())).thenReturn(Arrays.asList(views));
    }

    private static UpdateSavedViewRequest rename(String name) {
        return new UpdateSavedViewRequest(name, null, null, null);
    }

    @Test
    void listaLasCompartidasYLasMiasMarcandoCualesSonMias() {
        SavedView shared = view(pm, true, 0);
        SavedView mine = view(member, false, 0);
        when(viewRepository.findVisible(project.getId(), member.getId())).thenReturn(List.of(shared, mine));

        List<SavedViewResponse> views = viewService.getViews(SLUG, project.getId(), member.getId());

        assertThat(views).extracting(SavedViewResponse::id).containsExactly(shared.getId(), mine.getId());
        assertThat(views).extracting(SavedViewResponse::mine).containsExactly(false, true);
    }

    @Test
    void cadaVistaTraeCuantasTareasCumplenSusFiltros() {
        SavedView shared = view(pm, true, 0);
        SavedView mine = view(member, false, 0);
        when(viewRepository.findVisible(project.getId(), member.getId())).thenReturn(List.of(shared, mine));
        when(issueRepository.count(ArgumentMatchers.<Specification<Issue>>any())).thenReturn(4L, 2L);

        List<SavedViewResponse> views = viewService.getViews(SLUG, project.getId(), member.getId());

        assertThat(views).extracting(SavedViewResponse::issueCount).containsExactly(4L, 2L);
    }

    @Test
    void losFiltrosDeLaVistaSonLosDelListadoDeTareas() {
        UUID state = UUID.randomUUID();
        UUID cycle = UUID.randomUUID();
        SavedViewFilters filters = new SavedViewFilters(List.of(state), List.of(IssuePriority.HIGH), List.of(),
                List.of(), "login", "board", "priority", cycle, null, true, "assignee");

        IssueFilter issueFilter = SavedViewServiceImpl.issueFilter(filters);

        assertThat(issueFilter).isEqualTo(new IssueFilter(List.of(state), List.of(IssuePriority.HIGH), List.of(),
                List.of(), "login", cycle, null, true));
        assertThat(SavedViewServiceImpl.issueFilter(null))
                .isEqualTo(new IssueFilter(List.of(), List.of(), List.of(), List.of(), null, null, null, null));
    }

    @Nested
    class Reorder {

        @Test
        void unMiembroOrdenaSusVistasPersonales() {
            SavedView a = view(member, false, 0);
            SavedView b = view(member, false, 1);
            SavedView c = view(member, false, 2);
            personalGroup(member, a, b, c);

            viewService.reorder(SLUG, project.getId(), member.getId(),
                    new ReorderSavedViewsRequest(null, List.of(c.getId(), a.getId(), b.getId())));

            assertThat(List.of(c.getPosition(), a.getPosition(), b.getPosition())).containsExactly(0, 1, 2);
            verify(viewRepository).findVisible(project.getId(), member.getId());
        }

        @Test
        void soloElProjectManagerOrdenaLasCompartidas() {
            SavedView first = view(pm, true, 0);
            SavedView second = view(pm, true, 1);
            sharedGroup(first, second);

            assertThatThrownBy(() -> viewService.reorder(SLUG, project.getId(), member.getId(),
                    new ReorderSavedViewsRequest(List.of(second.getId(), first.getId()), null)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(SavedViewServiceImpl.REORDER_SHARED_FORBIDDEN);
            assertThat(first.getPosition()).isZero();

            viewService.reorder(SLUG, project.getId(), pm.getId(),
                    new ReorderSavedViewsRequest(List.of(second.getId(), first.getId()), null));

            assertThat(List.of(second.getPosition(), first.getPosition())).containsExactly(0, 1);
        }

        @Test
        void laListaDebeTenerExactamenteLasVistasDelGrupo() {
            SavedView a = view(member, false, 0);
            SavedView b = view(member, false, 1);
            SavedView foreign = view(otherMember, false, 0);
            personalGroup(member, a, b);

            for (List<UUID> ids : List.of(List.of(a.getId()), List.of(a.getId(), a.getId()),
                    List.of(a.getId(), foreign.getId()), List.of(a.getId(), b.getId(), foreign.getId()))) {
                assertThatThrownBy(() -> viewService.reorder(SLUG, project.getId(), member.getId(),
                        new ReorderSavedViewsRequest(null, ids)))
                        .isInstanceOf(BadRequestException.class)
                        .hasMessage("La lista debe tener exactamente tus vistas personales, sin repetir");
            }
            assertThat(List.of(a.getPosition(), b.getPosition())).containsExactly(0, 1);
        }

        @Test
        void sinNingunaListaResponde400() {
            assertThatThrownBy(() -> viewService.reorder(SLUG, project.getId(), member.getId(),
                    new ReorderSavedViewsRequest(null, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(SavedViewServiceImpl.REORDER_EMPTY);
        }
    }

    @Nested
    class Create {

        @Test
        void unMiembroGuardaUnaVistaPersonalAlFinalDeLasSuyas() {
            personalGroup(member, view(member, false, 0));
            UUID cycleId = UUID.randomUUID();
            SavedViewFilters filters = new SavedViewFilters(null, List.of(IssuePriority.HIGH, IssuePriority.HIGH),
                    null, null, "  login ", "board", "dueDate,asc", cycleId, null, true, "assignee");

            SavedViewResponse response = viewService.create(SLUG, project.getId(), member.getId(),
                    new CreateSavedViewRequest("  Mis urgentes ", null, filters));

            ArgumentCaptor<SavedView> saved = ArgumentCaptor.forClass(SavedView.class);
            verify(viewRepository).save(saved.capture());
            assertThat(saved.getValue().getOwner()).isSameAs(member);
            assertThat(saved.getValue().getName()).isEqualTo("Mis urgentes");
            assertThat(saved.getValue().isShared()).isFalse();
            assertThat(saved.getValue().getPosition()).isEqualTo(1);
            // Filtros normalizados: listas sin repetidos ([] si no vienen) y búsqueda recortada
            assertThat(saved.getValue().getFilters()).isEqualTo(new SavedViewFilters(List.of(),
                    List.of(IssuePriority.HIGH), List.of(), List.of(), "login", "board", "dueDate,asc", cycleId, null,
                    true, "assignee"));
            assertThat(response.mine()).isTrue();
        }

        @Test
        void sinFiltrosSeGuardaVacia() {
            personalGroup(member);

            viewService.create(SLUG, project.getId(), member.getId(), new CreateSavedViewRequest("Todo", false, null));

            ArgumentCaptor<SavedView> saved = ArgumentCaptor.forClass(SavedView.class);
            verify(viewRepository).save(saved.capture());
            assertThat(saved.getValue().getFilters()).isEqualTo(SavedViewFilters.empty());
            assertThat(saved.getValue().getPosition()).isZero();
        }

        @Test
        void unMiembroNoPuedeCrearVistasCompartidas() {
            assertThatThrownBy(() -> viewService.create(SLUG, project.getId(), member.getId(),
                    new CreateSavedViewRequest("Sprint", true, null)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager puede crear vistas compartidas");
            verify(viewRepository, never()).save(any());
        }

        @Test
        void elProjectManagerCreaUnaCompartidaAlFinalDeLasCompartidas() {
            sharedGroup(view(pm, true, 0), view(pm, true, 1));

            viewService.create(SLUG, project.getId(), pm.getId(), new CreateSavedViewRequest("Sprint", true, null));

            ArgumentCaptor<SavedView> saved = ArgumentCaptor.forClass(SavedView.class);
            verify(viewRepository).save(saved.capture());
            assertThat(saved.getValue().isShared()).isTrue();
            assertThat(saved.getValue().getPosition()).isEqualTo(2);
        }
    }

    @Nested
    class Update {

        @Test
        void elDuenoEditaSuVistaPersonal() {
            SavedView mine = view(member, false, 0);

            viewService.update(SLUG, project.getId(), mine.getId(), member.getId(), rename(" Bugs abiertos "));

            assertThat(mine.getName()).isEqualTo("Bugs abiertos");
        }

        @Test
        void laVistaPersonalDeOtroNoExisteNiParaElProjectManager() {
            SavedView mine = view(member, false, 0);

            for (User someoneElse : List.of(otherMember, pm)) {
                assertThatThrownBy(() -> viewService.update(SLUG, project.getId(), mine.getId(), someoneElse.getId(),
                        rename("Robada")))
                        .isInstanceOf(ResourceNotFoundException.class)
                        .hasMessage("Vista no encontrada");
            }
            assertThat(mine.getName()).isEqualTo("Vista 0");
        }

        @Test
        void unMiembroNoEditaLasCompartidasAunqueLasHayaCreado() {
            // Quien la creó ya no es project manager
            SavedView shared = view(member, true, 0);

            assertThatThrownBy(() -> viewService.update(SLUG, project.getId(), shared.getId(), member.getId(),
                    rename("Otra")))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager puede editar las vistas compartidas");
        }

        @Test
        void elProjectManagerEditaLasCompartidas() {
            SavedView shared = view(otherMember, true, 0);
            SavedViewFilters filters = new SavedViewFilters(null, null, List.of(member.getId()), null, null,
                    "list", null, null, null, null, null);

            SavedViewResponse response = viewService.update(SLUG, project.getId(), shared.getId(), pm.getId(),
                    new UpdateSavedViewRequest("Del equipo", filters, null, null));

            assertThat(shared.getName()).isEqualTo("Del equipo");
            assertThat(shared.getFilters().assignee()).containsExactly(member.getId());
            assertThat(response.mine()).isFalse();
        }

        @Test
        void unMiembroNoPuedeCompartirSuVista() {
            SavedView mine = view(member, false, 0);

            assertThatThrownBy(() -> viewService.update(SLUG, project.getId(), mine.getId(), member.getId(),
                    new UpdateSavedViewRequest(null, null, true, null)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager puede compartir vistas");
            assertThat(mine.isShared()).isFalse();
        }

        @Test
        void alCompartirPasaAlFinalDeLasCompartidasYSuGrupoSeRenumera() {
            SavedView first = view(pm, false, 0);
            SavedView second = view(pm, false, 1);
            personalGroup(pm, first, second);
            sharedGroup(view(otherMember, true, 0));

            viewService.update(SLUG, project.getId(), first.getId(), pm.getId(),
                    new UpdateSavedViewRequest(null, null, true, null));

            assertThat(first.isShared()).isTrue();
            assertThat(first.getPosition()).isEqualTo(1);
            assertThat(second.getPosition()).isZero();
        }

        @Test
        void reordenaDentroDeSuGrupo() {
            SavedView a = view(member, false, 0);
            SavedView b = view(member, false, 1);
            SavedView c = view(member, false, 2);
            personalGroup(member, a, b, c);

            viewService.update(SLUG, project.getId(), c.getId(), member.getId(),
                    new UpdateSavedViewRequest(null, null, null, 1));

            assertThat(List.of(a.getPosition(), c.getPosition(), b.getPosition())).containsExactly(0, 1, 2);
        }
    }

    @Nested
    class Delete {

        @Test
        void elDuenoEliminaSuVistaYLasDemasSeRenumeran() {
            SavedView a = view(member, false, 0);
            SavedView b = view(member, false, 1);
            personalGroup(member, a, b);

            viewService.delete(SLUG, project.getId(), a.getId(), member.getId());

            verify(viewRepository).delete(a);
            assertThat(b.getPosition()).isZero();
        }

        @Test
        void nadieMasVeNiEliminaUnaVistaPersonal() {
            SavedView mine = view(member, false, 0);

            assertThatThrownBy(() -> viewService.delete(SLUG, project.getId(), mine.getId(), pm.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(viewRepository, never()).delete(any());
        }

        @Test
        void unMiembroNoEliminaLasCompartidas() {
            SavedView shared = view(pm, true, 0);

            assertThatThrownBy(() -> viewService.delete(SLUG, project.getId(), shared.getId(), member.getId()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager puede eliminar las vistas compartidas");
            verify(viewRepository, never()).delete(any());
        }

        @Test
        void elProjectManagerEliminaLasCompartidas() {
            SavedView shared = view(otherMember, true, 0);
            sharedGroup(shared);

            viewService.delete(SLUG, project.getId(), shared.getId(), pm.getId());

            verify(viewRepository).delete(shared);
        }
    }
}
