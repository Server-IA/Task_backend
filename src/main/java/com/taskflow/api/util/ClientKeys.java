package com.taskflow.api.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Clave de un cliente para los límites de peticiones. Una IPv4 cuenta tal cual; una IPv6, por su prefijo /64: quien
 * tiene una conexión IPv6 suele controlar todo su /64 (2^64 direcciones), así que contarlas una a una permitiría
 * saltarse el límite cambiando de dirección.
 */
public final class ClientKeys {

    /** Solo literales IPv6: nunca se resuelve un nombre (no hay consultas DNS). */
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    private ClientKeys() {
    }

    /** La IPv4, el prefijo /64 de una IPv6 («2001:db8:1:2::/64») o, si no es una IP, el texto recibido. */
    public static String ip(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "desconocida";
        }
        String address = remoteAddress.trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        int zone = address.indexOf('%');
        if (zone >= 0) {
            address = address.substring(0, zone);
        }
        if (address.indexOf(':') < 0 || !IPV6_LITERAL.matcher(address).matches()) {
            return address;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            if (!(parsed instanceof Inet6Address)) {
                // IPv4 dentro de IPv6 (::ffff:1.2.3.4)
                return parsed.getHostAddress();
            }
            byte[] bytes = parsed.getAddress();
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                prefix.append(Integer.toHexString(((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF))).append(':');
            }
            return prefix.append(":/64").toString().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException ex) {
            return address;
        }
    }
}
