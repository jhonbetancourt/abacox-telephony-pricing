package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

@Service
@Log4j2
public class FileInfoPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;

    @Getter
    @AllArgsConstructor
    public static class FileInfoCreationResult {
        private final FileInfo fileInfo;
        private final boolean isNew;
    }

    /**
     * Creates a new FileInfo record with compressed content, or retrieves an existing one based on a checksum of the content.
     * This method is idempotent: processing the same file content will return the same FileInfo record.
     * It runs in its own transaction to ensure the ID is available to subsequent operations.
     *
     * @param filename The name of the file.
     * @param parentId The ID of the parent entity (e.g., plantTypeId).
     * @param type A descriptor for the file type (e.g., "ROUTED_STREAM").
     * @param uncompressedContent The raw byte content of the file.
     * @return A result object containing the persisted or existing FileInfo entity and a flag indicating if it was new.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW) // Ensures this commits independently
    public FileInfoCreationResult createOrGetFileInfo(String filename, Long parentId, String type, byte[] uncompressedContent) {
        String checksum = CdrUtil.sha256(uncompressedContent);
        log.debug("Attempting to create/get FileInfo. Filename: {}, ParentId: {}, Type: {}, Checksum: {}", filename, parentId, type, checksum);

        FileInfo fileInfo = findByChecksumInternal(checksum);
        boolean isNew = false;

        if (fileInfo == null) {
            isNew = true;
            log.debug("No existing FileInfo found for checksum. Creating a new record for file: {}", filename);
            fileInfo = new FileInfo();
            fileInfo.setFilename(filename.length() > 255 ? filename.substring(0, 255) : filename);
            fileInfo.setParentId(parentId != null ? parentId.intValue() : 0);
            fileInfo.setType(type.length() > 64 ? type.substring(0, 64) : type);
            fileInfo.setDate(LocalDateTime.now()); // Processing time
            fileInfo.setChecksum(checksum);
            fileInfo.setSize(uncompressedContent.length);
            fileInfo.setReferenceId(0);
            fileInfo.setDirectory("");

            try {
                fileInfo.setFileContent(CdrUtil.compress(uncompressedContent));
                log.debug("Successfully compressed content for file: {}. Original size: {}, Compressed size: {}",
                        filename, uncompressedContent.length, fileInfo.getFileContent().length);
            } catch (IOException e) {
                log.debug("Failed to compress file content for {}. Archiving will be skipped.", filename, e);
                fileInfo.setFileContent(null);
            }

            entityManager.persist(fileInfo);
            entityManager.flush();
            log.debug("Created and flushed new FileInfo record with ID: {} for file: {}", fileInfo.getId(), filename);
        } else {
            log.debug("Found existing FileInfo record with ID: {} for checksum: {}", fileInfo.getId(), checksum);
        }
        return new FileInfoCreationResult(fileInfo, isNew);
    }

    private FileInfo findByChecksumInternal(String checksum) {
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<FileInfo> findAndLockPendingFile() {
        // This query finds the oldest PENDING file and applies a pessimistic write lock.
        // "SKIP LOCKED" ensures that if another transaction has this row locked, we just skip it
        // and try to find another one. This is crucial for multi-instance deployments.
        // NOTE: The exact syntax for "SKIP LOCKED" might vary by database (this is for PostgreSQL/Oracle).
        // For MySQL >= 8.0, it's the same. For SQL Server, it's more complex (e.g., WITH (UPDLOCK, READPAST)).
        try {
            FileInfo fileInfo = entityManager.createQuery(
                            "SELECT fi FROM FileInfo fi WHERE fi.processingStatus = :status ORDER BY fi.date ASC", FileInfo.class)
                    .setParameter("status", FileInfo.ProcessingStatus.PENDING)
                    .setMaxResults(1)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setHint("jakarta.persistence.lock.timeout", 0) // Do not wait for the lock
                    .getSingleResult();

            fileInfo.setProcessingStatus(FileInfo.ProcessingStatus.IN_PROGRESS);
            entityManager.merge(fileInfo);
            log.debug("Locked and marked FileInfo ID {} as IN_PROGRESS.", fileInfo.getId());
            return Optional.of(fileInfo);
        } catch (NoResultException e) {
            return Optional.empty(); // No pending files found
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
        if (fileInfo == null || fileInfo.getFileContent() == null || fileInfo.getFileContent().length == 0) {
            log.warn("Could not find FileInfo or its content for ID: {}", fileInfoId);
            return Optional.empty();
        }

        try {
            byte[] decompressedContent = CdrUtil.decompress(fileInfo.getFileContent());
            log.debug("Successfully decompressed content for FileInfo ID: {}", fileInfoId);
            return Optional.of(new FileInfoData(fileInfo.getFilename(), decompressedContent));
        } catch (IOException | DataFormatException e) {
            log.error("Failed to decompress content for FileInfo ID: {}", fileInfoId, e);
            return Optional.empty();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(Long fileInfoId, FileInfo.ProcessingStatus status) {
        FileInfo fileInfo = findById(fileInfoId);
        if (fileInfo != null) {
            fileInfo.setProcessingStatus(status);
            // You could add a 'processing_details' column to store the details
            // For now, we just log it.
            log.debug("Updating status for FileInfo ID {} to {}", fileInfoId, status);
            entityManager.merge(fileInfo);
        } else {
            log.warn("Attempted to update status for a non-existent FileInfo ID: {}", fileInfoId);
        }
    }
}