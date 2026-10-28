package com.taskflow.api.dto.request;

import com.taskflow.api.entity.SavedViewFilters;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los elementos de la checklist y los filtros de las vistas se validan dentro del cuerpo de la petición.
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
        return new SavedViewFilters(null, null, null, null, null, view, sort);
    }

    @Test
    void validaCadaElementoDeLaChecklistDeUnaTareaNueva() {
        CreateIssueRequest request = new CreateIssueRequest("Tarea", null, null, null, null, null, null, null, null,
                List.of(new ChecklistItemRequest(ChecklistItemKind.CHECK, "Bien"),
                        new ChecklistItemRequest(null, "  "),
                        new ChecklistItemRequest(ChecklistItemKind.CRITERION, "x".repeat(501))));

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
    void validaLaFormaDeLosFiltrosDeUnaVista() {
        assertThat(paths(validator.validate(new CreateSavedViewRequest("Vista", false, filters("kanban", null)))))
                .containsExactly("filters.view");
        assertThat(paths(validator.validate(new CreateSavedViewRequest("Vista", false, filters(null, "drop table;")))))
                .containsExactly("filters.sort");
        assertThat(validator.validate(new CreateSavedViewRequest("Vista", false, filters("board", "dueDate,asc"))))
                .isEmpty();
        assertThat(validator.validate(new CreateSavedViewRequest("Vista", false, filters("list", "priority"))))
                .isEmpty();
    }
}
