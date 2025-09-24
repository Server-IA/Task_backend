package com.kanstad.task.exception;

import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.validation.FieldError;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    private String msg(String codeOrRaw, Object[] args, Locale locale) {
        return messageSource.getMessage(codeOrRaw, args, codeOrRaw, locale);
    }

    private String requestPath(WebRequest request) {
        if (request instanceof ServletWebRequest swr) {
            return swr.getRequest().getRequestURI();
        }
        // WebRequest.getDescription(false) -> "uri=/path"
        String desc = request.getDescription(false);
        return (desc != null && desc.startsWith("uri=")) ? desc.substring(4) : desc;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFoundException(
            NotFoundException ex, WebRequest request, Locale locale) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", 404);
        body.put("error", "Not Found");

        // Usar el código y args de tu NotFoundException
        String message = msg(ex.getCode(), ex.getArgs(), locale);
        body.put("message", message);

        body.put("path", requestPath(request));

        return ResponseEntity.status(404).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", 400);
        body.put("error", "Bad Request");
        body.put("message", "Error de validación");
        body.put("path", requestPath(request));

        // Agregar errores de validación específicos
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        body.put("validationErrors", errors);

        return ResponseEntity.status(400).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(
            ResponseStatusException ex, HttpServletRequest request) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", ex.getStatusCode().value());
        body.put("error", ex.getStatusCode().toString());

        // 👇 Solo agregar "message" si viene definido
        if (ex.getReason() != null && !ex.getReason().isBlank()) {
            body.put("message", ex.getReason());
        }

        body.put("path", request.getRequestURI());

        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

}