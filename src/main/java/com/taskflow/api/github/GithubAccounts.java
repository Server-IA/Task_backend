package com.taskflow.api.github;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.github.GithubClient.UserToken;
import com.taskflow.api.github.GithubStore.Account;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static com.taskflow.api.github.GithubClient.text;

/**
 * La cuenta de GitHub de cada persona. Quien la enlaza autoriza a la app de TaskFlow a actuar en su nombre; desde
 * entonces lo que hace en GitHub desde TaskFlow (crear una rama, ejecutar, repetir o cancelar un workflow) se hace con
 * su cuenta: sale a su nombre y GitHub solo lo deja donde ella tiene permiso. Sin cuenta enlazada se puede ver, no
 * actuar.
 */
@Slf4j
@Service
public class GithubAccounts {

    public static final String NOT_LINKED = "Conecta tu cuenta de GitHub en tu perfil para hacer esto";
    private static final String EXPIRED = "Tu conexión con GitHub caducó. Vuelve a conectar tu cuenta en tu perfil.";
    private static final String NOT_CONFIGURED = "La integración con GitHub no está configurada en este servidor";
    private static final String REJECTED = "GitHub no aceptó la autorización. Vuelve a intentarlo desde tu perfil.";
    /** Para qué es la firma del enlace: no sirve como firma del enlace de instalación ni de un aviso. */
    private static final String PURPOSE = "link";
    /** Marca interna: GitHub no respondió al renovar (no es un token; un token nunca tiene esta forma). */
    private static final String UNAVAILABLE = "::github-no-disponible::";
    /** Cuánto vale el enlace para autorizar (segundos). */
    private static final long STATE_SECONDS = 900;
    /** Con este margen antes de que caduque, el token se renueva. */
    private static final long RENEW_SECONDS = 120;

    private final GithubProperties properties;
    private final AppProperties appProperties;
    private final GithubClient client;
    private final GithubStore store;
    private final TransactionTemplate ownTransaction;

