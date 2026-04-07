package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.configmanager.StorageKey;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
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

        // Make the internal HTTP call to the export endpoint
        byte[] reportBytes = restClient.get()
                .uri(url)
                .header("X-Tenant-ID", tenant)
                .header("X-Username", "system")
                .header("X-Internal-Api-Key", internalApiKey)
                .header(HttpHeaders.ACCEPT, "application/octet-stream")
                .retrieve()
                .body(byte[].class);

        if (reportBytes == null || reportBytes.length == 0) {
            throw new RuntimeException("Report generation returned empty response");
        }

        log.info("Report generated successfully, size: {} bytes", reportBytes.length);

        // Upload to MinIO
        String objectName = UUID.randomUUID() + "/" + fileName;
        MinioStorageService.MinioUploadResult uploadResult = minioStorageService.uploadFile(
                tenant,
                StorageKey.REPORTS,
                objectName,
                new ByteArrayInputStream(reportBytes),
                reportBytes.length,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );

        log.info("Report uploaded to MinIO: bucket={}, object={}", uploadResult.bucketName(), uploadResult.objectName());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bucketName", uploadResult.bucketName());
        result.put("objectName", uploadResult.objectName());
        result.put("fileSize", reportBytes.length);
        result.put("fileName", fileName);
        return result;
    }
}
