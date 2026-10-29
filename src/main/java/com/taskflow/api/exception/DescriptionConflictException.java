package com.taskflow.api.exception;

import com.taskflow.api.dto.response.IssueResponse;
import lombok.Getter;

/**
 * La descripción cambió después de la versión de la que partió quien la guarda (409). Lleva la tarea como está ahora
 * para que el cliente muestre la otra versión, quién la guardó y cuándo.
 */
@Getter
public class DescriptionConflictException extends ConflictException {

    public static final String MESSAGE = "Alguien más cambió la descripción mientras la editabas";

    private final transient IssueResponse current;

    public DescriptionConflictException(IssueResponse current) {
        super(MESSAGE);
        this.current = current;
    }
}
