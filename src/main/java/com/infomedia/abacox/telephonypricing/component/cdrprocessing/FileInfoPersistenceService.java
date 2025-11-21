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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.zip.DataFormatException;

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
     * Creates a new FileInfo record with compressed content, or retrieves an existing one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileInfoCreationResult createOrGetFileInfo(String filename, Long parentId, String type, byte[] uncompressedContent) {
        String checksum = CdrUtil.sha256(uncompressedContent);
        log.debug("Attempting to create/get FileInfo. Filename: {}, Checksum: {}", filename, checksum);

        FileInfo fileInfo = findByChecksumInternal(checksum);
        boolean isNew = false;

        if (fileInfo == null) {
            isNew = true;
            fileInfo = new FileInfo();
            fileInfo.setFilename(filename.length() > 255 ? filename.substring(0, 255) : filename);
            fileInfo.setParentId(parentId != null ? parentId.intValue() : 0);
            fileInfo.setType(type.length() > 64 ? type.substring(0, 64) : type);
            fileInfo.setDate(LocalDateTime.now());
            fileInfo.setChecksum(checksum);
            fileInfo.setSize(uncompressedContent.length);
            fileInfo.setProcessingStatus(FileInfo.ProcessingStatus.PENDING);

            try {
                fileInfo.setFileContent(CdrUtil.compress(uncompressedContent));
            } catch (IOException e) {
                log.error("Failed to compress file content for {}.", filename, e);
                fileInfo.setFileContent(null);
            }

            entityManager.persist(fileInfo);
            entityManager.flush();
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

    /**
     * Deprecated single fetch wrapper, kept for backward compatibility.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<FileInfo> findAndLockPendingFile() {
        List<FileInfo> files = findAndLockPendingFiles(1);
        return files.isEmpty() ? Optional.empty() : Optional.of(files.get(0));
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

    /**
     * Retrieves and decompresses the original file content.
     */
    @Transactional(readOnly = true)
    public Optional<FileInfoData> getOriginalFileData(Long fileInfoId) {
        FileInfo fileInfo = findById(fileInfoId);
        if (fileInfo == null || fileInfo.getFileContent() == null || fileInfo.getFileContent().length == 0) {
            log.warn("Could not find FileInfo or its content for ID: {}", fileInfoId);
            return Optional.empty();
        }

        try {
            byte[] decompressedContent = CdrUtil.decompress(fileInfo.getFileContent());
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
            entityManager.merge(fileInfo);
        }
    }
}