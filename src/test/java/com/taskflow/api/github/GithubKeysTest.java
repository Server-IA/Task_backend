package com.taskflow.api.github;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubKeysTest {

    /** La cabecera que PKCS#8 le pone a una clave RSA de 2048 bits: lo que queda después es la clave en PKCS#1. */
    private static final int PKCS8_HEADER = 26;

    static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** La clave como la entrega GitHub: PKCS#1, en un .pem con «BEGIN RSA PRIVATE KEY». */
    static String pkcs1Pem(KeyPair pair) {
        byte[] pkcs8 = pair.getPrivate().getEncoded();
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, PKCS8_HEADER, pkcs8.length);
        return "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pkcs1)
                + "\n-----END RSA PRIVATE KEY-----\n";
    }

    @Test
    void leeLaClaveComoLaEntregaGitHub() throws Exception {
        KeyPair pair = keyPair();

        PrivateKey key = GithubKeys.privateKey(pkcs1Pem(pair));

        // Lo que firma la clave leída lo comprueba la pública del par
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update("hola".getBytes(StandardCharsets.UTF_8));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update("hola".getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(signer.sign())).isTrue();
    }

    @Test
    void leeUnaClaveYaConvertidaAPkcs8() throws Exception {
        KeyPair pair = keyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        assertThat(GithubKeys.privateKey(pem).getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
    }

    @Test
    void unArchivoQueNoEsUnaClaveSeExplica() {
        assertThatThrownBy(() -> GithubKeys.privateKey("esto no es una clave"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(".pem");
    }

    @Test
    void soloAceptaElAvisoFirmadoConElSecreto() throws Exception {
        byte[] body = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secreto".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String header = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));

        assertThat(GithubKeys.validSignature("secreto", body, header)).isTrue();
        assertThat(GithubKeys.validSignature("otro", body, header)).isFalse();
        assertThat(GithubKeys.validSignature("secreto", "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8), header)).isFalse();
        assertThat(GithubKeys.validSignature("secreto", body, null)).isFalse();
        assertThat(GithubKeys.validSignature("secreto", body, "sha1=abc")).isFalse();
        assertThat(GithubKeys.validSignature("secreto", body, "sha256=no-es-hexadecimal")).isFalse();
        assertThat(GithubKeys.validSignature("", body, header)).isFalse();
    }

    @Test
    void laFirmaDelEnlaceDeInstalacionNoSirveConOtroTextoNiOtroSecreto() {
        String signature = GithubKeys.sign("secreto", "workspace|persona|123");

        assertThat(GithubKeys.signed("secreto", "workspace|persona|123", signature)).isTrue();
        assertThat(GithubKeys.signed("secreto", "otro-workspace|persona|123", signature)).isFalse();
        assertThat(GithubKeys.signed("otro", "workspace|persona|123", signature)).isFalse();
        assertThat(GithubKeys.signed("secreto", "workspace|persona|123", null)).isFalse();
    }

    @Test
    void unaFirmaDeAvisoNoValeComoFirmaDeEnlace() throws Exception {
        // Quien consiguiera que GitHub firmara un cuerpo elegido no obtiene con eso un enlace de instalación válido
        String text = "workspace|persona|123";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secreto".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String webhookStyle = HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));

        assertThat(GithubKeys.signed("secreto", text, webhookStyle)).isFalse();
    }
}
