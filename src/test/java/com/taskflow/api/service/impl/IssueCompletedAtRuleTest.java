package com.taskflow.api.service.impl;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.enums.StateGroup;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regla de {@code completedAt} al cambiar de estado ({@link IssueServiceImpl#updateCompletedAt}).
 */
class IssueCompletedAtRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    private static State state(StateGroup group) {
        return new State(null, group.name(), "#000000", group, 0);
    }

    @Test
    void seFijaAlEntrarAlGrupoCompletado() {
        Issue issue = new Issue();
        issue.setState(state(StateGroup.COMPLETED));

        IssueServiceImpl.updateCompletedAt(issue, StateGroup.STARTED, NOW);

        assertThat(issue.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void unaTareaNuevaCompletadaTambienLaRecibe() {
        Issue issue = new Issue();
        issue.setState(state(StateGroup.COMPLETED));

        IssueServiceImpl.updateCompletedAt(issue, null, NOW);

        assertThat(issue.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void seConservaEntreEstadosCompletados() {
        Issue issue = new Issue();
        issue.setState(state(StateGroup.COMPLETED));
        issue.setCompletedAt(NOW);

        IssueServiceImpl.updateCompletedAt(issue, StateGroup.COMPLETED, NOW.plusSeconds(3600));

        assertThat(issue.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void siFaltabaLaFechaEnUnaTareaCompletadaSeCompleta() {
        Issue issue = new Issue();
        issue.setState(state(StateGroup.COMPLETED));

        IssueServiceImpl.updateCompletedAt(issue, StateGroup.COMPLETED, NOW);

        assertThat(issue.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void seBorraAlSalirDelGrupoCompletado() {
        Issue issue = new Issue();
        issue.setCompletedAt(NOW);

        issue.setState(state(StateGroup.CANCELLED));
        IssueServiceImpl.updateCompletedAt(issue, StateGroup.COMPLETED, NOW);
        assertThat(issue.getCompletedAt()).isNull();

        issue.setCompletedAt(NOW);
        issue.setState(state(StateGroup.STARTED));
        IssueServiceImpl.updateCompletedAt(issue, StateGroup.COMPLETED, NOW);
        assertThat(issue.getCompletedAt()).isNull();
    }

    /** Cuándo un cambio de estado desbloquea a las tareas que la tarea bloqueaba ({@code becameCompleted}). */
    @Test
    void soloPasarDeUnGrupoAbiertoACompletadoDesbloqueaAOtras() {
        assertThat(IssueServiceImpl.becameCompleted(StateGroup.STARTED, StateGroup.COMPLETED)).isTrue();
        assertThat(IssueServiceImpl.becameCompleted(StateGroup.BACKLOG, StateGroup.COMPLETED)).isTrue();
        // Una tarea cancelada ya no bloqueaba; entre dos completados no cambia nada
        assertThat(IssueServiceImpl.becameCompleted(StateGroup.CANCELLED, StateGroup.COMPLETED)).isFalse();
        assertThat(IssueServiceImpl.becameCompleted(StateGroup.COMPLETED, StateGroup.COMPLETED)).isFalse();
        assertThat(IssueServiceImpl.becameCompleted(StateGroup.STARTED, StateGroup.CANCELLED)).isFalse();
        assertThat(IssueServiceImpl.becameCompleted(null, StateGroup.COMPLETED)).isFalse();
    }
}
