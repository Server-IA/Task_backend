package com.taskflow.api.imports.source;

import com.taskflow.api.imports.ImportJson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Las peticiones a las herramientas de origen: JSON de ida y vuelta, descarga de archivos, reintento cuando el origen
 * pide esperar (429 o 503 con {@code Retry-After}) y un mensaje para la persona cuando no se puede seguir.
 * <p>
 * Solo sale por HTTPS y nunca hacia la red interna (direcciones locales o privadas): la dirección del origen la
 * escribe una persona y los enlaces de descarga vienen en las respuestas, así que no se confía en ninguno de los dos.
 * Las redirecciones se siguen a mano, con la misma comprobación en cada salto y sin llevar las credenciales a otro
 * servidor.
 */
@Slf4j
@Component
public class SourceHttp {

    public static final String REJECTED = "El origen rechazó las credenciales: revísalas y vuelve a conectar";
    public static final String FORBIDDEN = "Esa cuenta no tiene permiso para leer esto en el origen";
    public static final String NOT_FOUND = "No se encontró en el origen: revisa la dirección";
    public static final String BUSY = "El origen pidió esperar por demasiadas peticiones. Inténtalo de nuevo en unos minutos.";
    public static final String UNREACHABLE = "No se pudo conectar con el origen. Inténtalo de nuevo.";
    public static final String BAD_ADDRESS = "Esa dirección no se puede usar";

    private static final int ATTEMPTS = 4;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration MAX_WAIT = Duration.ofSeconds(30);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /** Solo en pruebas: deja salir por HTTP hacia la propia máquina (el servidor falso). */
    private final boolean allowLocal;

    public SourceHttp() {
        this(false);
    }

    SourceHttp(boolean allowLocal) {
        this.allowLocal = allowLocal;
    }

    public JsonNode getJson(String url, Map<String, String> headers) {
        return json(request(url, headers).GET().build());
    }

    public JsonNode postJson(String url, Map<String, String> headers, Object body) {
        return json(request(url, headers).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ImportJson.write(body), StandardCharsets.UTF_8)).build());
    }

    private JsonNode json(HttpRequest request) {
        HttpResponse<byte[]> response = send(request);
        try {
            return ImportJson.MAPPER.readTree(response.body());
        } catch (JacksonException ex) {
            throw new SourceException(UNREACHABLE, ex);
        }
    }

    /**
     * Descarga un archivo, siguiendo las redirecciones del origen (las credenciales solo viajan al servidor al que se
     * le pidió el archivo).
     *
     * @param maxBytes si pesa más, no se termina de bajar y falla
     */
    public byte[] download(String url, Map<String, String> headers, long maxBytes) {
        String current = url;
        Map<String, String> currentHeaders = headers;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = request(current, currentHeaders).GET().build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException ex) {
                throw new SourceException(UNREACHABLE, ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SourceException(UNREACHABLE, ex);
            }
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElseThrow(() -> new SourceException(UNREACHABLE));
                    URI next = request.uri().resolve(location);
                    if (!next.getHost().equalsIgnoreCase(request.uri().getHost())) {
                        currentHeaders = Map.of();
                    }
                    current = next.toString();
                    continue;
                }
                if (status != 200) {
                    throw failure(status);
                }
                byte[] content = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                if (content.length > maxBytes) {
                    throw new SourceException(UNREACHABLE);
                }
                return content;
            } catch (IOException ex) {
                throw new SourceException(UNREACHABLE, ex);
            }
        }
        throw new SourceException(UNREACHABLE);
    }

    private HttpRequest.Builder request(String url, Map<String, String> headers) {
        URI uri = safe(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "application/json");
        headers.forEach(builder::header);
        return builder;
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        Duration wait = Duration.ofSeconds(2);
        for (int attempt = 1; ; attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException ex) {
                if (attempt >= ATTEMPTS) {
                    throw new SourceException(UNREACHABLE, ex);
                }
                pause(wait);
                wait = wait.multipliedBy(2);
                continue;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SourceException(UNREACHABLE, ex);
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response;
            }
            if ((status == 429 || status == 503) && attempt < ATTEMPTS) {
                Duration asked = response.headers().firstValue("Retry-After").map(SourceHttp::seconds).orElse(wait);
                pause(asked.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : asked);
                wait = wait.multipliedBy(2);
                continue;
            }
            log.info("Origen: {} {} respondió {}", request.method(), request.uri().getHost() + request.uri().getPath(), status);
            throw failure(status);
        }
    }

    private static SourceException failure(int status) {
        return new SourceException(switch (status) {
            case 401 -> REJECTED;
            case 403 -> FORBIDDEN;
            case 404, 410 -> NOT_FOUND;
            case 429 -> BUSY;
            default -> UNREACHABLE;
        });
    }

    private static Duration seconds(String header) {
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(header.strip())));
        } catch (NumberFormatException notSeconds) {
            return Duration.ofSeconds(5);
        }
    }

    private static void pause(Duration time) {
        try {
            Thread.sleep(time.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SourceException(UNREACHABLE, ex);
        }
    }

    /** La dirección, si es HTTPS y no apunta a la red interna. */
    private URI safe(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new SourceException(BAD_ADDRESS, ex);
        }
        if (uri.getHost() == null) {
            throw new SourceException(BAD_ADDRESS);
        }
        if (allowLocal) {
            return uri;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SourceException(BAD_ADDRESS);
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress() || isUniqueLocal(address)) {
                    throw new SourceException(BAD_ADDRESS);
                }
            }
        } catch (UnknownHostException ex) {
            throw new SourceException(UNREACHABLE, ex);
        }
        return uri;
    }

    /** IPv6 de red privada (fc00::/7), que {@link InetAddress#isSiteLocalAddress()} no cubre. */
    private static boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
