package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.ChangePasswordRequest;
import com.taskflow.api.dto.request.LoginRequest;
import com.taskflow.api.dto.request.ResetPasswordRequest;
import com.taskflow.api.dto.request.RegisterRequest;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.email.EmailNotifier;
import com.taskflow.api.entity.RefreshToken;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.VerificationToken;
import com.taskflow.api.entity.enums.VerificationTokenType;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.TooManyRequestsException;
import com.taskflow.api.exception.UnauthorizedException;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.ratelimit.RateLimitRule;
import com.taskflow.api.ratelimit.RateLimitService;
import com.taskflow.api.repository.LockedCredentials;
import com.taskflow.api.repository.RefreshTokenRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.VerificationTokenRepository;
import com.taskflow.api.security.CredentialsGuard;
import com.taskflow.api.security.JwtService;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.util.ClientInfo;
import com.taskflow.api.util.TokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Mock
    private CredentialsGuard credentialsGuard;

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
                authenticationManager, passwordEncoder, jwtService, emailNotifier, userMapper, properties,
                rateLimitService, credentialsGuard);
    }

    private final RateLimitService rateLimitService = new RateLimitService();

    /** La fila del usuario bloqueada ({@code UserRepository.lockCredentials}). */
    private static LockedCredentials locked(String passwordHash, int credentialsVersion) {
        return new LockedCredentials() {
            @Override
            public String getPasswordHash() {
                return passwordHash;
            }

            @Override
            public int getCredentialsVersion() {
                return credentialsVersion;
            }
        };
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
            stored.setId(UUID.randomUUID());
            when(refreshTokenRepository.findUserIdByTokenHash(TokenUtils.sha256("rt")))
                    .thenReturn(Optional.of(user.getId()));
            when(userRepository.lockCredentials(user.getId())).thenReturn(Optional.of(locked("hash-actual", 2)));
            when(refreshTokenRepository.findByTokenHash(TokenUtils.sha256("rt"))).thenReturn(Optional.of(stored));
            when(refreshTokenRepository.revokeIfActive(stored.getId())).thenReturn(1);
            when(jwtService.generateAccessToken(user.getId(), user.getEmail(), 2)).thenReturn("nuevo-access");

            AuthResponse response = authService.refresh("rt");

            // Primero la fila del usuario (como el cambio de contraseña) y después el token, ya leído con el bloqueo
            InOrder order = inOrder(userRepository, refreshTokenRepository);
            order.verify(userRepository).lockCredentials(user.getId());
            order.verify(refreshTokenRepository).findByTokenHash(TokenUtils.sha256("rt"));
            // La rotación es un UPDATE condicional (atómico), no un cambio en la entidad leída
            verify(refreshTokenRepository).revokeIfActive(stored.getId());
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
            lockedRefresh(user, stored);

            assertThatThrownBy(() -> authService.refresh("rt"))
                    .isInstanceOf(UnauthorizedException.class);
            verify(refreshTokenRepository).revokeAllByUserId(user.getId());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        void siOtraPeticionLoRotoAlMismoTiempoCuentaComoReutilizado() {
            User user = user("ana@test.com");
            RefreshToken stored = new RefreshToken();
            stored.setId(UUID.randomUUID());
            stored.setUser(user);
            stored.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
            lockedRefresh(user, stored);
            // La otra petición ya lo marcó como usado entre la lectura y el UPDATE
            when(refreshTokenRepository.revokeIfActive(stored.getId())).thenReturn(0);

            assertThatThrownBy(() -> authService.refresh("rt"))
                    .isInstanceOf(UnauthorizedException.class);
            verify(refreshTokenRepository).revokeAllByUserId(user.getId());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        void rechazaTokenDesconocido() {
            when(refreshTokenRepository.findUserIdByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh("rt"))
                    .isInstanceOf(UnauthorizedException.class);
            verify(userRepository, never()).lockCredentials(any());
        }

        @Test
        void siUnCambioDeContrasenaBorroElTokenMientrasEsperabaNoCreaOtro() {
            // El token existía al buscar su usuario, pero un cambio de contraseña lo borró antes del bloqueo
            UUID userId = UUID.randomUUID();
            when(refreshTokenRepository.findUserIdByTokenHash(anyString())).thenReturn(Optional.of(userId));
            when(userRepository.lockCredentials(userId)).thenReturn(Optional.of(locked("hash-nuevo", 1)));
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh("rt"))
                    .isInstanceOf(UnauthorizedException.class);
            verify(refreshTokenRepository, never()).save(any());
            // No es una reutilización: no se cierran las sesiones nuevas
            verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        }

        private void lockedRefresh(User user, RefreshToken stored) {
            when(refreshTokenRepository.findUserIdByTokenHash(anyString())).thenReturn(Optional.of(user.getId()));
            when(userRepository.lockCredentials(user.getId())).thenReturn(Optional.of(locked("hash-actual", 0)));
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));
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
                    .isInstanceOf(BadRequestException.class)
                    // QA: como error del campo, para que el formulario marque «Contraseña actual»
                    .extracting(ex -> ((BadRequestException) ex).getErrors())
                    .isEqualTo(java.util.Map.of("currentPassword", "La contraseña actual no es correcta"));
            verify(refreshTokenRepository, never()).deleteAllByUserId(any());
        }

        @Test
        void changePasswordCierraSesionesYAvisaPorCorreo() {
            User user = user("ana@test.com");
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("Clave1234", "hash-actual")).thenReturn(true);
            when(passwordEncoder.matches("Nueva12345", "hash-actual")).thenReturn(false);
            when(passwordEncoder.encode("Nueva12345")).thenReturn("hash-nuevo");
            // La entidad en memoria está vieja: la versión que cuenta es la de la fila bloqueada
            when(userRepository.lockCredentials(user.getId())).thenReturn(Optional.of(locked("hash-actual", 4)));
            when(jwtService.generateAccessToken(user.getId(), user.getEmail(), 5)).thenReturn("access-v5");

            Instant before = Instant.now();
            AuthResponse response = authService.changePassword(user.getId(),
                    new ChangePasswordRequest("Clave1234", "Nueva12345"));

            assertThat(user.getPasswordHash()).isEqualTo("hash-nuevo");
            // Bloquea la fila antes de borrar las sesiones (como refresh y login antes de crear una)
            InOrder order = inOrder(userRepository, refreshTokenRepository);
            order.verify(userRepository).lockCredentials(user.getId());
            order.verify(refreshTokenRepository).deleteAllByUserId(user.getId());
            verify(emailNotifier).sendPasswordChanged(user);
            // Los access tokens anteriores dejan de valer (sube la versión); el de esta sesión lleva la nueva
            assertThat(user.getCredentialsVersion()).isEqualTo(5);
            assertThat(user.getCredentialsChangedAt()).isAfterOrEqualTo(before);
            assertThat(response.accessToken()).isEqualTo("access-v5");
            verify(credentialsGuard).evict(user.getId());
        }

        @Test
        void changePasswordLimitaLosIntentos() {
            User user = user("ana@test.com");
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), eq("hash-actual"))).thenReturn(false);
            ChangePasswordRequest request = new ChangePasswordRequest("mala", "Nueva12345");

            for (int i = 0; i < RateLimitRule.CHANGE_PASSWORD.getCapacity(); i++) {
                assertThatThrownBy(() -> authService.changePassword(user.getId(), request))
                        .isInstanceOf(BadRequestException.class);
            }
            assertThatThrownBy(() -> authService.changePassword(user.getId(), request))
                    .isInstanceOf(TooManyRequestsException.class);
        }

        @Test
        void resetPasswordInvalidaLosAccessTokensAnteriores() {
            User user = user("ana@test.com");
            VerificationToken token = new VerificationToken();
            token.setUser(user);
            token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
            when(verificationTokenRepository.findByTokenHashAndType(
                    TokenUtils.sha256("abc"), VerificationTokenType.PASSWORD_RESET)).thenReturn(Optional.of(token));
            when(passwordEncoder.encode("Nueva12345")).thenReturn("hash-nuevo");

            when(userRepository.lockCredentials(user.getId())).thenReturn(Optional.of(locked("hash-actual", 0)));

            authService.resetPassword(new ResetPasswordRequest("abc", "Nueva12345"));

            assertThat(user.getCredentialsChangedAt()).isNotNull();
            assertThat(user.getCredentialsVersion()).isEqualTo(1);
            verify(refreshTokenRepository).deleteAllByUserId(user.getId());
            verify(credentialsGuard).evict(user.getId());
        }
    }

    @Nested
    class Login {

        private final LoginRequest wrong = new LoginRequest("Ana@Test.com", "mala");

        private ClientInfo from(String ip) {
            return new ClientInfo(ip, "curl");
        }

        /** El login de {@code user} con la contraseña correcta funciona (con la fila bloqueada y la versión 0). */
        private void correctPassword(User user) {
            when(authenticationManager.authenticate(argThat(token -> token != null && !"mala".equals(token.getCredentials()))))
                    .thenAnswer(invocation -> new UsernamePasswordAuthenticationToken(UserPrincipal.fromUser(user),
                            null, java.util.List.of()));
            when(userRepository.lockCredentials(user.getId())).thenReturn(Optional.of(locked("hash-actual", 0)));
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        }

        @Test
        void unaIpQuePruebaContrasenasSeBloqueaSoloASiMisma() {
            User user = user("ana@test.com");
            when(authenticationManager.authenticate(argThat(token -> token != null && "mala".equals(token.getCredentials()))))
                    .thenThrow(new BadCredentialsException("mala"));
            correctPassword(user);

            for (int i = 0; i < RateLimitRule.LOGIN_ACCOUNT_IP.getCapacity(); i++) {
                assertThatThrownBy(() -> authService.login(wrong, from("203.0.113.7")))
                        .isInstanceOf(BadCredentialsException.class);
            }
            // Desde esa IP ya no se prueba ni la contraseña correcta…
            assertThatThrownBy(() -> authService.login(new LoginRequest("ana@test.com", "Clave1234"),
                    from("203.0.113.7")))
                    .isInstanceOf(TooManyRequestsException.class);
            // …pero la dueña entra desde la suya
            authService.login(new LoginRequest("ana@test.com", "Clave1234"), from("198.51.100.20"));
            verify(refreshTokenRepository).save(any(RefreshToken.class));
        }

        @Test
        void lasIpv6DelMismoPrefijo64CuentanComoUna() {
            when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("mala"));

            for (int i = 0; i < RateLimitRule.LOGIN_ACCOUNT_IP.getCapacity(); i++) {
                ClientInfo client = from("2001:db8:1:2::" + Integer.toHexString(i + 1));
                assertThatThrownBy(() -> authService.login(wrong, client))
                        .isInstanceOf(BadCredentialsException.class);
            }
            assertThatThrownBy(() -> authService.login(wrong, from("2001:db8:1:2:ffff::9")))
                    .isInstanceOf(TooManyRequestsException.class);
            // Otro /64 tiene su propio límite
            assertThatThrownBy(() -> authService.login(wrong, from("2001:db8:1:3::1")))
                    .isInstanceOf(BadCredentialsException.class);
        }

        @Test
        void hayUnTechoPorCuentaDesdeCualquierIp() {
            when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("mala"));

            for (int i = 0; i < RateLimitRule.LOGIN_ACCOUNT.getCapacity(); i++) {
                ClientInfo client = from("10.0." + (i / 200) + "." + (i % 200));
                assertThatThrownBy(() -> authService.login(wrong, client))
                        .isInstanceOf(BadCredentialsException.class);
            }
            // Ya no se prueba la contraseña: 429 aunque venga de otra IP
            assertThatThrownBy(() -> authService.login(wrong, from("10.9.9.9")))
                    .isInstanceOf(TooManyRequestsException.class);
            verify(authenticationManager, times(RateLimitRule.LOGIN_ACCOUNT.getCapacity())).authenticate(any());
            // El intento rechazado por el techo no gastó el de esa IP
            assertThat(rateLimitService.tryConsume(RateLimitRule.LOGIN_ACCOUNT_IP, "ana@test.com|10.9.9.9")
                    .getRemainingTokens()).isEqualTo(RateLimitRule.LOGIN_ACCOUNT_IP.getCapacity() - 1);
        }

        @Test
        void losIntentosEnCursoYaCuentanAunqueLleguenALaVez() {
            // Mientras se comprueba una contraseña (BCrypt tarda), llegan más intentos desde la misma IP: el que está
            // en curso ya gastó su intento, así que no entran más de los que permite el límite
            AtomicInteger depth = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            when(authenticationManager.authenticate(any())).thenAnswer(invocation -> {
                if (depth.getAndIncrement() == 0) {
                    for (int i = 0; i < RateLimitRule.LOGIN_ACCOUNT_IP.getCapacity(); i++) {
                        try {
                            authService.login(wrong, from("203.0.113.7"));
                        } catch (TooManyRequestsException ex) {
                            rejected.incrementAndGet();
                        } catch (BadCredentialsException ignored) {
                            // llegó a comprobar la contraseña
                        }
                    }
                }
                throw new BadCredentialsException("mala");
            });

            assertThatThrownBy(() -> authService.login(wrong, from("203.0.113.7")))
                    .isInstanceOf(BadCredentialsException.class);

            verify(authenticationManager, times(RateLimitRule.LOGIN_ACCOUNT_IP.getCapacity())).authenticate(any());
            assertThat(rejected).hasValue(1);
        }

        @Test
        void unLoginCorrectoDevuelveSuIntento() {
            User user = user("ana@test.com");
            when(authenticationManager.authenticate(argThat(token -> token != null && "mala".equals(token.getCredentials()))))
                    .thenThrow(new BadCredentialsException("mala"));
            correctPassword(user);
            LoginRequest right = new LoginRequest("ana@test.com", "Clave1234");

            for (int i = 0; i < RateLimitRule.LOGIN_ACCOUNT_IP.getCapacity() - 1; i++) {
                assertThatThrownBy(() -> authService.login(wrong, from("203.0.113.7")))
                        .isInstanceOf(BadCredentialsException.class);
            }
            for (int i = 0; i < 5; i++) {
                authService.login(right, from("203.0.113.7"));
            }
            // Queda exactamente un intento: los logins correctos no gastaron ninguno
            assertThatThrownBy(() -> authService.login(wrong, from("203.0.113.7")))
                    .isInstanceOf(BadCredentialsException.class);
            assertThatThrownBy(() -> authService.login(right, from("203.0.113.7")))
                    .isInstanceOf(TooManyRequestsException.class);
        }

        @Test
        void siLaContrasenaCambioMientrasSeComprobabaNoAbreSesion() {
            User user = user("ana@test.com");
            when(authenticationManager.authenticate(any()))
                    .thenAnswer(invocation -> new UsernamePasswordAuthenticationToken(UserPrincipal.fromUser(user),
                            null, java.util.List.of()));
            // Al bloquear la fila, el hash ya es otro (un restablecimiento terminó entre medio)
            when(userRepository.lockCredentials(user.getId())).thenReturn(Optional.of(locked("hash-nuevo", 1)));

            assertThatThrownBy(() -> authService.login(new LoginRequest("ana@test.com", "Clave1234"),
                    from("203.0.113.7")))
                    .isInstanceOf(UnauthorizedException.class);
            verify(refreshTokenRepository, never()).save(any());
            verifyNoInteractions(jwtService);
        }

        @Test
        void elAccessTokenLlevaLaVersionDeLaFilaBloqueada() {
            User user = user("ana@test.com");
            correctPassword(user);
            when(userRepository.lockCredentials(user.getId())).thenReturn(Optional.of(locked("hash-actual", 7)));
            when(jwtService.generateAccessToken(user.getId(), user.getEmail(), 7)).thenReturn("access-v7");

            AuthResponse response = authService.login(new LoginRequest("ana@test.com", "Clave1234"),
                    from("203.0.113.7"));

            assertThat(response.accessToken()).isEqualTo("access-v7");
        }
    }
}
