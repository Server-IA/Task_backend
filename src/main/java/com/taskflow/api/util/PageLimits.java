package com.taskflow.api.util;

import com.taskflow.api.exception.BadRequestException;
import org.springframework.data.domain.Pageable;

/**
 * Páginas que se pueden pedir. Spring Data rechaza un desplazamiento ({@code page × size}) mayor que
 * {@link Integer#MAX_VALUE} con una excepción que llegaba como 500 ({@code ?page=99999999&size=200}).
 */
public final class PageLimits {

    static final String TOO_FAR = "La página pedida está demasiado lejos: usa un número de página más bajo";

    private PageLimits() {
    }

    /** El mismo {@code pageable}, o 400 si su desplazamiento no cabe en un entero. */
    public static Pageable reachable(Pageable pageable) {
        if (pageable.isPaged() && pageable.getOffset() > Integer.MAX_VALUE) {
            throw new BadRequestException(TOO_FAR);
        }
        return pageable;
    }
}
