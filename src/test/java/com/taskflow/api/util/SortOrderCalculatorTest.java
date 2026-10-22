package com.taskflow.api.util;

import org.junit.jupiter.api.Test;

import static com.taskflow.api.util.SortOrderCalculator.GAP;
import static org.assertj.core.api.Assertions.assertThat;

class SortOrderCalculatorTest {

    @Test
    void entreDosTarjetasUsaElPromedio() {
        assertThat(SortOrderCalculator.between(100d, 200d, 500d)).isEqualTo(150d);
    }

    @Test
    void debajoDeLaUltimaSumaElEspacio() {
        assertThat(SortOrderCalculator.between(300d, null, 300d)).isEqualTo(300d + GAP);
    }

    @Test
    void arribaDeLaPrimeraRestaElEspacio() {
        assertThat(SortOrderCalculator.between(null, 100d, 500d)).isEqualTo(100d - GAP);
    }

    @Test
    void sinVecinasVaAlFinalDeLaColumna() {
        assertThat(SortOrderCalculator.between(null, null, 1000d)).isEqualTo(1000d + GAP);
        assertThat(SortOrderCalculator.between(null, null, null)).isEqualTo(GAP);
    }

    @Test
    void detectaCuandoHayQueRenumerar() {
        assertThat(SortOrderCalculator.needsRebalance(1.0, 1.0 + 1e-9)).isTrue();
        assertThat(SortOrderCalculator.needsRebalance(1.0, 2.0)).isFalse();
    }

    @Test
    void muchosMovimientosEnElMismoLugarTerminanPidiendoRenumerar() {
        double above = GAP;
        double below = 2 * GAP;
        int moves = 0;
        while (!SortOrderCalculator.needsRebalance(above, below)) {
            below = SortOrderCalculator.between(above, below, null);
            moves++;
        }
        // Con un espacio de 65535 caben decenas de movimientos antes de renumerar
        assertThat(moves).isGreaterThan(30);
    }
}
