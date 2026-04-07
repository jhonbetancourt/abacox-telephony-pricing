package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.configmanager.StorageKey;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Log4j2
public class ReportGenerationService {

    private final MinioStorageService minioStorageService;
    private final RestClient restClient;
    private final String internalApiKey;

    public ReportGenerationService(MinioStorageService minioStorageService,
                                   @Value("${server.port}") int serverPort,
                                   @Value("${abacox.internal-api-key}") String internalApiKey) {
        this.minioStorageService = minioStorageService;
        this.internalApiKey = internalApiKey;
        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:" + serverPort)
                .build();
    }

    public Map<String, Object> generateReport(String endpointPath, Map<String, String> parameters,
                                               String fileName, String tenant) {
        log.info("Generating report: endpoint={}, tenant={}", endpointPath, tenant);

        // Build the URL with query parameters
        StringBuilder urlBuilder = new StringBuilder(endpointPath);
        if (parameters != null && !parameters.isEmpty()) {
            urlBuilder.append("?");
            parameters.forEach((key, value) -> {
                if (urlBuilder.charAt(urlBuilder.length() - 1) != '?') {
                    urlBuilder.append("&");
                }
                urlBuilder.append(key).append("=").append(value);
            });
        }

        String url = urlBuilder.toString();
        log.debug("Calling internal endpoint: {}", url);

        // Stream the HTTP response to a temp file instead of holding in memory
        Path tempFile;
        try {
            tempFile = Files.createTempFile("report-", ".xlsx");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp file for report", e);
        }

        try {
            // Stream response body directly to temp file
            restClient.get()
                    .uri(url)
                    .header("X-Tenant-ID", tenant)
                    .header("X-Username", "system")
                    .header("X-Internal-Api-Key", internalApiKey)
                    .header(HttpHeaders.ACCEPT, "application/octet-stream")
                    .exchange((request, response) -> {
                        try (OutputStream out = Files.newOutputStream(tempFile);
                             InputStream in = response.getBody()) {
                            in.transferTo(out);
                        }
                        return null;
                    });

            long fileSize = Files.size(tempFile);
            if (fileSize == 0) {
                throw new RuntimeException("Report generation returned empty response");
            }

            log.info("Report generated successfully, size: {} bytes", fileSize);

            // Upload temp file to MinIO
            String objectName = UUID.randomUUID() + "/" + fileName;
            MinioStorageService.MinioUploadResult uploadResult;
            try (InputStream in = Files.newInputStream(tempFile)) {
                uploadResult = minioStorageService.uploadFile(
                        tenant,
                        StorageKey.REPORTS,
                        objectName,
                        in,
                        fileSize,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                );
            }

            log.info("Report uploaded to MinIO: bucket={}, object={}", uploadResult.bucketName(), uploadResult.objectName());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bucketName", uploadResult.bucketName());
            result.put("objectName", uploadResult.objectName());
            result.put("fileSize", fileSize);
            result.put("fileName", fileName);
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to process report file", e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", tempFile, e);
            }
        }
    }
}
