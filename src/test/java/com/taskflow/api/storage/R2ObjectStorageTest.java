package com.taskflow.api.storage;

import com.taskflow.api.storage.ObjectStorage.PresignedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El cliente de R2: las URLs firmadas se calculan sin red (con credenciales de prueba) y las llamadas a R2 van contra un
 * {@link S3Client} simulado.
 */
class R2ObjectStorageTest {

    private static final URI ENDPOINT = URI.create("https://cuenta-de-prueba.r2.cloudflarestorage.com");

    private S3Client client;
    private S3Presigner presigner;
    private R2ObjectStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        presigner = S3Presigner.builder()
                .endpointOverride(ENDPOINT)
                .region(StorageConfig.R2_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIDPRUEBA", "secreto-de-prueba")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        storage = new R2ObjectStorage(client, presigner, "taskflow-files");
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    private static String query(String url) {
        return URLDecoder.decode(URI.create(url).getRawQuery(), StandardCharsets.UTF_8);
    }

    @Test
    void laSubidaFirmaElTipoYElTamano() {
        PresignedRequest put = storage.presignUpload("projects/p/abc.png", "image/png", 123_456,
                Duration.ofMinutes(15));

        URI url = URI.create(put.url());
        assertThat(url.getHost()).isEqualTo("cuenta-de-prueba.r2.cloudflarestorage.com");
        assertThat(url.getPath()).isEqualTo("/taskflow-files/projects/p/abc.png");
        String query = query(put.url());
        assertThat(query).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("/auto/s3/aws4_request")
                .contains("X-Amz-Expires=900");
        // R2 compara las cabeceras firmadas: otro tipo u otro tamaño dan 403
        assertThat(query).containsPattern("X-Amz-SignedHeaders=[^&]*content-length")
                .containsPattern("X-Amz-SignedHeaders=[^&]*content-type");
        // El navegador manda el tipo; el tamaño y el host los pone él solo
        assertThat(put.headers()).containsExactly(java.util.Map.entry("Content-Type", "image/png"));
        assertThat(put.expiresAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(15)), within(5,
                java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void laDescargaLlevaElNombreElTipoYLaCache() {
        PresignedRequest inline = storage.presignDownload("projects/p/a.png", "image/png", "mockup perfil.png", true,
                Duration.ofHours(1));
        String query = query(inline.url());
        assertThat(query).contains("response-content-type=image/png")
                .contains("response-content-disposition=inline; filename=\"mockup perfil.png\"; "
                        + "filename*=UTF-8''mockup%20perfil.png")
                .contains("response-cache-control=private, max-age=3600")
                .contains("X-Amz-Expires=3600");
        assertThat(inline.headers()).isEmpty();

        PresignedRequest attachment = storage.presignDownload("projects/p/b.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "año «final».docx", false,
                Duration.ofHours(1));
        assertThat(query(attachment.url())).contains("response-content-disposition=attachment; "
                + "filename=\"a_o _final_.docx\"; filename*=UTF-8''a%C3%B1o%20%C2%ABfinal%C2%BB.docx");
    }

    @Test
    void elNombreNoRompeLaCabecera() {
        assertThat(R2ObjectStorage.contentDisposition(false, "a\"b;c\\d.txt"))
                .startsWith("attachment; filename=\"a_b_c_d.txt\"; filename*=UTF-8''a%22b%3Bc%5Cd.txt");
        assertThat(R2ObjectStorage.contentDisposition(true, " ")).startsWith("inline; filename=\"archivo\"");
    }

    @Test
    void headDevuelveTamanoYTipoOVacioSiNoExiste() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(42L).contentType("image/png").build());
        assertThat(storage.head("k")).hasValueSatisfying(info -> {
            assertThat(info.size()).isEqualTo(42);
            assertThat(info.contentType()).isEqualTo("image/png");
        });

        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThat(storage.head("k")).isEmpty();
    }

    @Test
    void headCon404EsVacioYConOtroErrorEs503() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build())
                .thenThrow(S3Exception.builder().statusCode(500).build())
                .thenThrow(SdkClientException.create("sin red"));

        assertThat(storage.head("k")).isEmpty();
        assertThatThrownBy(() -> storage.head("k")).isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> storage.head("k")).isInstanceOf(StorageUnavailableException.class)
                .hasMessage(R2ObjectStorage.UNREACHABLE);
    }

    @Test
    void leeSoloElPrincipioDelArchivo() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), "%PDF-1.7 y mucho más".getBytes(StandardCharsets.US_ASCII)));

        byte[] head = storage.readPrefix("k", 8);

        assertThat(new String(head, StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.7");
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObjectAsBytes(request.capture());
        assertThat(request.getValue().range()).isEqualTo("bytes=0-7");
        assertThat(request.getValue().bucket()).isEqualTo("taskflow-files");
    }

    @Test
    void borrarNoFallaSiNoExisteYSiR2FallaEs503() {
        storage.delete("k");
        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo("k");

        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        storage.delete("k");

        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkClientException.create("sin red"));
        assertThatThrownBy(() -> storage.delete("k")).isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void sinCredencialesNoHayAlmacenamiento() {
        StorageProperties empty = new StorageProperties("", "", "", "taskflow-files", "", 0, null, null);
        assertThat(empty.configured()).isFalse();
        assertThat(empty.workspaceQuotaBytes()).isEqualTo(StorageProperties.DEFAULT_QUOTA);
        assertThat(new StorageConfig().objectStorage(empty).enabled()).isFalse();
        assertThatThrownBy(() -> new DisabledObjectStorage().presignUpload("k", "image/png", 1, Duration.ZERO))
                .isInstanceOf(StorageUnavailableException.class);

        StorageProperties r2 = new StorageProperties("cuenta", "id", "secreto", "taskflow-files", "", 0, null, null);
        assertThat(r2.configured()).isTrue();
        assertThat(r2.endpointUri()).isEqualTo(URI.create("https://cuenta.r2.cloudflarestorage.com"));
        assertThat(r2.downloadUrlTtl()).isEqualTo(Duration.ofHours(1));
    }
}
