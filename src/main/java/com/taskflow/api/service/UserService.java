package com.taskflow.api.service;

import com.taskflow.api.dto.request.UpdateProfileRequest;
import com.taskflow.api.dto.response.UserResponse;

import java.util.UUID;

public interface UserService {

    UserResponse getProfile(UUID userId);

    UserResponse updateProfile(UUID userId, UpdateProfileRequest request);

    /** Guarda el idioma de la cuenta ({@code es} o {@code en}): el de la aplicación y el de sus correos. */
    UserResponse updateLanguage(UUID userId, String language);
}
