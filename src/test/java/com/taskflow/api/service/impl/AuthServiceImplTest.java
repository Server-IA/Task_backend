package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.ChangePasswordRequest;
import com.taskflow.api.dto.request.RegisterRequest;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.email.EmailNotifier;
import com.taskflow.api.entity.RefreshToken;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.VerificationToken;
import com.taskflow.api.entity.enums.VerificationTokenType;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.UnauthorizedException;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.repository.RefreshTokenRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.VerificationTokenRepository;
import com.taskflow.api.security.JwtService;
import com.taskflow.api.util.TokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private VerificationTokenRepository verificationTokenRepository;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private EmailNotifier emailNotifier;
    @Mock
    private UserMapper userMapper;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt("secreto-de-pruebas-con-mas-de-32-caracteres", 15, 7),
                new AppProperties.Mail("resend", "", "TaskFlow <test@test.com>"),
                "http://localhost:5173",
                "America/Bogota",
                new AppProperties.Tokens(24, 60, 7));
        authService = new AuthServiceImpl(userRepository, refreshTokenRepository, verificationTokenRepository,
                authenticationManager, passwordEncoder, jwtService, emailNotifier, userMapper, properties);
    }

    private static User user(String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setFullName("Ana");
        user.setPasswordHash("hash-actual");
        user.setEmailVerified(true);
        return user;
    }

    @Nested
    class Register {

        @Test
        void rechazaCorreoDuplicadoSinImportarMayusculas() {
            when(userRepository.existsByEmail("ana@test.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(
                    new RegisterRequest("  Ana@Test.com ", "Clave1234", "Ana")))
                    .isInstanceOf(ConflictException.class);
            verify(userRepository, never()).save(any());
        }

        @Test
        void guardaUsuarioConContrasenaHasheadaYEnviaVerificacion() {
            when(userRepository.existsByEmail("ana@test.com")).thenReturn(false);
            when(passwordEncoder.encode("Clave1234")).thenReturn("bcrypt-hash");

            authService.register(new RegisterRequest("Ana@Test.com", "Clave1234", " Ana Pérez "));

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getEmail()).isEqualTo("ana@test.com");
            assertThat(saved.getValue().getPasswordHash()).isEqualTo("bcrypt-hash");
            assertThat(saved.getValue().getFullName()).isEqualTo("Ana Pérez");
            assertThat(saved.getValue().isEmailVerified()).isFalse();

            // El token se guarda hasheado y el correo recibe el valor en claro
            ArgumentCaptor<VerificationToken> token = ArgumentCaptor.forClass(VerificationToken.class);
            verify(verificationTokenRepository).save(token.capture());
            ArgumentCaptor<String> rawToken = ArgumentCaptor.forClass(String.class);
            verify(emailNotifier).sendEmailVerification(eq(saved.getValue()), rawToken.capture());
            assertThat(token.getValue().getType()).isEqualTo(VerificationTokenType.EMAIL_VERIFICATION);
            assertThat(token.getValue().getTokenHash()).isEqualTo(TokenUtils.sha256(rawToken.getValue()));
        }
    }

    @Nested
    class VerifyEmail {

        @Test
        void marcaElCorreoComoVerificado() {
            User user = user("ana@test.com");
            user.setEmailVerified(false);
            VerificationToken token = new VerificationToken();
            token.setUser(user);
            token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
            when(verificationTokenRepository.findByTokenHashAndType(
                    TokenUtils.sha256("abc"), VerificationTokenType.EMAIL_VERIFICATION))
                    .thenReturn(Optional.of(token));

            authService.verifyEmail("abc");

            assertThat(user.isEmailVerified()).isTrue();
            assertThat(token.isUsed()).isTrue();
        }

        @Test
        void rechazaTokenExpirado() {
            VerificationToken token = new VerificationToken();
            token.setUser(user("ana@test.com"));
            token.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
            when(verificationTokenRepository.findByTokenHashAndType(anyString(), any()))
                    .thenReturn(Optional.of(token));

            assertThatThrownBy(() -> authService.verifyEmail("abc"))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    class Refresh {

        @Test
        void rotaElTokenYEmiteUnoNuevo() {
            User user = user("ana@test.com");
            RefreshToken stored = new RefreshToken();
            stored.setUser(user);
            stored.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
            when(refreshTokenRepository.findByTokenHash(TokenUtils.sha256("rt"))).thenReturn(Optional.of(stored));
            when(jwtService.generateAccessToken(user.getId(), user.getEmail())).thenReturn("nuevo-access");

            AuthResponse response = authService.refresh("rt");

            assertThat(stored.isRevoked()).isTrue();
            assertThat(response.accessToken()).isEqualTo("nuevo-access");
            assertThat(response.refreshToken()).isNotBlank().isNotEqualTo("rt");
            verify(refreshTokenRepository).save(any(RefreshToken.class));
        }

        @Test
        void reutilizarUnTokenRotadoRevocaTodasLasSesiones() {
            User user = user("ana@test.com");
            RefreshToken stored = new RefreshToken();
            stored.setUser(user);
            stored.setRevoked(true);
            stored.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> authService.refresh("rt"))
                    .isInstanceOf(UnauthorizedException.class);
            verify(refreshTokenRepository).revokeAllByUserId(user.getId());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        void rechazaTokenDesconocido() {
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh("rt"))
                    .isInstanceOf(UnauthorizedException.class);
        }
    }

    @Nested
    class Passwords {

        @Test
        void forgotPasswordNoRevelaSiElCorreoExiste() {
            when(userRepository.findByEmail("nadie@test.com")).thenReturn(Optional.empty());

            authService.forgotPassword("nadie@test.com");

            verifyNoInteractions(emailNotifier);
        }

        @Test
        void changePasswordExigeLaContrasenaActual() {
            User user = user("ana@test.com");
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("mala", "hash-actual")).thenReturn(false);

            assertThatThrownBy(() -> authService.changePassword(user.getId(),
                    new ChangePasswordRequest("mala", "Nueva12345")))
                    .isInstanceOf(BadRequestException.class);
            verify(refreshTokenRepository, never()).deleteAllByUserId(any());
        }

        @Test
        void changePasswordCierraSesionesYAvisaPorCorreo() {
            User user = user("ana@test.com");
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("Clave1234", "hash-actual")).thenReturn(true);
            when(passwordEncoder.matches("Nueva12345", "hash-actual")).thenReturn(false);
            when(passwordEncoder.encode("Nueva12345")).thenReturn("hash-nuevo");

            authService.changePassword(user.getId(), new ChangePasswordRequest("Clave1234", "Nueva12345"));

            assertThat(user.getPasswordHash()).isEqualTo("hash-nuevo");
            verify(refreshTokenRepository).deleteAllByUserId(user.getId());
            verify(emailNotifier).sendPasswordChanged(user);
        }
    }
}
