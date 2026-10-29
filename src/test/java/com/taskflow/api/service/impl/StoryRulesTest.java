package com.taskflow.api.service.impl;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.repository.StoryChildCount;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryRulesTest {

    private static StoryChildCount count(IssueType type, StateGroup group, long count) {
        return new StoryChildCount(type, group, count);
    }

    @Test
    void listaParaRevisarConTodasLasTareasTerminadasYSinBugsAbiertos() {
        assertThat(StoryRules.isReady(StateGroup.STARTED, List.of(count(IssueType.TASK, StateGroup.COMPLETED, 5),
                count(IssueType.BUG, StateGroup.COMPLETED, 1)))).isTrue();
        assertThat(StoryRules.isReady(StateGroup.UNSTARTED, List.of(count(IssueType.TASK, StateGroup.COMPLETED, 2),
                count(IssueType.TASK, StateGroup.CANCELLED, 1)))).isTrue();
    }

    @Test
    void noEstaListaSinTareasConUnaAbiertaConUnBugAbiertoOSiEstaCerrada() {
        assertThat(StoryRules.isReady(StateGroup.STARTED, List.of())).isFalse();
        assertThat(StoryRules.isReady(StateGroup.STARTED, List.of(count(IssueType.BUG, StateGroup.COMPLETED, 1))))
                .isFalse();
        assertThat(StoryRules.isReady(StateGroup.STARTED, List.of(count(IssueType.TASK, StateGroup.COMPLETED, 4),
                count(IssueType.TASK, StateGroup.STARTED, 1)))).isFalse();
        assertThat(StoryRules.isReady(StateGroup.STARTED, List.of(count(IssueType.TASK, StateGroup.COMPLETED, 4),
                count(IssueType.BUG, StateGroup.BACKLOG, 1)))).isFalse();
        assertThat(StoryRules.isReady(StateGroup.COMPLETED, List.of(count(IssueType.TASK, StateGroup.COMPLETED, 4))))
                .isFalse();
    }

    @Test
    void conTodasSusTareasCanceladasNoEstaLista() {
        // No se hizo nada: no hay una tarea completada
        assertThat(StoryRules.isReady(StateGroup.STARTED, List.of(count(IssueType.TASK, StateGroup.CANCELLED, 3))))
                .isFalse();
        assertThat(StoryRules.isReady(StateGroup.STARTED, List.of(count(IssueType.TASK, StateGroup.CANCELLED, 3),
                count(IssueType.BUG, StateGroup.COMPLETED, 2)))).isFalse();
    }

    @Test
    void cerrarOReabrirEsDelProjectManager() {
        assertThat(StoryRules.managerOnlyMessage(StateGroup.STARTED, StateGroup.COMPLETED))
                .isEqualTo(StoryRules.CLOSE_FORBIDDEN);
        assertThat(StoryRules.managerOnlyMessage(StateGroup.UNSTARTED, StateGroup.CANCELLED))
                .isEqualTo(StoryRules.CLOSE_FORBIDDEN);
        assertThat(StoryRules.managerOnlyMessage(StateGroup.COMPLETED, StateGroup.CANCELLED))
                .isEqualTo(StoryRules.CLOSE_FORBIDDEN);
        assertThat(StoryRules.managerOnlyMessage(StateGroup.COMPLETED, StateGroup.STARTED))
                .isEqualTo(StoryRules.REOPEN_FORBIDDEN);
        assertThat(StoryRules.managerOnlyMessage(StateGroup.BACKLOG, StateGroup.STARTED)).isNull();
    }

    @Test
    void quienYCuandoLaCerroSeFijaAlCerrarlaSeConservaEntreCerradosYSeBorraAlReabrirla() {
        User ana = new User();
        User carlos = new User();
        Issue story = new Issue();
        story.setType(IssueType.STORY);
        Project project = new Project();
        story.setState(new State(project, "Done", "#000000", StateGroup.COMPLETED, 3));
        Instant closedAt = Instant.parse("2026-11-12T10:00:00Z");

        StoryRules.updateClosed(story, StateGroup.STARTED, ana, closedAt);
        assertThat(story.getClosedAt()).isEqualTo(closedAt);
        assertThat(story.getClosedBy()).isSameAs(ana);

        story.setState(new State(project, "Cancelled", "#000000", StateGroup.CANCELLED, 4));
        StoryRules.updateClosed(story, StateGroup.COMPLETED, carlos, closedAt.plusSeconds(60));
        assertThat(story.getClosedAt()).isEqualTo(closedAt);
        assertThat(story.getClosedBy()).isSameAs(ana);

        story.setState(new State(project, "In Progress", "#000000", StateGroup.STARTED, 2));
        StoryRules.updateClosed(story, StateGroup.CANCELLED, ana, Instant.now());
        assertThat(story.getClosedAt()).isNull();
        assertThat(story.getClosedBy()).isNull();
    }

    @Test
    void enLasTareasNoSeGuardaQuienLaCerro() {
        Issue task = new Issue();
        task.setType(IssueType.TASK);
        task.setState(new State(new Project(), "Done", "#000000", StateGroup.COMPLETED, 3));

        StoryRules.updateClosed(task, StateGroup.STARTED, new User(), Instant.now());

        assertThat(task.getClosedAt()).isNull();
    }

    @Test
    void elTextoDeLoQueMovioLaHistoriaUsaElPrimerNombre() {
        Project project = new Project();
        project.setIdentifier("WEB");
        Issue task = new Issue();
        task.setProject(project);
        task.setSequenceId(41);
        User luis = new User();
        luis.setFullName("  Luis Muñoz Pérez ");

        assertThat(StoryRules.startedBy(luis, task)).isEqualTo("Luis empezó WEB-41");
        assertThat(StoryRules.startedBy(null, task)).isEqualTo("Alguien empezó WEB-41");
    }
}
