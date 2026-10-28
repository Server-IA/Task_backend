package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.IssueSummaryResponse;
import com.taskflow.api.dto.response.ProjectStatsResponse;
import com.taskflow.api.dto.response.ProjectStatsResponse.Burndown;
import com.taskflow.api.dto.response.ProjectStatsResponse.BurndownPoint;
import com.taskflow.api.dto.response.ProjectStatsResponse.OpenStateCount;
import com.taskflow.api.dto.response.ProjectStatsResponse.StaleIssue;
import com.taskflow.api.dto.response.ProjectStatsResponse.ThroughputWeek;
import com.taskflow.api.dto.response.ProjectStatsResponse.WorkloadEntry;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.repository.IssueStatsRow;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ProjectStatsCalculatorTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    /** Martes 27 de octubre de 2026, 12:00 en Bogotá. La semana empezó el lunes 26. */
    private static final Instant NOW = Instant.parse("2026-10-27T17:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    private final ProjectStatsCalculator calculator = new ProjectStatsCalculator(NOW, BOGOTA);

    private final State backlog = state("Backlog", StateGroup.BACKLOG, 0);
    private final State todo = state("Todo", StateGroup.UNSTARTED, 1);
    private final State inProgress = state("In Progress", StateGroup.STARTED, 2);
    private final State done = state("Done", StateGroup.COMPLETED, 3);
    private final State cancelled = state("Cancelled", StateGroup.CANCELLED, 4);

    private int sequence = 0;

    private static State state(String name, StateGroup group, int position) {
        State state = new State(null, name, "#A3A3A3", group, position);
        state.setId(UUID.randomUUID());
        return state;
    }

    /** Hora local de Bogotá → instante. */
    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(BOGOTA).toInstant();
    }

    private IssueStatsRow open(State state, LocalDate dueDate, Instant updatedAt) {
        return new IssueStatsRow(UUID.randomUUID(), ++sequence, "Tarea " + sequence, state.getId(),
                state.getStateGroup(), dueDate, at("2026-09-01T09:00"), updatedAt, null);
    }

    private IssueStatsRow open(State state) {
        return open(state, null, NOW);
    }

    private IssueStatsRow completed(Instant createdAt, Instant completedAt) {
        return new IssueStatsRow(UUID.randomUUID(), ++sequence, "Tarea " + sequence, done.getId(),
                StateGroup.COMPLETED, null, createdAt, completedAt, completedAt);
    }

    private IssueStatsRow completedAt(Instant completedAt) {
        return completed(completedAt.minus(Duration.ofDays(1)), completedAt);
    }

    @Nested
    class Mediana {

        @Test
        void sinValoresEsNula() {
            assertThat(ProjectStatsCalculator.median(List.of())).isNull();
        }

        @Test
        void cantidadImparEsElValorCentral() {
            assertThat(ProjectStatsCalculator.median(List.of(3.0))).isEqualTo(3.0);
            assertThat(ProjectStatsCalculator.median(List.of(9.0, 1.0, 2.0))).isEqualTo(2.0);
        }

        @Test
        void cantidadParEsElPromedioDeLosDosCentrales() {
            assertThat(ProjectStatsCalculator.median(List.of(10.0, 1.0, 3.0, 2.0))).isEqualTo(2.5);
        }
    }

    @Nested
    class Semanas {

        @Test
        void laSemanaEmpiezaElLunes() {
            assertThat(ProjectStatsCalculator.startOfWeek(TODAY)).isEqualTo(LocalDate.of(2026, 10, 26));
            assertThat(ProjectStatsCalculator.startOfWeek(LocalDate.of(2026, 10, 26))).isEqualTo(LocalDate.of(2026, 10, 26));
            assertThat(ProjectStatsCalculator.startOfWeek(LocalDate.of(2026, 11, 1))).isEqualTo(LocalDate.of(2026, 10, 26));
        }

        @Test
        void throughputTieneExactamenteLasSemanasPedidasDeLaMasAntiguaALaActual() {
            List<IssueStatsRow> issues = List.of(
                    completedAt(at("2026-10-26T01:00")),   // lunes de esta semana
                    completedAt(at("2026-10-25T22:00")),   // domingo (en UTC ya es lunes): semana pasada
                    completedAt(at("2026-10-20T10:00")),
                    completedAt(at("2026-10-01T10:00")),   // fuera de las 4 semanas
                    open(todo));

            List<ThroughputWeek> throughput = calculator.throughput(issues, 4);

            assertThat(throughput).containsExactly(
                    new ThroughputWeek(LocalDate.of(2026, 10, 5), 0),
                    new ThroughputWeek(LocalDate.of(2026, 10, 12), 0),
                    new ThroughputWeek(LocalDate.of(2026, 10, 19), 2),
                    new ThroughputWeek(LocalDate.of(2026, 10, 26), 1));
            assertThat(calculator.throughput(issues, 12)).hasSize(12)
                    .last().isEqualTo(new ThroughputWeek(LocalDate.of(2026, 10, 26), 1));
        }

        @Test
        void contadoresDeLaSemana() {
            IssueStatsRow createdMonday = new IssueStatsRow(UUID.randomUUID(), 99, "Nueva", todo.getId(),
                    StateGroup.UNSTARTED, null, at("2026-10-26T00:30"), NOW, null);
            List<IssueStatsRow> issues = List.of(
                    createdMonday,
                    completedAt(at("2026-10-27T08:00")),
                    completedAt(at("2026-10-23T08:00")),
                    completedAt(at("2026-10-19T00:10")),
                    completedAt(at("2026-10-18T23:50")));

            ProjectStatsResponse stats = calculate(issues, 8);

            assertThat(stats.createdThisWeek()).isEqualTo(2); // la nueva y la completada hoy (creada ayer)
            assertThat(stats.completedThisWeek()).isEqualTo(1);
            assertThat(stats.completedLastWeek()).isEqualTo(2);
            assertThat(stats.openCount()).isEqualTo(1);
        }
    }

    @Nested
    class CycleTime {

        @Test
        void esLaMedianaDelPeriodoYDelPeriodoAnterior() {
            // weeks = 4 → periodo actual desde el lunes 5 de octubre; el anterior, desde el 7 de septiembre
            List<IssueStatsRow> issues = List.of(
                    completed(at("2026-10-10T10:00"), at("2026-10-12T10:00")),   // 2 días
                    completed(at("2026-10-16T10:00"), at("2026-10-20T10:00")),   // 4 días
                    completed(at("2026-10-21T10:00"), at("2026-10-27T10:00")),   // 6 días
                    completed(at("2026-09-20T00:00"), at("2026-09-21T12:00")),   // 1,5 días (periodo anterior)
                    completed(at("2026-08-01T00:00"), at("2026-08-30T00:00")));  // fuera de ambos

            ProjectStatsResponse stats = calculate(issues, 4);

            assertThat(stats.cycleTimeDays()).isEqualTo(4.0);
            assertThat(stats.previousCycleTimeDays()).isEqualTo(1.5);
        }

        @Test
        void seRedondeaAUnDecimalYEsNulaSinCompletadas() {
            List<IssueStatsRow> issues = List.of(
                    completed(at("2026-10-20T00:00"), at("2026-10-23T10:00")));   // 3,4166… días

            ProjectStatsResponse stats = calculate(issues, 8);

            assertThat(stats.cycleTimeDays()).isEqualTo(3.4);
            assertThat(stats.previousCycleTimeDays()).isNull();
        }
    }

    @Nested
    class BurndownDelCiclo {

        private Cycle cycle(LocalDate start, LocalDate end) {
            Cycle cycle = new Cycle();
            cycle.setId(UUID.randomUUID());
            cycle.setName("Sprint 1");
            cycle.setStartDate(start);
            cycle.setEndDate(end);
            return cycle;
        }

        @Test
        void unPuntoPorDiaHastaHoyConLasPendientesAlFinalDeCadaDia() {
            Cycle sprint = cycle(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 11, 5));
            IssueStatsRow doneSaturday = completedAt(at("2026-10-24T23:30"));
            IssueStatsRow doneToday = completedAt(at("2026-10-27T09:00"));
            IssueStatsRow doneBeforeStart = completedAt(at("2026-10-20T09:00"));
            IssueStatsRow stillOpen = open(inProgress);
            IssueStatsRow dropped = new IssueStatsRow(UUID.randomUUID(), 50, "Cancelada", cancelled.getId(),
                    StateGroup.CANCELLED, null, NOW, NOW, null);
            IssueStatsRow notInCycle = open(todo);
            List<IssueStatsRow> issues = List.of(doneSaturday, doneToday, doneBeforeStart, stillOpen, dropped,
                    notInCycle);
            Set<UUID> cycleIssueIds = Set.of(doneSaturday.id(), doneToday.id(), doneBeforeStart.id(), stillOpen.id(),
                    dropped.id());

            Burndown burndown = new ProjectStatsCalculator(NOW, BOGOTA).calculate("WEB", 8, issues, List.of(),
                    Map.of(), sprint, cycleIssueIds).burndown();

            assertThat(burndown.cycleId()).isEqualTo(sprint.getId());
            assertThat(burndown.name()).isEqualTo("Sprint 1");
            assertThat(burndown.total()).isEqualTo(4); // sin la cancelada
            assertThat(burndown.points()).containsExactly(
                    new BurndownPoint(LocalDate.of(2026, 10, 23), 3),
                    new BurndownPoint(LocalDate.of(2026, 10, 24), 2),
                    new BurndownPoint(LocalDate.of(2026, 10, 25), 2),
                    new BurndownPoint(LocalDate.of(2026, 10, 26), 2),
                    new BurndownPoint(LocalDate.of(2026, 10, 27), 1));
        }

        @Test
        void sinCicloEnCursoEsNulo() {
            assertThat(calculate(List.of(open(todo)), 8).burndown()).isNull();
        }
    }

    @Nested
    class TareasAbiertas {

        @Test
        void porEstadoSoloEstadosAbiertosEnOrdenYConCeros() {
            List<IssueStatsRow> issues = List.of(open(backlog), open(backlog), open(inProgress),
                    completedAt(at("2026-10-27T08:00")));

            List<OpenStateCount> byState = calculator.openByState(issues,
                    List.of(done, inProgress, cancelled, todo, backlog));

            assertThat(byState).extracting(OpenStateCount::name, OpenStateCount::count).containsExactly(
                    tuple("Backlog", 2L),
                    tuple("Todo", 0L),
                    tuple("In Progress", 1L));
            assertThat(byState.getFirst().group()).isEqualTo(StateGroup.BACKLOG);
            assertThat(byState.getFirst().color()).isEqualTo("#A3A3A3");
        }

        @Test
        void vencidasLaMasVencidaPrimeroYComoMaximoDiez() {
            List<IssueStatsRow> issues = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                issues.add(open(todo, TODAY.minusDays(i), NOW));
            }
            issues.add(open(todo, TODAY, NOW));                       // vence hoy: todavía no está vencida
            issues.add(new IssueStatsRow(UUID.randomUUID(), 77, "Hecha", done.getId(), StateGroup.COMPLETED,
                    TODAY.minusDays(30), NOW, NOW, NOW));              // cerrada: no cuenta

            List<IssueSummaryResponse> overdue = calculator.overdue(issues, "WEB");

            assertThat(overdue).hasSize(ProjectStatsCalculator.MAX_OVERDUE);
            assertThat(overdue.getFirst().code()).isEqualTo("WEB-12");   // vencida hace 12 días
            assertThat(overdue.get(9).code()).isEqualTo("WEB-3");
        }

        @Test
        void cargaPorPersonaConLasSinAsignarAparte() {
            UserSummaryResponse ana = new UserSummaryResponse(UUID.randomUUID(), "ana@test.com", "Ana", null);
            UserSummaryResponse beto = new UserSummaryResponse(UUID.randomUUID(), "beto@test.com", "Beto", null);
            IssueStatsRow shared = open(todo, TODAY.minusDays(1), NOW);   // vencida, con dos asignados
            IssueStatsRow anaOnly = open(inProgress);
            IssueStatsRow unassigned = open(backlog);
            IssueStatsRow closed = completedAt(at("2026-10-27T08:00"));

            List<WorkloadEntry> workload = calculator.workload(List.of(shared, anaOnly, unassigned, closed), Map.of(
                    shared.id(), List.of(ana, beto),
                    anaOnly.id(), List.of(ana),
                    closed.id(), List.of(beto)));

            assertThat(workload).containsExactly(
                    new WorkloadEntry(ana, 2, 1),
                    new WorkloadEntry(beto, 1, 1),
                    new WorkloadEntry(null, 1, 0));
        }

        @Test
        void estancadasMasDeCatorceDiasSinCambiosLaMasAntiguaPrimero() {
            IssueStatsRow fourteen = open(todo, null, NOW.minus(Duration.ofDays(14)));
            IssueStatsRow fifteen = open(todo, null, NOW.minus(Duration.ofDays(15)));
            IssueStatsRow forty = open(todo, null, NOW.minus(Duration.ofDays(40)));
            IssueStatsRow closedOld = new IssueStatsRow(UUID.randomUUID(), 90, "Vieja", done.getId(),
                    StateGroup.COMPLETED, null, NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(60)),
                    NOW.minus(Duration.ofDays(60)));

            List<StaleIssue> stale = calculator.stale(List.of(fourteen, fifteen, forty, closedOld), "WEB");

            assertThat(stale).extracting(StaleIssue::id, StaleIssue::daysWithoutChanges).containsExactly(
                    tuple(forty.id(), 40L),
                    tuple(fifteen.id(), 15L));
        }

        @Test
        void comoMaximoCincoEstancadas() {
            List<IssueStatsRow> issues = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                issues.add(open(todo, null, NOW.minus(Duration.ofDays(20 + i))));
            }

            assertThat(calculator.stale(issues, "WEB")).hasSize(ProjectStatsCalculator.MAX_STALE);
        }
    }

    private ProjectStatsResponse calculate(List<IssueStatsRow> issues, int weeks) {
        return calculator.calculate("WEB", weeks, issues, List.of(backlog, todo, inProgress, done, cancelled),
                Map.of(), null, Set.of());
    }
}
