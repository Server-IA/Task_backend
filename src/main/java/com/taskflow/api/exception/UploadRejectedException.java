package com.taskflow.api.exception;

import org.springframework.http.HttpStatus;

/**
 * El archivo que llegó a R2 no es el que se firmó (otro tamaño, otro tipo o un contenido que no corresponde): se borra
 * y se responde 400 con {@code code: UPLOAD_REJECTED}.
 */
public class UploadRejectedException extends ApiException {

    public static final String CODE = "UPLOAD_REJECTED";

    public UploadRejectedException(String message) {
        super(HttpStatus.BAD_REQUEST, message, CODE);
    }
}
