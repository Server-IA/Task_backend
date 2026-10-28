package com.taskflow.api.dto.request;

import com.taskflow.api.entity.SavedViewFilters;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.entity.enums.RetroColumn;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los elementos de la checklist, los filtros de las vistas y las excepciones del cierre de un ciclo se validan
 * dentro del cuerpo de la petición.
 */
class NestedValidationTest {

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

    private static List<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).sorted().toList();
    }

    private static SavedViewFilters filters(String view, String sort) {
        return filters(view, sort, null);
    }

    private static SavedViewFilters filters(String view, String sort, String groupBy) {
        return new SavedViewFilters(null, null, null, null, null, view, sort, null, null, null, groupBy);
    }

    @Test
    void validaCadaElementoDeLaChecklistDeUnaTareaNueva() {
        CreateIssueRequest request = new CreateIssueRequest("Tarea", null, null, null, null, null, null, null, null,
                List.of(new ChecklistItemRequest(ChecklistItemKind.CHECK, "Bien"),
                        new ChecklistItemRequest(null, "  "),
                        new ChecklistItemRequest(ChecklistItemKind.CRITERION, "x".repeat(501))), null);

        assertThat(paths(validator.validate(request)))
                .containsExactly("checklist[1].kind", "checklist[1].text", "checklist[2].text");
    }

    @Test
    void validaLaChecklistDeLasPlantillas() {
        IssueTemplateRequest request = new IssueTemplateRequest("Bug", null, null, null, null,
                List.of(new ChecklistItemRequest(ChecklistItemKind.CHECK, "")));

        assertThat(paths(validator.validate(request))).containsExactly("checklist[0].text");
    }

    @Test
    void validaCadaExcepcionAlCerrarUnCiclo() {
        CloseCycleRequest request = new CloseCycleRequest(null, null, Arrays.asList(
                new CloseCycleRequest.IssueMove(UUID.randomUUID(), CycleMoveTarget.BACKLOG),
                new CloseCycleRequest.IssueMove(null, null),
                null), false);

        assertThat(paths(validator.validate(request)))
                .containsExactly("moveTo", "overrides[1].issueId", "overrides[1].moveTo", "overrides[2].<list element>");
    }

    @Test
    void elTextoDeUnaNotaDeLaRetroVaDe1A500() {
        assertThat(paths(validator.validate(new RetroNoteRequest(null, " ", null)))).containsExactly("column", "text");
        assertThat(paths(validator.validate(new UpdateRetroNoteRequest("x".repeat(501), null, null))))
                .containsExactly("text");
        assertThat(paths(validator.validate(new UpdateRetroNoteRequest("", null, null)))).containsExactly("text");
        assertThat(validator.validate(new UpdateRetroNoteRequest(null, UUID.randomUUID(), null))).isEmpty();
        assertThat(validator.validate(new RetroNoteRequest(RetroColumn.ACTION, "x".repeat(500), UUID.randomUUID())))
                .isEmpty();
        assertThat(paths(validator.validate(new DailyNoteRequest("x".repeat(5001))))).containsExactly("text");
        assertThat(validator.validate(new DailyNoteRequest(null))).isEmpty();
    }

    @Test
    void validaLaFormaDeLosFiltrosDeUnaVista() {
        assertThat(paths(validator.validate(new CreateSavedViewRequest("Vista", false, filters("kanban", null)))))
                .containsExactly("filters.view");
        assertThat(paths(validator.validate(new CreateSavedViewRequest("Vista", false, filters(null, "drop table;")))))
                .containsExactly("filters.sort");
        assertThat(validator.validate(new CreateSavedViewRequest("Vista", false, filters("board", "dueDate,asc"))))
                .isEmpty();
        assertThat(validator.validate(new CreateSavedViewRequest("Vista", false, filters("list", "priority"))))
                .isEmpty();
        assertThat(validator.validate(new CreateSavedViewRequest("Vista", false,
                filters("list", "priorityRank,asc"))))
                .isEmpty();
        assertThat(paths(validator.validate(new CreateSavedViewRequest("Vista", false,
                filters("list", null, "estado")))))
                .containsExactly("filters.groupBy");
        assertThat(validator.validate(new CreateSavedViewRequest("Vista", false, filters("list", null, "assignee"))))
                .isEmpty();
    }
}
