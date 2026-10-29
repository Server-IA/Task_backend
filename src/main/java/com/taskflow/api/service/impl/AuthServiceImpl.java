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
import com.taskflow.api.exception.UnauthorizedException;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.ratelimit.RateLimitRule;
import com.taskflow.api.ratelimit.RateLimitService;
import com.taskflow.api.repository.RefreshTokenRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.VerificationTokenRepository;
import com.taskflow.api.security.CredentialsGuard;
import com.taskflow.api.security.JwtService;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.AuthService;
import com.taskflow.api.util.ClientInfo;
import com.taskflow.api.util.TokenUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE = "Bearer";

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
        // Además del límite por IP, uno por cuenta: repartir los intentos entre muchas IPs no sirve para adivinar la
        // contraseña. Solo gastan intento las contraseñas incorrectas.
        rateLimitService.ensureAvailable(RateLimitRule.LOGIN_ACCOUNT, email);
        Authentication authentication;
        try {
            // Lanza BadCredentials, Disabled (sin verificar) o Locked (inactivo); el handler global los traduce.
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (BadCredentialsException ex) {
            rateLimitService.tryConsume(RateLimitRule.LOGIN_ACCOUNT, email);
            throw ex;
        }
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new UnauthorizedException("Correo o contraseña incorrectos"));

        AuthResponse response = issueTokens(user);
        emailNotifier.sendLoginAlert(user, client, Instant.now());
        return response;
    }

    @Override
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse refresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenUtils.sha256(refreshToken))
                .orElseThrow(() -> new UnauthorizedException("Refresh token inválido"));

        if (stored.isRevoked()) {
            // Reutilizar un token ya rotado indica que pudo ser robado: se cierran todas las sesiones.
            log.warn("Refresh token reutilizado para el usuario {}; se revocan todas sus sesiones",
                    stored.getUser().getId());
            refreshTokenRepository.revokeAllByUserId(stored.getUser().getId());
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
        return issueTokens(user);
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
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        VerificationToken verification = findUsableToken(request.token(), VerificationTokenType.PASSWORD_RESET,
                "El enlace para restablecer la contraseña no es válido o ya expiró");
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
            throw new BadRequestException("La contraseña actual no es correcta");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException("La nueva contraseña debe ser distinta de la actual");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        credentialsChanged(user);
        emailNotifier.sendPasswordChanged(user);
        // Emitidos después del cambio (al segundo), así que siguen valiendo
        return issueTokens(user);
    }

    /**
     * La contraseña cambió: se cierran todas las sesiones (refresh tokens) y dejan de valer los access tokens emitidos
     * hasta ahora. La marca va al segundo, como el «iat» de los JWT: los que se emitan desde ya valen.
     */
    private void credentialsChanged(User user) {
        user.setCredentialsChangedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        refreshTokenRepository.deleteAllByUserId(user.getId());
        credentialsGuard.evict(user.getId());
    }

    private AuthResponse issueTokens(User user) {
        String rawRefreshToken = TokenUtils.generateToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(TokenUtils.sha256(rawRefreshToken));
        refreshToken.setExpiresAt(Instant.now().plus(Duration.ofDays(properties.jwt().refreshExpirationDays())));
        refreshTokenRepository.save(refreshToken);

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
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
