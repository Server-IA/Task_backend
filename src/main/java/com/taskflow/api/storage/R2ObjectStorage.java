package com.taskflow.api.storage;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ObjectStorage} sobre Cloudflare R2 con el SDK de AWS (S3Client para HEAD, GET parcial y DELETE; S3Presigner
 * para las URLs firmadas, que se calculan sin llamar a R2).
 */
@Slf4j
public class R2ObjectStorage implements ObjectStorage, AutoCloseable {

    /** Cabeceras firmadas que no manda el código del navegador: las pone el propio navegador. */
    private static final Set<String> BROWSER_HEADERS = Set.of("host", "content-length");
    static final String UNREACHABLE = "No se pudo conectar con el almacenamiento de archivos. Inténtalo de nuevo";

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public R2ObjectStorage(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public PresignedRequest presignUpload(String key, String contentType, long size, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(request -> request
                .signatureDuration(ttl)
                .putObjectRequest(put));
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!BROWSER_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(canonicalName(name), String.join(",", values));
            }
        });
        return new PresignedRequest(presigned.url().toString(), headers, presigned.expiration());
    }

    @Override
    public PresignedRequest presignDownload(String key, String contentType, String fileName, boolean inline,
                                            Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentType(contentType)
                .responseContentDisposition(contentDisposition(inline, fileName))
                // Mientras la URL vale, el navegador reutiliza la imagen (cada archivo es inmutable: id nuevo por subida)
                .responseCacheControl("private, max-age=" + ttl.toSeconds())
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(request -> request
                .signatureDuration(ttl)
                .getObjectRequest(get));
        return new PresignedRequest(presigned.url().toString(), Map.of(), presigned.expiration());
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            HeadObjectResponse head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            long size = head.contentLength() == null ? -1 : head.contentLength();
            return Optional.of(new ObjectInfo(size, head.contentType()));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw unreachable("HEAD", key, ex);
        } catch (SdkException ex) {
            throw unreachable("HEAD", key, ex);
        }
    }

    @Override
    public byte[] readPrefix(String key, int bytes) {
        try {
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .range("bytes=0-" + (bytes - 1))
                    .build());
            byte[] content = response.asByteArray();
            return content.length > bytes ? Arrays.copyOf(content, bytes) : content;
        } catch (SdkException ex) {
            throw unreachable("GET", key, ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException ex) {
            // Ya no estaba
        } catch (SdkException ex) {
            throw unreachable("DELETE", key, ex);
        }
    }

    @Override
    public void close() {
        client.close();
        presigner.close();
    }

    private static StorageUnavailableException unreachable(String operation, String key, Exception ex) {
        log.warn("R2 {} {} falló: {}", operation, key, ex.getMessage());
        return new StorageUnavailableException(UNREACHABLE);
    }

    /**
     * {@code inline} o {@code attachment} con el nombre original: {@code filename} en ASCII (para navegadores viejos) y
     * {@code filename*} en UTF-8 (RFC 6266).
     */
    static String contentDisposition(boolean inline, String fileName) {
        String name = fileName == null || fileName.isBlank() ? "archivo" : fileName;
        StringBuilder ascii = new StringBuilder();
        for (char c : name.toCharArray()) {
            ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' && c != ';' ? c : '_');
        }
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return (inline ? "inline" : "attachment") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /** {@code content-type} → {@code Content-Type}. */
    private static String canonicalName(String header) {
        List<String> parts = Arrays.stream(header.split("-"))
                .map(part -> part.isEmpty() ? part
                        : Character.toUpperCase(part.charAt(0)) + part.substring(1).toLowerCase(Locale.ROOT))
                .toList();
        return String.join("-", parts);
    }
}
