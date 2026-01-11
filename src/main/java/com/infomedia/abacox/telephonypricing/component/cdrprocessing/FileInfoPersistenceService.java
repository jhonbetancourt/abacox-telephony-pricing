package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.component.configmanager.StorageKey;
import com.infomedia.abacox.telephonypricing.component.utils.XXHash128Util;
import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import com.infomedia.abacox.telephonypricing.service.MinioStorageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

// CHANGED: Standard Java ZIP imports
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import java.io.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class FileInfoPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;

    // Inject the new MinIO service
    private final MinioStorageService minioStorageService;

    @Getter
    @AllArgsConstructor
    public static class FileInfoCreationResult {
        private final FileInfo fileInfo;
        private final boolean isNew;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileInfoCreationResult createOrGetFileInfo(String filename, Long parentId, String type, File file) throws IOException {

        // 1. Calculate Hash
        UUID checksum;
        try (InputStream fileInputStream = new FileInputStream(file)) {
            checksum = XXHash128Util.hash(fileInputStream);
        }

        // 2. Check DB
        FileInfo fileInfo = findByChecksumInternal(checksum);
        boolean isNew = false;

        if (fileInfo == null) {
            isNew = true;
            String tenantId = TenantContext.getTenant();
            String objectKey = checksum.toString();

            // 3. Compress to Temp
            File tempCompressedFile = File.createTempFile("minio_up_", ".gz");
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = new FileOutputStream(tempCompressedFile)) {
                compressStream(in, out);
            }

            // 4. Upload & CAPTURE RESULT
            MinioStorageService.MinioUploadResult uploadResult;
            try (InputStream uploadStream = new FileInputStream(tempCompressedFile)) {
                uploadResult = minioStorageService.uploadFile(
                        tenantId,
                        StorageKey.CDR,
                        objectKey,
                        uploadStream,
                        tempCompressedFile.length(),
                        "application/gzip"
                );
            } catch (Exception e) {
                tempCompressedFile.delete();
                throw new IOException("Failed to upload to MinIO", e);
            }

            // 5. Save DB Record using returned metadata
            fileInfo = new FileInfo();
            fileInfo.setFilename(filename.length() > 255 ? filename.substring(0, 255) : filename);
            fileInfo.setParentId(parentId != null ? parentId.intValue() : 0);
            fileInfo.setType(type.length() > 64 ? type.substring(0, 64) : type);
            fileInfo.setDate(LocalDateTime.now());
            fileInfo.setChecksum(checksum);
            fileInfo.setSize((int) file.length());
            fileInfo.setProcessingStatus(FileInfo.ProcessingStatus.PENDING);

            // SET STORAGE DATA FROM RESULT
            fileInfo.setStorageBucket(uploadResult.bucketName());
            fileInfo.setStorageObjectName(uploadResult.objectName());

            entityManager.persist(fileInfo);
            entityManager.flush();

            tempCompressedFile.delete();
        }

        return new FileInfoCreationResult(fileInfo, isNew);
    }


    /**
     * Compresses data from an InputStream to an OutputStream using GZIP with maximum compression.
     * The GZIPOutputStream is closed automatically, which writes the trailer and finishes compression.
     */
    public static void compressStream(InputStream inputStream, OutputStream outputStream) throws IOException {
        // CHANGED: Use GZIPOutputStream with anonymous subclass to set Best Compression (Level 9)
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream) {
            {
                def.setLevel(Deflater.BEST_COMPRESSION);
            }
        }) {
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                gzipOutputStream.write(buffer, 0, bytesRead);
            }
            // gzipOutputStream.close() is called automatically here via try-with-resources
        }
    }

    private FileInfo findByChecksumInternal(UUID checksum) { // Changed Long to UUID
        try {
            return entityManager.createQuery("SELECT fi FROM FileInfo fi WHERE fi.checksum = :checksum", FileInfo.class)
                    .setParameter("checksum", checksum)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public FileInfo findById(Long fileInfoId) {
        if (fileInfoId == null) return null;
        return entityManager.find(FileInfo.class, fileInfoId.intValue());
    }

    /**
     * Optimized batch fetch: Locks and returns up to 'limit' pending files at once.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<FileInfo> findAndLockPendingFiles(int limit) {
        try {
            List<FileInfo> files = entityManager.createQuery(
                            "SELECT fi FROM FileInfo fi WHERE fi.processingStatus = :status ORDER BY fi.date ASC", FileInfo.class)
                    .setParameter("status", FileInfo.ProcessingStatus.PENDING)
                    .setMaxResults(limit)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setHint("jakarta.persistence.lock.timeout", 0) // SKIP LOCKED behavior
                    .getResultList();

            for (FileInfo fi : files) {
                fi.setProcessingStatus(FileInfo.ProcessingStatus.IN_PROGRESS);
                entityManager.merge(fi);
            }

            return files;
        } catch (Exception e) {
            log.error("Error locking pending files", e);
            return Collections.emptyList();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resetInProgressToPending() {
        int updatedCount = entityManager.createQuery(
                        "UPDATE FileInfo fi SET fi.processingStatus = :pendingStatus WHERE fi.processingStatus = :inProgressStatus")
                .setParameter("pendingStatus", FileInfo.ProcessingStatus.PENDING)
                .setParameter("inProgressStatus", FileInfo.ProcessingStatus.IN_PROGRESS)
                .executeUpdate();
        if (updatedCount > 0) {
            log.info("Reset {} files from IN_PROGRESS to PENDING status on startup.", updatedCount);
        }
        return updatedCount;
    }

    @Transactional(readOnly = true)
    public Optional<FileInfoData> getOriginalFileData(Long fileInfoId) {
        FileInfo fileInfo = findById(fileInfoId);
        if (fileInfo == null || fileInfo.getStorageObjectName() == null) {
            return Optional.empty();
        }

        try {
            // Use stored bucket name directly
            InputStream compressedStream = minioStorageService.downloadFile(
                    fileInfo.getStorageBucket(),
                    fileInfo.getStorageObjectName()
            );

            InputStream decompressedStream = new GZIPInputStream(compressedStream);

            return Optional.of(new FileInfoData(
                    fileInfo.getFilename(),
                    decompressedStream,
                    fileInfo.getSize()
            ));

        } catch (Exception e) {
            log.error("Failed to retrieve file from MinIO for ID: {}", fileInfoId, e);
            return Optional.empty();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(Long fileInfoId, FileInfo.ProcessingStatus status) {
        FileInfo fileInfo = findById(fileInfoId);
        if (fileInfo != null) {
            fileInfo.setProcessingStatus(status);
            entityManager.merge(fileInfo);
        }
    }

    @Transactional(readOnly = true)
    public FileInfoMetadata getFileMetadata(Long fileInfoId) {
        FileInfo fileInfo = findById(fileInfoId);
        if (fileInfo == null) {
            return null;
        }
        return new FileInfoMetadata(fileInfo.getFilename(), fileInfo.getSize());
    }

    /**
     * Streams the content directly to the provided OutputStream within a Transaction.
     * This keeps the PostgreSQL Large Object descriptor open while reading.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public void streamFileContent(Long fileInfoId, OutputStream outputStream) {
        FileInfo fileInfo = findById(fileInfoId);
        if (fileInfo == null) {
            throw new RuntimeException("File info not found for ID: " + fileInfoId);
        }

        try {
            String tenantId = TenantContext.getTenant();

            // Get from MinIO
            InputStream minioStream = minioStorageService.downloadFile(tenantId, StorageKey.CDR, fileInfo.getStorageObjectName());

            // Decompress and copy to HTTP output
            try (InputStream gzipStream = new GZIPInputStream(minioStream)) {
                StreamUtils.copy(gzipStream, outputStream);
                outputStream.flush();
            }
            // minioStream closed by try-with-resources of gzipStream (usually)
            // or explicitly close minioStream if GZIPInputStream doesn't close inner stream
            // (it does in standard Java).

        } catch (Exception e) {
            log.error("Error streaming content for file ID: {}", fileInfoId, e);
            throw new RuntimeException("Failed to stream file content", e);
        }
    }

    // Helper record for metadata
    public record FileInfoMetadata(String filename, long size) {
    }
}