package com.taskflow.api.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * IP y navegador desde los que se hace una petición (se usan en el correo de inicio de sesión).
 */
public record ClientInfo(String ip, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        return new ClientInfo(request.getRemoteAddr(), userAgent == null ? "" : userAgent);
    }

    /** Descripción corta del dispositivo, por ejemplo «Chrome en Windows». */
    public String device() {
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String browser;
        if (ua.contains("edg/")) {
            browser = "Edge";
        } else if (ua.contains("opr/") || ua.contains("opera")) {
            browser = "Opera";
        } else if (ua.contains("firefox/")) {
            browser = "Firefox";
        } else if (ua.contains("chrome/")) {
            browser = "Chrome";
        } else if (ua.contains("safari/")) {
            browser = "Safari";
        } else if (ua.contains("postman")) {
            browser = "Postman";
        } else if (ua.contains("curl")) {
            browser = "curl";
        } else {
            browser = "Navegador desconocido";
        }

        String os;
        if (ua.contains("android")) {
            os = "Android";
        } else if (ua.contains("iphone") || ua.contains("ipad")) {
            os = "iOS";
        } else if (ua.contains("windows")) {
            os = "Windows";
        } else if (ua.contains("mac os")) {
            os = "macOS";
        } else if (ua.contains("linux")) {
            os = "Linux";
        } else {
            return browser;
        }
        return browser + " en " + os;
    }
}
