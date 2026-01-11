package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.component.configmanager.StorageKey;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Log4j2
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket-prefix:abacox-}")
    private String bucketPrefix;

    // In-memory cache to avoid checking bucket existence on every single upload
    private final Set<String> checkedBuckets = ConcurrentHashMap.newKeySet();

    // Thread-safe flag to track status
    private final AtomicBoolean isConnected = new AtomicBoolean(false);

    /**
     * Checks if MinIO is reachable.
     * Runs at startup and then every 1 minute.
     */
    @PostConstruct
    @Scheduled(fixedDelay = 60000)
    public void checkConnectivity() {
        try {
            // fast/lightweight call to check connection
            minioClient.listBuckets();

            // If it was previously down, log recovery
            if (!isConnected.get()) {
                log.info("MinIO connection established/recovered.");
            }
            isConnected.set(true);
        } catch (Exception e) {
            // Only log the error if we were previously connected (avoids log spam)
            if (isConnected.get()) {
                log.error("MinIO connection LOST. Processing will pause. Error: {}", e.getMessage());
            }
            isConnected.set(false);
        }
    }

    /**
     * Simple getter for the worker to check
     */
    public boolean isReady() {
        return isConnected.get();
    }

    /**
     * Uploads a file stream to the tenant's bucket.
     *
     * @param tenantId    The tenant identifier (used to determine the bucket).
     * @param objectName  The unique name for the file (e.g., UUID hash).
     * @param inputStream The data stream (e.g., GZIP compressed stream).
     * @param size        The size of the stream (pass -1 if unknown, though known size is better for RAM).
     * @param contentType MIME type (e.g., "application/gzip").
     */
    public MinioUploadResult uploadFile(String tenantId, StorageKey storageKey, String objectName, InputStream inputStream, long size, String contentType) {
        String bucketName = resolveBucketName(tenantId, storageKey);
        ensureBucketExists(bucketName);

        try {
            log.debug("Uploading to MinIO. Bucket: {}, Object: {}, Size: {}", bucketName, objectName, size);

            long partSize = -1;
            if (size == -1) {
                partSize = 10485760; // 10MB default part size for unknown length
            }

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, size, partSize)
                            .contentType(contentType)
                            .build());

            // RETURN the location details
            return new MinioUploadResult(bucketName, objectName);

        } catch (Exception e) {
            log.error("Error uploading object {} to bucket {}", objectName, bucketName, e);
            throw new RuntimeException("MinIO upload failed", e);
        }
    }

    /**
     * Retrieves a file stream from the tenant's bucket.
     * Caller is responsible for closing the stream.
     */
    public InputStream downloadFile(String tenantId, StorageKey storageKey, String objectName) {
        String bucketName = resolveBucketName(tenantId, storageKey);
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
        } catch (Exception e) {
            log.error("Error downloading object {} from bucket {}", objectName, bucketName, e);
            throw new RuntimeException("MinIO download failed", e);
        }
    }

    public InputStream downloadFile(String bucketName, String objectName) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
        } catch (Exception e) {
            log.error("Error downloading object {} from bucket {}", objectName, bucketName, e);
            throw new RuntimeException("MinIO download failed", e);
        }
    }

    /**
     * Deletes a file from the tenant's bucket.
     */
    public void deleteFile(String tenantId, StorageKey storageKey, String objectName) {
        String bucketName = resolveBucketName(tenantId, storageKey);
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
            log.debug("Deleted object {} from bucket {}", objectName, bucketName);
        } catch (Exception e) {
            log.warn("Failed to delete object {} from bucket {}", objectName, bucketName, e);
            // We usually don't throw exception on delete failure to prevent rollback of main transaction
        }
    }

    /**
     * Ensures the bucket exists. Caches the result to minimize API calls.
     */
    private void ensureBucketExists(String bucketName) {
        if (checkedBuckets.contains(bucketName)) {
            return;
        }

        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                log.info("Bucket {} does not exist. Creating it...", bucketName);
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            checkedBuckets.add(bucketName);
        } catch (ErrorResponseException e) {
            // Handle race condition: If another instance created it at the exact same time
            if (e.errorResponse().code().equals("BucketAlreadyOwnedByYou")) {
                checkedBuckets.add(bucketName);
            } else {
                throw new RuntimeException("Could not check/create MinIO bucket: " + bucketName, e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not check/create MinIO bucket: " + bucketName, e);
        }
    }

    /**
     * Converts a Tenant ID into a valid S3 bucket name.
     * Rules: Lowercase, numbers, hyphens. No underscores.
     */
    public String resolveBucketName(String tenantId, StorageKey storageKey) {
        if (tenantId == null) {
            throw new IllegalArgumentException("TenantId cannot be null for storage operations");
        }
        // Sanitize: Replace underscores with hyphens, remove other special chars, toLowerCase
        String sanitizedTenant = tenantId.toLowerCase()
                .replace("_", "-")
                .replaceAll("[^a-z0-9-]", "");
        
        return bucketPrefix + sanitizedTenant+"-"+storageKey.name().toLowerCase();
    }

    public record MinioUploadResult(String bucketName, String objectName) {}
}