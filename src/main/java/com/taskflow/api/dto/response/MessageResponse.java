package com.taskflow.api.dto.response;

import com.taskflow.api.i18n.Language;

public record MessageResponse(String message) {

    /** Con el texto (escrito en español) en el idioma de la petición. */
    public static MessageResponse of(String spanish) {
        return new MessageResponse(Language.current().t(spanish));
    }
}
