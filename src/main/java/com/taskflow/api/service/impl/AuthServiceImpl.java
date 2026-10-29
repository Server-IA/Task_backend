package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.ChangePasswordRequest;
import com.taskflow.api.dto.request.LoginRequest;
import com.taskflow.api.dto.request.RegisterRequest;
import com.taskflow.api.dto.request.ResetPasswordRequest;
import com.taskflow.api.dto.response.AuthResponse;
import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.email.EmailNotifier;
import com.taskflow.api.entity.RefreshToken;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.VerificationToken;
import com.taskflow.api.entity.enums.VerificationTokenType;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
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
import com.taskflow.api.service.AuthService;
import com.taskflow.api.util.ClientInfo;
import com.taskflow.api.util.ClientKeys;
import com.taskflow.api.util.TokenUtils;
import io.github.bucket4j.ConsumptionProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE = "Bearer";
    static final String WRONG_CURRENT_PASSWORD = "La contraseña actual no es correcta";
    static final String INVALID_RESET_TOKEN = "El enlace para restablecer la contraseña no es válido o ya expiró";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailNotifier emailNotifier;
    private final UserMapper userMapper;
    private final AppProperties properties;
    private final RateLimitService rateLimitService;
    private final CredentialsGuard credentialsGuard;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Ya existe una cuenta con ese correo");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName().trim());
        userRepository.save(user);

        String token = createVerificationToken(user, VerificationTokenType.EMAIL_VERIFICATION,
                Duration.ofHours(properties.tokens().emailVerificationHours()));
        emailNotifier.sendEmailVerification(user, token);
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public void verifyEmail(String token) {
        VerificationToken verification = findUsableToken(token, VerificationTokenType.EMAIL_VERIFICATION,
                "El enlace de verificación no es válido o ya expiró");
        verification.setUsed(true);
        verification.getUser().setEmailVerified(true);
    }

    @Override
    @Transactional
    public void resendVerification(String email) {
        userRepository.findByEmail(normalizeEmail(email))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> {
                    String token = createVerificationToken(user, VerificationTokenType.EMAIL_VERIFICATION,
                            Duration.ofHours(properties.tokens().emailVerificationHours()));
                    emailNotifier.sendEmailVerification(user, token);
                });
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request, ClientInfo client) {
        String email = normalizeEmail(request.email());
        // Además del límite por IP (RateLimitFilter), dos por cuenta. Cada intento gasta uno de cada antes de comprobar
        // la contraseña (así no se saltan con muchas peticiones a la vez) y un login correcto los devuelve:
        //  - por cuenta e IP: quien prueba contraseñas desde una IP se bloquea solo a sí mismo, no a la dueña;
        //  - por cuenta, desde cualquier IP: un techo más alto que frena repartir los intentos entre muchas IPs.
        String accountAndIp = email + "|" + ClientKeys.ip(client.ip());
        consumeLoginAttempt(RateLimitRule.LOGIN_ACCOUNT_IP, accountAndIp, null, null);
        consumeLoginAttempt(RateLimitRule.LOGIN_ACCOUNT, email, RateLimitRule.LOGIN_ACCOUNT_IP, accountAndIp);

        // Lanza BadCredentials, Disabled (sin verificar) o Locked (inactivo); el handler global los traduce. Cualquier
        // fallo deja gastados los intentos.
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        // Con la fila bloqueada: si la contraseña cambió mientras se comprobaba (un restablecimiento a la vez), la
        // sesión no se abre; si el cambio llega después, espera a que esta termine y la cierra.
        LockedCredentials credentials = userRepository.lockCredentials(principal.getId())
                .filter(locked -> locked.getPasswordHash().equals(principal.getPassword()))
                .orElseThrow(() -> new UnauthorizedException("Correo o contraseña incorrectos"));
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new UnauthorizedException("Correo o contraseña incorrectos"));
        rateLimitService.refund(RateLimitRule.LOGIN_ACCOUNT_IP, accountAndIp);
        rateLimitService.refund(RateLimitRule.LOGIN_ACCOUNT, email);

        AuthResponse response = issueTokens(user, credentials.getCredentialsVersion());
        emailNotifier.sendLoginAlert(user, client, Instant.now());
        return response;
    }

    /**
     * Gasta un intento de login de la regla o lanza 429. Si se rechaza, devuelve el intento ya gastado de
     * {@code previous} (ese intento no llegó a comprobar la contraseña).
     */
    private void consumeLoginAttempt(RateLimitRule rule, String key, RateLimitRule previous, String previousKey) {
        ConsumptionProbe probe = rateLimitService.tryConsume(rule, key);
        if (!probe.isConsumed()) {
            if (previous != null) {
                rateLimitService.refund(previous, previousKey);
            }
            throw new TooManyRequestsException(RateLimitService.seconds(probe.getNanosToWaitForRefill()));
        }
    }

    @Override
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse refresh(String refreshToken) {
        String hash = TokenUtils.sha256(refreshToken);
        UUID userId = refreshTokenRepository.findUserIdByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("Refresh token inválido"));
        // Primero la fila del usuario, como el cambio de contraseña: si hay uno en curso, se espera a que termine y
        // entonces el token ya no existe. Sin este bloqueo, un refresh simultáneo podía crear un token nuevo que el
        // borrado del cambio de contraseña no veía.
        LockedCredentials credentials = userRepository.lockCredentials(userId)
                .orElseThrow(() -> new UnauthorizedException("Refresh token inválido"));
        // Se lee después del bloqueo (es lo primero que carga esta transacción): ve lo que dejó un cambio de contraseña
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("Refresh token inválido"));

        if (stored.isRevoked()) {
            // Reutilizar un token ya rotado indica que pudo ser robado: se cierran todas las sesiones.
            log.warn("Refresh token reutilizado para el usuario {}; se revocan todas sus sesiones", userId);
            refreshTokenRepository.revokeAllByUserId(userId);
            throw new UnauthorizedException("Refresh token inválido");
        }
        if (!stored.isUsable(Instant.now())) {
            throw new UnauthorizedException("El refresh token expiró, inicia sesión de nuevo");
        }

        User user = stored.getUser();
        if (!user.isActive()) {
            throw new UnauthorizedException("La cuenta está desactivada");
        }
        // Atómico: si dos peticiones usan el mismo token a la vez, solo una lo rota; la otra cuenta como reutilización.
        if (refreshTokenRepository.revokeIfActive(stored.getId()) == 0) {
            log.warn("Refresh token usado dos veces a la vez por el usuario {}; se revocan todas sus sesiones",
                    user.getId());
            refreshTokenRepository.revokeAllByUserId(user.getId());
            throw new UnauthorizedException("Refresh token inválido");
        }
        return issueTokens(user, credentials.getCredentialsVersion());
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteByTokenHash(TokenUtils.sha256(refreshToken));
    }

    @Override
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(normalizeEmail(email))
                .filter(User::isActive)
                .ifPresent(user -> {
                    String token = createVerificationToken(user, VerificationTokenType.PASSWORD_RESET,
                            Duration.ofMinutes(properties.tokens().passwordResetMinutes()));
                    emailNotifier.sendPasswordReset(user, token);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public void validateResetToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException(INVALID_RESET_TOKEN);
        }
        findUsableToken(token, VerificationTokenType.PASSWORD_RESET, INVALID_RESET_TOKEN);
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        VerificationToken verification = findUsableToken(request.token(), VerificationTokenType.PASSWORD_RESET,
                INVALID_RESET_TOKEN);
        verification.setUsed(true);

        User user = verification.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Quien recibió el correo demostró ser dueño del email.
        user.setEmailVerified(true);
        credentialsChanged(user);
        emailNotifier.sendPasswordChanged(user);
    }

    @Override
    @Transactional
    public AuthResponse changePassword(UUID userId, ChangePasswordRequest request) {
        // Con una sesión robada no se puede probar la contraseña actual sin límite
        rateLimitService.consume(RateLimitRule.CHANGE_PASSWORD, userId.toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            // Como error del campo: el formulario marca «Contraseña actual»
            throw BadRequestException.forField("currentPassword", WRONG_CURRENT_PASSWORD);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException("La nueva contraseña debe ser distinta de la actual");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        credentialsChanged(user);
        emailNotifier.sendPasswordChanged(user);
        // Con la versión nueva de las credenciales, así que siguen valiendo
        return issueTokens(user);
    }

    /**
     * La contraseña cambió: se cierran todas las sesiones (refresh tokens) y dejan de valer los access tokens emitidos
     * hasta ahora (sube la versión de las credenciales; los que se emitan desde ya llevan la nueva).
     * <p>
     * Bloquea la fila del usuario antes de borrar los refresh tokens, igual que {@link #refresh} y {@link #login}
     * antes de crear uno: un refresh o un login simultáneo termina antes (y su token se borra aquí) o espera y ve
     * el cambio. La versión se lee de la fila bloqueada, no de la entidad en memoria.
     */
    private void credentialsChanged(User user) {
        int current = userRepository.lockCredentials(user.getId())
                .map(LockedCredentials::getCredentialsVersion)
                .orElse(user.getCredentialsVersion());
        user.setCredentialsVersion(current + 1);
        user.setCredentialsChangedAt(Instant.now());
        refreshTokenRepository.deleteAllByUserId(user.getId());
        credentialsGuard.evict(user.getId());
    }

    private AuthResponse issueTokens(User user) {
        return issueTokens(user, user.getCredentialsVersion());
    }

    private AuthResponse issueTokens(User user, int credentialsVersion) {
        String rawRefreshToken = TokenUtils.generateToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(TokenUtils.sha256(rawRefreshToken));
        refreshToken.setExpiresAt(Instant.now().plus(Duration.ofDays(properties.jwt().refreshExpirationDays())));
        refreshTokenRepository.save(refreshToken);

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), credentialsVersion);
        return new AuthResponse(accessToken, rawRefreshToken, TOKEN_TYPE,
                jwtService.getAccessExpirationSeconds(), userMapper.toResponse(user));
    }

    /** Crea un token nuevo (invalidando los anteriores del mismo tipo) y devuelve su valor en claro. */
    private String createVerificationToken(User user, VerificationTokenType type, Duration validity) {
        verificationTokenRepository.invalidateAll(user.getId(), type);

        String rawToken = TokenUtils.generateToken();
        VerificationToken token = new VerificationToken();
        token.setUser(user);
        token.setType(type);
        token.setTokenHash(TokenUtils.sha256(rawToken));
        token.setExpiresAt(Instant.now().plus(validity));
        verificationTokenRepository.save(token);
        return rawToken;
    }

    private VerificationToken findUsableToken(String rawToken, VerificationTokenType type, String errorMessage) {
        return verificationTokenRepository.findByTokenHashAndType(TokenUtils.sha256(rawToken), type)
                .filter(token -> token.isUsable(Instant.now()))
                .orElseThrow(() -> new BadRequestException(errorMessage));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