    public GithubAccounts(GithubProperties properties, AppProperties appProperties, GithubClient client, GithubStore store,
                          PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.appProperties = appProperties;
        this.client = client;
        this.store = store;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param configured si el servidor tiene los datos de la GitHub App (sin ellos no se puede enlazar)
     * @param profileUrl el perfil de la persona en GitHub
     */
    public record MyAccount(boolean configured, boolean linked, String login, String avatarUrl, String profileUrl, Instant linkedAt) {
    }

    @Transactional(readOnly = true)
    public MyAccount status(UUID userId) {
        return store.account(userId)
                .map(account -> new MyAccount(properties.configured(), true, account.login(), account.avatarUrl(),
                        properties.webUrl() + "/" + account.login(), account.createdAt()))
                .orElseGet(() -> new MyAccount(properties.configured(), false, null, null, null, null));
    }

    /** Si la persona tiene su cuenta enlazada. */
    @Transactional(readOnly = true)
    public boolean linked(UUID userId) {
        return store.account(userId).isPresent();
    }

    /** La dirección de GitHub donde la persona autoriza a la app; lleva firmado para quién es. */
    public String linkUrl(UUID userId) {
        if (!properties.configured()) {
            throw new ConflictException(NOT_CONFIGURED);
        }
        String payload = userId + "|" + Instant.now().plusSeconds(STATE_SECONDS).getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String state = encoded + "." + GithubKeys.sign(properties.webhookSecret(), PURPOSE, encoded);
        return properties.webUrl() + "/login/oauth/authorize?client_id=" + encode(properties.clientId())
                + "&redirect_uri=" + encode(appProperties.frontendUrl() + "/github/callback") + "&state=" + state;
    }

    /**
     * Termina de enlazar la cuenta con lo que GitHub trajo de vuelta. Solo vale para quien pidió el enlace: si otra
     * persona llega con él (alguien le pasó su enlace para que autorizara), no se enlaza nada.
     */
    @Transactional
    public MyAccount complete(UUID userId, String code, String state) {
        if (!properties.configured()) {
            throw new ConflictException(NOT_CONFIGURED);
        }
        if (code == null || code.isBlank() || state == null || !state.contains(".")) {
            throw new BadRequestException(REJECTED);
        }
        String encoded = state.substring(0, state.indexOf('.'));
        if (!GithubKeys.signed(properties.webhookSecret(), PURPOSE, encoded, state.substring(state.indexOf('.') + 1))) {
            throw new BadRequestException(REJECTED);
        }
        String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|");
        if (!parts[0].equals(userId.toString())) {
            throw new BadRequestException("Ese enlace lo pidió otra persona. Conecta tu cuenta desde tu propio perfil.");
        }
        if (Long.parseLong(parts[1]) < Instant.now().getEpochSecond()) {
            throw new BadRequestException("El enlace para conectar tu cuenta caducó. Vuelve a intentarlo desde tu perfil.");
        }
        UserToken token;
        JsonNode user;
        try {
            token = client.exchangeCode(code.strip());
            user = client.user(token.accessToken());
        } catch (GithubException | IllegalStateException ex) {
            log.info("GitHub: no se pudo enlazar la cuenta de {}: {}", userId, ex.getMessage());
            throw new BadRequestException(REJECTED);
        }
        long githubId = user.path("id").asLong(0);
        if (githubId == 0) {
            throw new BadRequestException(REJECTED);
        }
        if (store.accountOwner(githubId).filter(owner -> !owner.equals(userId)).isPresent()) {
            throw new ConflictException("Esa cuenta de GitHub ya está conectada a otro usuario de TaskFlow");
        }
        String avatar = text(user, "avatar_url");
        store.saveAccount(userId, githubId, text(user, "login"), avatar.isEmpty() ? null : avatar, seal(token.accessToken()),
                token.refreshToken() == null ? null : seal(token.refreshToken()), token.accessExpiresAt(), token.refreshExpiresAt());
        return status(userId);
    }

    /** Desenlaza la cuenta: TaskFlow olvida sus tokens y le pide a GitHub que los anule. */
    @Transactional
    public void unlink(UUID userId) {
        store.account(userId).ifPresent(account -> {
            try {
                client.revoke(open(account.accessToken()));
            } catch (GithubException | IllegalStateException ex) {
                // Si GitHub no responde, igual queda desenlazada aquí
                log.info("GitHub: no se pudo anular el token de {}: {}", account.login(), ex.getMessage());
            }
            store.deleteAccount(userId);
        });
    }

    /**
     * El token con el que la persona actúa en GitHub, renovado si está por caducar. Si no tiene cuenta enlazada, o
     * GitHub ya no acepta sus tokens (la persona quitó la autorización allá, o pasaron meses), se le dice que la conecte.
     */
    @Transactional
    public String token(UUID userId) {
        Account account = store.account(userId).orElseThrow(() -> new ConflictException(NOT_LINKED));
        Instant now = Instant.now();
        if (account.accessExpiresAt() == null || account.accessExpiresAt().isAfter(now.plusSeconds(RENEW_SECONDS))) {
            return open(account.accessToken());
        }
        // Renovar gasta el token de refresco: lo nuevo se guarda en una transacción propia, así no se pierde si lo que la
        // persona estaba haciendo falla después (se quedaría con un refresco ya gastado y habría que reconectar). La fila
        // se bloquea para que dos acciones a la vez no renueven con el mismo token.
        String renewed = ownTransaction.execute(status -> {
            Account locked = store.accountForUpdate(userId).orElse(null);
            if (locked == null) {
                return null;
            }
            if (locked.accessExpiresAt() == null || locked.accessExpiresAt().isAfter(Instant.now().plusSeconds(RENEW_SECONDS))) {
                // Otra petición lo renovó mientras esta esperaba
                return open(locked.accessToken());
            }
            if (locked.refreshToken() == null || (locked.refreshExpiresAt() != null && locked.refreshExpiresAt().isBefore(Instant.now()))) {
                store.deleteAccount(userId);
                return null;
            }
            UserToken fresh;
            try {
                fresh = client.refresh(open(locked.refreshToken()));
            } catch (GithubException ex) {
                if (ex.status() == 0 || ex.status() >= 500) {
                    return UNAVAILABLE;
                }
                store.deleteAccount(userId);
                return null;
            }
            store.saveAccountTokens(userId, seal(fresh.accessToken()), fresh.refreshToken() == null ? null : seal(fresh.refreshToken()),
                    fresh.accessExpiresAt(), fresh.refreshExpiresAt());
            return fresh.accessToken();
        });
        if (renewed == null) {
            throw new ConflictException(EXPIRED);
        }
        if (UNAVAILABLE.equals(renewed)) {
            throw new ConflictException("GitHub no respondió. Inténtalo de nuevo en un momento.");
        }
        return renewed;
    }

    /**
     * Si la cuenta de GitHub de la persona puede ver esa instalación de la app (es de su organización o de su cuenta).
     * Con esto se comprueba que quien conecta un workspace es de verdad quien instaló la app.
     *
     * @return {@code false} si no tiene cuenta enlazada o GitHub no se la muestra
     */
    @Transactional
    public boolean canAccessInstallation(UUID userId, long installationId) {
        if (store.account(userId).isEmpty()) {
            return false;
        }
        try {
            return client.userCanAccess(token(userId), installationId);
        } catch (GithubException | ConflictException | IllegalStateException ex) {
            log.info("GitHub: no se pudo comprobar la instalación {} con la cuenta de {}: {}", installationId, userId, ex.getMessage());
            return false;
        }
    }

    private String seal(String token) {
        return GithubKeys.seal(properties.clientSecret(), token);
    }

    private String open(String sealed) {
        return GithubKeys.open(properties.clientSecret(), sealed);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
