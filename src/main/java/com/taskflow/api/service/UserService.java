package com.taskflow.api.service;

import com.taskflow.api.dto.request.UpdateProfileRequest;
import com.taskflow.api.dto.response.UserResponse;

import java.util.UUID;

public interface UserService {

    UserResponse getProfile(UUID userId);

    UserResponse updateProfile(UUID userId, UpdateProfileRequest request);
}
