package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.UpdateProfileRequest;
import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.FileMapper;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.FileService;
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
    private final FileService fileService;

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
        String avatarUrl = request.avatarUrl() == null ? null : request.avatarUrl().trim();
        if (avatarUrl != null && !avatarUrl.equals(user.getAvatarUrl())) {
            if (avatarUrl.startsWith(FileMapper.REFERENCE_PREFIX)) {
                throw new BadRequestException("Sube la foto desde tu perfil");
            }
            user.setAvatarUrl(avatarUrl.isEmpty() ? null : avatarUrl);
            // La foto subida que tenía ya no se usa
            fileService.deleteAvatars(userId);
        }
        return userMapper.toResponse(user);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
    }
}
