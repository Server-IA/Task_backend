package com.taskflow.api.github;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La API de GitHub, como la GitHub App de TaskFlow. Hay dos formas de presentarse: como la app (un token firmado con
 * su clave privada, que dura minutos; sirve para saber de una instalación o quitarla) y como una instalación (un token
 * que GitHub entrega por una hora y da acceso a los repositorios que esa organización eligió). Ninguno se guarda: se
 * piden cuando hacen falta y el de instalación se reutiliza en memoria mientras dura.
 */
@Slf4j
@Component
public class GithubClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int PAGE = 100;
    /** Hasta cuántas páginas se siguen en un listado (10 000 elementos): de ahí en adelante no se pide más. */
    private static final int MAX_PAGES = 100;

    private final GithubProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, InstallationToken> tokens = new ConcurrentHashMap<>();
    private volatile PrivateKey privateKey;

    private record InstallationToken(String token, Instant expiresAt) {
    }

    public GithubClient(GithubProperties properties) {
        this.properties = properties;
    }

    // ----- Como la app

    /** La instalación: en qué cuenta está ({@code account.login}, {@code account.type}). */
    public JsonNode installation(long installationId) {
        return send("GET", "/app/installations/" + installationId, appToken(), null);
    }

    /** Quita la app de esa organización o cuenta: GitHub deja de mandar avisos. */
    public void uninstall(long installationId) {
        send("DELETE", "/app/installations/" + installationId, appToken(), null);
        tokens.remove(installationId);
    }

    // ----- Como una instalación

    /** Los repositorios que la instalación deja ver. */
    public List<JsonNode> repositories(long installationId) {
        return pages(installationId, "/installation/repositories?", "repositories");
    }

    public List<JsonNode> pullRequests(long installationId, String repository, int limit) {
        JsonNode page = get(installationId, "/repos/" + repository + "/pulls?state=all&sort=updated&direction=desc&per_page=" + Math.min(limit, PAGE));
        List<JsonNode> pulls = new ArrayList<>();
        page.forEach(pulls::add);
        return pulls;
    }

    public List<JsonNode> branches(long installationId, String repository) {
        return pages(installationId, "/repos/" + repository + "/branches?", null);
    }

    /** El commit en el que está una rama; {@code null} si la rama no existe. */
    public String branchSha(long installationId, String repository, String branch) {
        try {
            return text(get(installationId, "/repos/" + repository + "/git/ref/heads/" + path(branch)).path("object"), "sha");
        } catch (GithubException ex) {
            if (ex.status() == 404) {
                return null;
            }
            throw ex;
        }
    }

    public void createBranch(long installationId, String repository, String name, String sha) {
        send("POST", "/repos/" + repository + "/git/refs", installationToken(installationId), Map.of("ref", "refs/heads/" + name, "sha", sha));
    }

    public List<JsonNode> workflows(long installationId, String repository) {
        return pages(installationId, "/repos/" + repository + "/actions/workflows?", "workflows");
    }

    /** El contenido de un archivo del repositorio (el YAML de un workflow), o cadena vacía si no se puede leer. */
    public String fileContent(long installationId, String repository, String filePath, String ref) {
        try {
            JsonNode file = get(installationId, "/repos/" + repository + "/contents/" + path(filePath) + (ref == null ? "" : "?ref=" + encode(ref)));
            String content = text(file, "content").replaceAll("\\s", "");
            return new String(java.util.Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        } catch (GithubException | IllegalArgumentException ex) {
            return "";
        }
    }

    /** Pide a GitHub que ejecute un workflow en esa rama (GitHub no devuelve la ejecución: avisa cuando empieza). */
    public void dispatchWorkflow(long installationId, String repository, long workflowId, String ref, Map<String, String> inputs) {
        send("POST", "/repos/" + repository + "/actions/workflows/" + workflowId + "/dispatches", installationToken(installationId),
                inputs == null || inputs.isEmpty() ? Map.of("ref", ref) : Map.of("ref", ref, "inputs", inputs));
    }

    public List<JsonNode> workflowRuns(long installationId, String repository, int limit) {
        JsonNode page = get(installationId, "/repos/" + repository + "/actions/runs?per_page=" + Math.min(limit, PAGE));
        List<JsonNode> runs = new ArrayList<>();
        page.path("workflow_runs").forEach(runs::add);
        return runs;
    }

    public void rerun(long installationId, String repository, long runId) {
        send("POST", "/repos/" + repository + "/actions/runs/" + runId + "/rerun", installationToken(installationId), null);
    }

    public void cancel(long installationId, String repository, long runId) {
        send("POST", "/repos/" + repository + "/actions/runs/" + runId + "/cancel", installationToken(installationId), null);
    }

    /** Los checks de un commit (los pasos del pipeline de un PR). */
    public List<JsonNode> checkRuns(long installationId, String repository, String sha) {
        return pages(installationId, "/repos/" + repository + "/commits/" + sha + "/check-runs?", "check_runs");
    }

    // ----- Como una persona (su cuenta de GitHub enlazada)

    /**
     * Los tokens de una persona. El de acceso dura horas; el de refresco, meses. Si la app no hace caducar sus tokens,
     * solo viene el de acceso y no caduca.
     */
    public record UserToken(String accessToken, String refreshToken, Instant accessExpiresAt, Instant refreshExpiresAt) {
    }

    /** Cambia el código que GitHub entrega al autorizar por los tokens de la persona. */
    public UserToken exchangeCode(String code) {
        return oauth(Map.of("client_id", properties.clientId(), "client_secret", properties.clientSecret(), "code", code));
    }

    /** Pide tokens nuevos con el de refresco. */
    public UserToken refresh(String refreshToken) {
        return oauth(Map.of("client_id", properties.clientId(), "client_secret", properties.clientSecret(),
                "grant_type", "refresh_token", "refresh_token", refreshToken));
    }

    private UserToken oauth(Map<String, String> body) {
        JsonNode response = call("POST", properties.webUrl() + "/login/oauth/access_token", null, body);
        String access = text(response, "access_token");
        if (access.isEmpty()) {
            // GitHub responde 200 con el error adentro (código vencido, refresco anulado…)
            throw new GithubException(400, text(response, "error"), null);
        }
        Instant now = Instant.now();
        long accessSeconds = response.path("expires_in").asLong(0);
        long refreshSeconds = response.path("refresh_token_expires_in").asLong(0);
        String refresh = text(response, "refresh_token");
        return new UserToken(access, refresh.isEmpty() ? null : refresh, accessSeconds > 0 ? now.plusSeconds(accessSeconds) : null,
                refreshSeconds > 0 ? now.plusSeconds(refreshSeconds) : null);
    }

    /** Quién es la persona en GitHub ({@code id}, {@code login}, {@code avatar_url}). */
    public JsonNode user(String userToken) {
        return send("GET", "/user", userToken, null);
    }

    /** Si la persona puede ver esa instalación de la app (es de su cuenta o de una organización suya). */
    public boolean userCanAccess(String userToken, long installationId) {
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode list = send("GET", "/user/installations?per_page=" + PAGE + "&page=" + page, userToken, null).path("installations");
            for (JsonNode installation : list) {
                if (installation.path("id").asLong(0) == installationId) {
                    return true;
                }
            }
            if (list.size() < PAGE) {
                break;
            }
        }
        return false;
    }

    public void createBranchAs(String userToken, String repository, String name, String sha) {
        send("POST", "/repos/" + repository + "/git/refs", userToken, Map.of("ref", "refs/heads/" + name, "sha", sha));
    }

    public void dispatchWorkflowAs(String userToken, String repository, long workflowId, String ref, Map<String, String> inputs) {
        send("POST", "/repos/" + repository + "/actions/workflows/" + workflowId + "/dispatches", userToken,
                inputs == null || inputs.isEmpty() ? Map.of("ref", ref) : Map.of("ref", ref, "inputs", inputs));
    }

    public void rerunAs(String userToken, String repository, long runId) {
        send("POST", "/repos/" + repository + "/actions/runs/" + runId + "/rerun", userToken, null);
    }

    public void cancelAs(String userToken, String repository, long runId) {
        send("POST", "/repos/" + repository + "/actions/runs/" + runId + "/cancel", userToken, null);
    }

    /** Anula el token de una persona (al desenlazar su cuenta). */
    public void revoke(String userToken) {
        String basic = java.util.Base64.getEncoder()
                .encodeToString((properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
        call("DELETE", properties.apiUrl() + "/applications/" + properties.clientId() + "/token", "Basic " + basic, Map.of("access_token", userToken));
    }

    // ----- Peticiones

    private JsonNode get(long installationId, String path) {
        return send("GET", path, installationToken(installationId), null);
    }

    /** Un listado completo, página por página. {@code field}: dónde viene la lista ({@code null} = la respuesta es la lista). */
    private List<JsonNode> pages(long installationId, String path, String field) {
        List<JsonNode> items = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode response = get(installationId, path + "per_page=" + PAGE + "&page=" + page);
            JsonNode list = field == null ? response : response.path(field);
            list.forEach(items::add);
            if (list.size() < PAGE) {
                break;
            }
        }
        return items;
    }

    private JsonNode send(String method, String path, String token, Object body) {
        return call(method, properties.apiUrl() + path, "Bearer " + token, body);
    }

    private JsonNode call(String method, String url, String authorization, Object body) {
        String path = URI.create(url).getPath();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                // El intercambio de OAuth responde JSON solo si se le pide así
                .header("Accept", path.startsWith("/login/oauth/") ? "application/json" : "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "TaskFlow");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new GithubException(0, "sin conexión", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GithubException(0, "interrumpido", ex);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String detail = "";
            try {
                detail = text(JSON.readTree(response.body()), "message");
            } catch (JacksonException notJson) {
                // sin detalle
            }
            log.info("GitHub: {} {} respondió {} {}", method, path.replaceAll("\\?.*", ""), status, detail);
            throw new GithubException(status, detail, null);
        }
        if (response.body() == null || response.body().isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(response.body());
        } catch (JacksonException ex) {
            throw new GithubException(status, "respuesta ilegible", ex);
        }
    }

    // ----- Credenciales

    /** El token de la app: firmado con su clave privada, válido unos minutos. */
    private String appToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.appId())
                // Un minuto atrás, por si el reloj de este servidor va adelantado respecto al de GitHub
                .issuedAt(Date.from(now.minusSeconds(60)))
                .expiration(Date.from(now.plusSeconds(8 * 60)))
                .signWith(key(), Jwts.SIG.RS256)
                .compact();
    }

    private PrivateKey key() {
        PrivateKey key = privateKey;
        if (key == null) {
            try {
                key = GithubKeys.privateKey(Files.readString(Path.of(properties.privateKeyPath())));
            } catch (IOException ex) {
                throw new IllegalStateException("No se pudo leer la clave privada de la GitHub App en GITHUB_PRIVATE_KEY_PATH", ex);
            }
            privateKey = key;
        }
        return key;
    }

    private String installationToken(long installationId) {
        InstallationToken cached = tokens.get(installationId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(120))) {
            return cached.token();
        }
        JsonNode response = send("POST", "/app/installations/" + installationId + "/access_tokens", appToken(), null);
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(text(response, "expires_at"));
        } catch (java.time.format.DateTimeParseException ex) {
            expiresAt = Instant.now().plusSeconds(600);
        }
        InstallationToken fresh = new InstallationToken(text(response, "token"), expiresAt);
        tokens.put(installationId, fresh);
        return fresh.token();
    }

    /** El texto de un campo; cadena vacía si falta o no es texto ni número. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : value.isNumber() ? value.asString() : "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Una ruta con barras (una rama «feature/x», un archivo en una carpeta): cada tramo codificado por separado. */
    private static String path(String value) {
        StringBuilder out = new StringBuilder();
        for (String part : value.split("/")) {
            out.append(out.isEmpty() ? "" : "/").append(encode(part).replace("+", "%20"));
        }
        return out.toString();
    }
}
