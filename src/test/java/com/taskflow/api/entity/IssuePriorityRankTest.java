package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.IssuePriority;
import org.hibernate.annotations.Formula;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** La fórmula de {@code priorityRank} sigue el orden de {@link IssuePriority}: urgente primero, sin prioridad al final. */
class IssuePriorityRankTest {

    @Test
    void cadaPrioridadTieneSuPosicionYSinPrioridadVaAlFinal() throws NoSuchFieldException {
        String formula = Issue.class.getDeclaredField("priorityRank").getAnnotation(Formula.class).value();

        for (IssuePriority priority : IssuePriority.values()) {
            if (priority == IssuePriority.NONE) {
                continue;
            }
            Matcher matcher = Pattern.compile("when '" + priority.name() + "' then ([0-9]+)").matcher(formula);
            assertThat(matcher.find()).as(priority.name()).isTrue();
            assertThat(Integer.parseInt(matcher.group(1))).as(priority.name()).isEqualTo(priority.ordinal());
        }
        assertThat(formula).doesNotContain("'NONE'").contains("else " + IssuePriority.NONE.ordinal() + " end");
        assertThat(IssuePriority.NONE.ordinal()).isEqualTo(IssuePriority.values().length - 1);
        assertThat(Arrays.stream(IssuePriority.values()).map(Enum::name))
                .containsExactly("URGENT", "HIGH", "MEDIUM", "LOW", "NONE");
    }
}
