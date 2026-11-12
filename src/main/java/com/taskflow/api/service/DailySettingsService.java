package com.taskflow.api.service;

import com.taskflow.api.dto.request.DailySettingsRequest;
import com.taskflow.api.dto.response.DailySettingsResponse;

import java.util.UUID;

/**
 * Ajustes de la daily de un proyecto: tiempo por persona (general y por persona), qué bloques se exponen y los
 * puntos a tratar.
 */
public interface DailySettingsService {

    /** Los ajustes guardados o, si el proyecto nunca los cambió, los valores por defecto. */
    DailySettingsResponse get(String slug, UUID projectId);

    /** Reemplaza los ajustes del proyecto. */
    DailySettingsResponse save(String slug, UUID projectId, UUID userId, DailySettingsRequest request);
}
