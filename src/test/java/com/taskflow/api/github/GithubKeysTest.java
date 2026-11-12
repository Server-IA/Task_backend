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
    void laFirmaDeUnPropositoNoSirveParaOtro() {
        String install = GithubKeys.sign("secreto", "install", "datos");

        assertThat(GithubKeys.signed("secreto", "install", "datos", install)).isTrue();
        assertThat(GithubKeys.signed("secreto", "link", "datos", install)).isFalse();
        // La firma sin propósito es la de instalar
        assertThat(GithubKeys.sign("secreto", "datos")).isEqualTo(install);
    }

    @Test
    void cifraLosTokensYSoloSeLeenConElMismoSecreto() {
        String sealed = GithubKeys.seal("secreto-de-cliente", "ghu_token-de-una-persona");

        assertThat(sealed).doesNotContain("ghu_");
        assertThat(GithubKeys.open("secreto-de-cliente", sealed)).isEqualTo("ghu_token-de-una-persona");
        // Cada vez sale distinto (vector aleatorio), así dos tokens iguales no se reconocen en la base
        assertThat(GithubKeys.seal("secreto-de-cliente", "ghu_token-de-una-persona")).isNotEqualTo(sealed);
        assertThatThrownBy(() -> GithubKeys.open("otro-secreto", sealed)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GithubKeys.open("secreto-de-cliente", "no es base64 !")).isInstanceOf(IllegalStateException.class);
        // Un cifrado retocado no se abre
        // (un carácter del medio: los últimos de un Base64 pueden llevar bits de relleno que no cambian nada)
        char middle = sealed.charAt(20);
        String tampered = sealed.substring(0, 20) + (middle == 'A' ? 'B' : 'A') + sealed.substring(21);
        assertThatThrownBy(() -> GithubKeys.open("secreto-de-cliente", tampered)).isInstanceOf(IllegalStateException.class);
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
