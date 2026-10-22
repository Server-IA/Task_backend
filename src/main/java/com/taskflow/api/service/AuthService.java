package com.taskflow.api.service;

import com.taskflow.api.dto.request.ChangePasswordRequest;
import com.taskflow.api.dto.request.LoginRequest;
import com.taskflow.api.dto.request.RegisterRequest;
import com.taskflow.api.dto.request.ResetPasswordRequest;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.util.ClientInfo;

import java.util.UUID;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    void verifyEmail(String token);

    /** No revela si el correo existe: siempre termina sin error. */
    void resendVerification(String email);

    AuthResponse login(LoginRequest request, ClientInfo client);

    AuthResponse refresh(String refreshToken);

    void logout(String refreshToken);

    /** No revela si el correo existe: siempre termina sin error. */
    void forgotPassword(String email);

    void resetPassword(ResetPasswordRequest request);

    /** Cambia la contraseña, cierra las demás sesiones y devuelve un par de tokens nuevo. */
    AuthResponse changePassword(UUID userId, ChangePasswordRequest request);
}
