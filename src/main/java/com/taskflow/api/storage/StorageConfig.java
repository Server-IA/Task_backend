package com.taskflow.api.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

/**
 * Cliente de R2. R2 usa la región {@code auto} y rutas con el bucket ({@code /<bucket>/<clave>}). Sin credenciales,
 * {@link DisabledObjectStorage}: la API arranca igual (desarrollo, pruebas).
 */
@Slf4j
@Configuration
public class StorageConfig {

    static final Region R2_REGION = Region.of("auto");

    @Bean
    public ObjectStorage objectStorage(StorageProperties properties) {
        if (!properties.configured()) {
            log.warn("R2 sin configurar (R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET): "
                    + "la subida de archivos está desactivada");
            return new DisabledObjectStorage();
        }
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKeyId().trim(), properties.secretAccessKey().trim()));
        S3Configuration s3 = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();
        S3Client client = S3Client.builder()
                .endpointOverride(properties.endpointUri())
                .region(R2_REGION)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3)
                // R2 no necesita los checksums que el SDK agrega por defecto desde 2.30
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(20))
                        .build())
                .build();
        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(properties.endpointUri())
                .region(R2_REGION)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3)
                .build();
        return new R2ObjectStorage(client, presigner, properties.bucket().trim());
    }
}
