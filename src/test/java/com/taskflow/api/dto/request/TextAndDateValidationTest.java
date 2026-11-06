package com.taskflow.api.dto.request;

import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.entity.enums.RetroColumn;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug hunt 3: textos hechos solo de espacios invisibles (duro, de ancho cero) pasaban {@code @NotBlank} y se guardaban
 * tareas, comentarios, elementos de checklist y notas que se ven vacíos; y cualquier fecha valía (0001-01-01,
 * +999999999-12-31), con un 500 o un mensaje engañoso.
 */
class TextAndDateValidationTest {

    private static final String NBSP = "  ";
    private static final String ZERO_WIDTH = "​⁠﻿";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static <T> Set<String> invalidFields(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    private static CreateIssueRequest issue(String title, LocalDate startDate, LocalDate dueDate) {
        return new CreateIssueRequest(title, null, null, null, null, startDate, dueDate, null, null, null, null, null,
                null, null);
    }

    private static UpdateIssueRequest issueEdit(String title, LocalDate dueDate) {
        return new UpdateIssueRequest(title, null, null, null, null, null, dueDate, null, null, null, null, null, null,
                null, null);
    }

    @Test
    void unTextoDeEspaciosInvisiblesNoEsUnTitulo() {
        for (String invisible : List.of(NBSP, ZERO_WIDTH, "  ​ ")) {
            assertThat(invalidFields(issue(invisible, null, null))).containsExactly("title");
            assertThat(invalidFields(issueEdit(invisible, null))).containsExactly("title");
            assertThat(invalidFields(new SplitItemRequest(invisible, null, null, null))).containsExactly("title");
            assertThat(invalidFields(new CommentRequest(invisible))).containsExactly("body");
            assertThat(invalidFields(new ChecklistItemRequest(ChecklistItemKind.CHECK, invisible)))
                    .containsExactly("text");
            assertThat(invalidFields(new UpdateChecklistItemRequest(invisible, null, null))).containsExactly("text");
            assertThat(invalidFields(new RetroNoteRequest(RetroColumn.WENT_WELL, invisible, null)))
                    .containsExactly("text");
            assertThat(invalidFields(new UpdateRetroNoteRequest(invisible, null, null))).containsExactly("text");
        }
    }

    @Test
    void conAlgoVisibleONullSiguenValiendo() {
        assertThat(invalidFields(issue(NBSP + "Login" + ZERO_WIDTH, null, null))).isEmpty();
        assertThat(invalidFields(issueEdit(null, null))).isEmpty();
        assertThat(invalidFields(new UpdateChecklistItemRequest(null, true, null))).isEmpty();
        assertThat(invalidFields(new UpdateRetroNoteRequest(null, null, true))).isEmpty();
        assertThat(invalidFields(new CommentRequest("👍"))).isEmpty();
    }

    @Test
    void lasFechasDePlanificacionVanDe1900A2999() {
        LocalDate typo = LocalDate.of(26, 11, 5);           // 0026 en vez de 2026
        LocalDate huge = LocalDate.of(999_999_999, 12, 31);  // PostgreSQL no la admite
        assertThat(invalidFields(issue("Login", typo, huge))).containsExactlyInAnyOrder("startDate", "dueDate");
        assertThat(invalidFields(issueEdit(null, LocalDate.MIN))).containsExactly("dueDate");
        assertThat(invalidFields(new CycleRequest("Sprint", null, LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31))))
                .containsExactlyInAnyOrder("startDate", "endDate");
        assertThat(invalidFields(new UpdateCycleRequest("Sprint", null, typo, null, null, null, null)))
                .containsExactly("startDate");
        assertThat(invalidFields(new ModuleRequest("Pagos", null, null, LocalDate.of(3000, 1, 1))))
                .containsExactly("targetDate");

        assertThat(invalidFields(issue("Login", PlanningDate.MIN, PlanningDate.MAX))).isEmpty();
        assertThat(invalidFields(new CycleRequest("Sprint", null, LocalDate.of(2026, 11, 2), null))).isEmpty();
    }
}
