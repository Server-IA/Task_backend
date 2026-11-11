package com.taskflow.api.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La GitHub App de TaskFlow (prefijo {@code app.github}). Es una sola para toda la instalación de TaskFlow; cada
 * workspace la instala en su organización o cuenta.
 *
 * @param appId          el identificador numérico de la app
 * @param appSlug        su nombre en la dirección pública ({@code github.com/apps/<slug>})
 * @param privateKeyPath ruta al {@code .pem} con su clave privada
 * @param webhookSecret  el secreto con el que GitHub firma sus avisos
 * @param apiUrl         la API de GitHub (otra solo en las pruebas)
 * @param webUrl         el sitio de GitHub, para armar enlaces
 */
@ConfigurationProperties(prefix = "app.github")
public record GithubProperties(String appId, String appSlug, String privateKeyPath, String webhookSecret, String apiUrl,
                               String webUrl) {

    public GithubProperties {
        appId = clean(appId);
        appSlug = clean(appSlug);
        privateKeyPath = clean(privateKeyPath);
        webhookSecret = clean(webhookSecret);
        apiUrl = trimSlash(apiUrl == null || apiUrl.isBlank() ? "https://api.github.com" : apiUrl.strip());
        webUrl = trimSlash(webUrl == null || webUrl.isBlank() ? "https://github.com" : webUrl.strip());
    }

    /** Si están todos los datos de la app: sin ellos la integración está apagada. */
    public boolean configured() {
        return !appId.isEmpty() && !appSlug.isEmpty() && !privateKeyPath.isEmpty() && !webhookSecret.isEmpty();
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
