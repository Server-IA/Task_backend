package com.taskflow.api.github;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Las dos piezas de criptografía de la integración: leer la clave privada de la app (con ella se firma el token con el
 * que TaskFlow se presenta ante GitHub) y comprobar la firma de los avisos que manda GitHub.
 */
public final class GithubKeys {

    /** Cabecera de PKCS#8 para una clave RSA: lo que le falta al formato PKCS#1 en que GitHub entrega la clave. */
    private static final byte[] RSA_ALGORITHM = {0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D,
            0x01, 0x01, 0x01, 0x05, 0x00};

    private GithubKeys() {
    }

    /**
     * La clave privada de un archivo {@code .pem}. GitHub la entrega en PKCS#1 ({@code BEGIN RSA PRIVATE KEY}), que
     * Java no lee directamente: se envuelve en PKCS#8. Una ya convertida ({@code BEGIN PRIVATE KEY}) se lee tal cual.
     */
    public static PrivateKey privateKey(String pem) {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? wrap(der) : der));
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new IllegalStateException("La clave privada de la GitHub App no se puede leer: debe ser el archivo .pem que genera GitHub", ex);
        }
    }

    /** PKCS#1 → PKCS#8: SEQUENCE { versión 0, algoritmo RSA, OCTET STRING { clave } }. */
    private static byte[] wrap(byte[] pkcs1) {
        byte[] key = concat(new byte[]{0x04}, length(pkcs1.length), pkcs1);
        byte[] content = concat(new byte[]{0x02, 0x01, 0x00}, RSA_ALGORITHM, key);
        return concat(new byte[]{0x30}, length(content.length), content);
    }

    /** La longitud en DER: un byte si es menor que 128; si no, cuántos bytes ocupa y luego esos bytes. */
    private static byte[] length(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        if (length < 0x100) {
            return new byte[]{(byte) 0x81, (byte) length};
        }
        if (length < 0x10000) {
            return new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) length};
        }
        return new byte[]{(byte) 0x83, (byte) (length >> 16), (byte) (length >> 8), (byte) length};
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, at, part.length);
            at += part.length;
        }
        return result;
    }

    /**
     * La firma (HMAC SHA-256, en hexadecimal) con la que TaskFlow marca el enlace para instalar la app: al volver de
     * GitHub se sabe que el workspace y la persona que trae son los que TaskFlow puso. Lleva un prefijo propio para que
     * una firma de aviso nunca sirva como firma de enlace.
     */
    public static String sign(String secret, String text) {
        return sign(secret, "install", text);
    }

    /**
     * La firma de un enlace para un propósito ({@code install}: instalar la app en un workspace; {@code link}: enlazar
     * la cuenta de una persona). La de un propósito no sirve para otro.
     */
    public static String sign(String secret, String purpose, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("taskflow-" + purpose + ":" + text).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo firmar el enlace", ex);
        }
    }

    /** Si esa es la firma de ese texto; la comparación no depende de en qué carácter difieren. */
    public static boolean signed(String secret, String text, String signature) {
        return signed(secret, "install", text, signature);
    }

    public static boolean signed(String secret, String purpose, String text, String signature) {
        return signature != null && MessageDigest.isEqual(sign(secret, purpose, text).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Cifra un token para guardarlo (AES-GCM con una clave derivada del secreto): {@code base64(iv + cifrado)}. Quien
     * lea la base de datos sin el secreto del servidor no obtiene tokens de GitHub.
     */
    public static String seal(String secret, String text) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, tokenKey(secret), new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(concat(iv, cipher.doFinal(text.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo cifrar el token", ex);
        }
    }

    /** Lo contrario de {@link #seal}. Falla si el texto se alteró o el secreto cambió. */
    public static String open(String secret, String sealed) {
        try {
            byte[] all = Base64.getDecoder().decode(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, tokenKey(secret), new GCMParameterSpec(128, all, 0, 12));
            return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo leer el token guardado: el secreto de la app cambió", ex);
        }
    }

    private static SecretKeySpec tokenKey(String secret) throws GeneralSecurityException {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(("taskflow-github-tokens:" + secret).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    /**
     * Si el aviso lo firmó GitHub con el secreto de la app: la cabecera {@code X-Hub-Signature-256} trae
     * {@code sha256=<HMAC del cuerpo>}. La comparación no depende de en qué byte difieren.
     */
    public static boolean validSignature(String secret, byte[] body, String header) {
        if (secret == null || secret.isEmpty() || header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] received = HexFormat.of().parseHex(header.substring("sha256=".length()).strip());
            return MessageDigest.isEqual(expected, received);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            return false;
        }
    }
}
