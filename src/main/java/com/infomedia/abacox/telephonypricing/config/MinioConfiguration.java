package com.infomedia.abacox.telephonypricing.config;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class MinioConfiguration {

    @Value("${minio.url}")
    private String url;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS) // Handshake time
                .writeTimeout(10, TimeUnit.MINUTES)   // Time allowed to write data (upload)
                .readTimeout(10, TimeUnit.MINUTES)    // Time allowed to read response
                .build();

        return MinioClient.builder()
                .endpoint(url)
                .credentials(accessKey, secretKey)
                .httpClient(httpClient)
                .build();
    }
}