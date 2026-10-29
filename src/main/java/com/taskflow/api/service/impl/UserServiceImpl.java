package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.UpdateProfileRequest;
import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        return userMapper.toResponse(findUser(userId));
    }

    @Override
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = findUser(userId);
        if (request.fullName() != null) {
            if (request.fullName().isBlank()) {
                throw new BadRequestException("El nombre no puede estar vacío");
            }
            user.setFullName(request.fullName().trim());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl().isBlank() ? null : request.avatarUrl().trim());
        }
        return userMapper.toResponse(user);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
    }
}
