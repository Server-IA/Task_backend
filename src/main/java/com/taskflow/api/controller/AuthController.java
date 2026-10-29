package com.taskflow.api.controller;

import com.taskflow.api.dto.request.EmailRequest;
import com.taskflow.api.dto.request.LoginRequest;
import com.taskflow.api.dto.request.RefreshTokenRequest;
import com.taskflow.api.dto.request.RegisterRequest;
import com.taskflow.api.dto.request.ResetPasswordRequest;
import com.taskflow.api.dto.request.TokenRequest;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.dto.response.MessageResponse;
import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.service.AuthService;
import com.taskflow.api.util.ClientInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Registro, inicio de sesión y recuperación de contraseña")
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Crear cuenta", description = "Envía un correo de verificación.")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(summary = "Verificar email con el token del correo")
    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody TokenRequest request) {
        authService.verifyEmail(request.token());
        return new MessageResponse("Correo verificado. Ya puedes iniciar sesión");
    }

    @Operation(summary = "Reenviar correo de verificación")
    @PostMapping("/resend-verification")
    public MessageResponse resendVerification(@Valid @RequestBody EmailRequest request) {
        authService.resendVerification(request.email());
        return new MessageResponse("Si la cuenta existe y no está verificada, te enviamos un nuevo correo");
    }

    @Operation(summary = "Iniciar sesión", description = "Devuelve accessToken (15 min) y refreshToken (7 días).")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, ClientInfo.from(httpRequest));
    }

    @Operation(summary = "Renovar tokens", description = "El refresh token usado queda revocado.")
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @Operation(summary = "Cerrar sesión (revoca el refresh token)")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
    }

    @Operation(summary = "Pedir enlace para restablecer la contraseña")
    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody EmailRequest request) {
        authService.forgotPassword(request.email());
        return new MessageResponse("Si el correo está registrado, te enviamos un enlace para restablecer la contraseña");
    }

    @Operation(summary = "Comprobar el enlace para restablecer la contraseña",
            description = "No usa el token: 204 si sirve; 400 (con el mismo mensaje que el restablecimiento) si falta, "
                    + "no es válido, ya se usó o venció.")
    @GetMapping("/reset-password/validate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validateResetToken(@RequestParam(defaultValue = "") String token) {
        authService.validateResetToken(token);
    }

    @Operation(summary = "Restablecer la contraseña con el token del correo")
    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return new MessageResponse("Contraseña actualizada. Ya puedes iniciar sesión");
    }
}
