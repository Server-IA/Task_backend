package com.taskflow.api.dto.response;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningResponsesTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 15);
    private static final LocalDate END = LocalDate.of(2026, 10, 29);

    @Test
    void estadoDelCicloSegunLasFechas() {
        assertThat(CycleStatus.of(null, END, START)).isEqualTo(CycleStatus.DRAFT);
        assertThat(CycleStatus.of(START, END, START.minusDays(1))).isEqualTo(CycleStatus.UPCOMING);
        assertThat(CycleStatus.of(START, END, START)).isEqualTo(CycleStatus.CURRENT);
        assertThat(CycleStatus.of(START, END, END)).isEqualTo(CycleStatus.CURRENT);
        assertThat(CycleStatus.of(START, END, END.plusDays(1))).isEqualTo(CycleStatus.COMPLETED);
    }

    @Test
    void unCicloCerradoEstaCompletadoAunqueSusFechasNoHayanTerminado() {
        assertThat(CycleStatus.of(START, END, true, START)).isEqualTo(CycleStatus.COMPLETED);
        assertThat(CycleStatus.of(START, END, true, START.minusDays(1))).isEqualTo(CycleStatus.COMPLETED);
        assertThat(CycleStatus.of(null, null, true, START)).isEqualTo(CycleStatus.COMPLETED);
        assertThat(CycleStatus.of(START, END, false, START)).isEqualTo(CycleStatus.CURRENT);
    }

    @Test
    void porcentajeDeAvance() {
        assertThat(ProgressResponse.of(0, 0).percentage()).isZero();
        assertThat(ProgressResponse.of(3, 1).percentage()).isEqualTo(33);
        assertThat(ProgressResponse.of(3, 2).percentage()).isEqualTo(67);
        assertThat(ProgressResponse.of(4, 4).percentage()).isEqualTo(100);
    }
}
