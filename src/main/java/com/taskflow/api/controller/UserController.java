package com.taskflow.api.controller;

import com.taskflow.api.dto.request.AvatarUploadRequest;
import com.taskflow.api.dto.request.ChangePasswordRequest;
import com.taskflow.api.dto.request.UpdateProfileRequest;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.dto.response.UploadTicketResponse;
import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.AuthService;
import com.taskflow.api.service.FileService;
import com.taskflow.api.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Usuario", description = "Perfil del usuario autenticado")
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuthService authService;
    private final FileService fileService;

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

    @Operation(summary = "Pedir la subida de mi foto de perfil (URL firmada)",
            description = "La foto ya recortada en el navegador (512 × 512, WebP; también PNG o JPG) de hasta 5 MB. "
                    + "Se manda directo a R2 con PUT y las cabeceras indicadas, y después se confirma. Las fotos no "
                    + "cuentan para el espacio de ningún workspace.")
    @PostMapping("/avatar")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadTicketResponse startAvatarUpload(@AuthenticationPrincipal UserPrincipal me,
                                                  @Valid @RequestBody AvatarUploadRequest request) {
        return fileService.createAvatarUpload(me.getId(), request);
    }

    @Operation(summary = "Confirmar mi foto de perfil",
            description = "Comprueba la subida en R2 y la pone en el perfil: avatarUrl pasa a ser /files/<id> (su URL "
                    + "se pide con GET /api/v1/files/urls). La foto anterior se borra.")
    @PostMapping("/avatar/{fileId}/confirm")
    public UserResponse confirmAvatar(@AuthenticationPrincipal UserPrincipal me, @PathVariable UUID fileId) {
        return fileService.confirmAvatar(me.getId(), fileId);
    }

    @Operation(summary = "Quitar mi foto de perfil", description = "Vuelven las iniciales.")
    @DeleteMapping("/avatar")
    public UserResponse removeAvatar(@AuthenticationPrincipal UserPrincipal me) {
        return fileService.removeAvatar(me.getId());
    }

    @Operation(summary = "Cambiar mi contraseña",
            description = "Cierra las demás sesiones y devuelve un par de tokens nuevo para la sesión actual.")
    @PatchMapping("/password")
    public AuthResponse changePassword(@AuthenticationPrincipal UserPrincipal me,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(me.getId(), request);
    }
}
