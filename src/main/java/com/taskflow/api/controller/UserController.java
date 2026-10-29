package com.taskflow.api.controller;

import com.taskflow.api.dto.request.ChangePasswordRequest;
import com.taskflow.api.dto.request.UpdateProfileRequest;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.AuthService;
import com.taskflow.api.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Usuario", description = "Perfil del usuario autenticado")
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    @Operation(summary = "Ver mi perfil")
    @GetMapping
    public UserResponse getProfile(@AuthenticationPrincipal UserPrincipal me) {
        return userService.getProfile(me.getId());
    }

    @Operation(summary = "Editar mi perfil", description = "Los campos nulos no se modifican.")
    @PatchMapping
    public UserResponse updateProfile(@AuthenticationPrincipal UserPrincipal me,
                                      @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(me.getId(), request);
    }

    @Operation(summary = "Cambiar mi contraseña",
            description = "Cierra las demás sesiones y devuelve un par de tokens nuevo para la sesión actual.")
    @PatchMapping("/password")
    public AuthResponse changePassword(@AuthenticationPrincipal UserPrincipal me,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(me.getId(), request);
    }
}
