package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.hibernate.engine.jdbc.BlobProxy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.tukaani.xz.LZMAInputStream;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.SQLException;
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
    public FileInfoCreationResult createOrGetFileInfo(String filename, Long parentId, String type, File file) throws IOException {
        log.debug("Attempting to create/get FileInfo. Filename: {}, File size: {}", filename, file.length());

        // Calculate checksum using streaming approach
        String checksum;
        try (InputStream fileInputStream = new FileInputStream(file)) {
            checksum = CdrUtil.sha256Stream(fileInputStream);
        }

        log.debug("Calculated checksum: {}", checksum);

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
            fileInfo.setSize((int) file.length());
            fileInfo.setProcessingStatus(FileInfo.ProcessingStatus.PENDING);

            File tempCompressedFile = null;
            try {
                tempCompressedFile = createCompressedBlobFromFile(file, fileInfo);

                entityManager.persist(fileInfo);
                entityManager.flush();

                // Schedule cleanup after transaction commits
                final File fileToDelete = tempCompressedFile;
                scheduleFileCleanup(fileToDelete);

            } catch (IOException e) {
                log.error("Failed to compress file content for {}.", filename, e);
                // If transaction fails, clean up immediately
                if (tempCompressedFile != null && tempCompressedFile.exists()) {
                    if (tempCompressedFile.delete()) {
                        log.debug("Cleaned up temp compressed file after error: {}", tempCompressedFile.getAbsolutePath());
                    }
                }
                fileInfo.setFileContent(null);
                throw e;
            }
        }
        return new FileInfoCreationResult(fileInfo, isNew);
    }

    /**
     * Creates a compressed blob from a file and returns the temporary compressed file.
     * The temp file should be deleted after the transaction commits.
     */
    private File createCompressedBlobFromFile(File sourceFile, FileInfo fileInfo) throws IOException {
        File tempCompressedFile = File.createTempFile("compressed_", ".lzma");
        tempCompressedFile.deleteOnExit(); // Backup cleanup in case of JVM crash

        try (InputStream fileInputStream = new FileInputStream(sourceFile);
             FileOutputStream tempOutputStream = new FileOutputStream(tempCompressedFile)) {

            CdrUtil.compressStream(fileInputStream, tempOutputStream);
        }

        // Log compression statistics
        double compressionRatio = sourceFile.length() > 0
                ? (100.0 * tempCompressedFile.length() / sourceFile.length())
                : 0;
        log.debug("LZMA compression: {} bytes -> {} bytes ({}% of original size)",
                sourceFile.length(), tempCompressedFile.length(), String.format("%.2f", compressionRatio));

        // Create Blob from the compressed file stream
        InputStream compressedInputStream = new FileInputStream(tempCompressedFile);
        Blob blob = BlobProxy.generateProxy(compressedInputStream, tempCompressedFile.length());
        fileInfo.setFileContent(blob);

        return tempCompressedFile;
    }

    /**
     * Schedules a file for deletion after the current transaction commits.
     * If the transaction rolls back, the file is deleted immediately.
     */
    private void scheduleFileCleanup(File file) {
        if (file == null) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (file.exists()) {
                            if (file.delete()) {
                                log.debug("Deleted temp compressed file after transaction {}: {}",
                                        status == STATUS_COMMITTED ? "commit" : "rollback",
                                        file.getAbsolutePath());
                            } else {
                                log.warn("Failed to delete temp compressed file: {}", file.getAbsolutePath());
                            }
                        }
                    }
                }
        );
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
     * Retrieves the file content as a decompressed InputStream.
     * The caller is responsible for closing the stream.
     *
     * @param fileInfoId The ID of the FileInfo record
     * @return Optional containing FileInfoData with filename and decompressed content stream, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<FileInfoData> getOriginalFileData(Long fileInfoId) {
        FileInfo fileInfo = findById(fileInfoId);
        if (fileInfo == null || fileInfo.getFileContent() == null) {
            log.warn("Could not find FileInfo or its content for ID: {}", fileInfoId);
            return Optional.empty();
        }

        try {
            // Get the compressed data stream from the Blob
            InputStream compressedStream = fileInfo.getFileContent().getBinaryStream();

            // Wrap it in an LZMA decompression stream
            InputStream decompressedStream = new LZMAInputStream(compressedStream);

            // Create FileInfoData with the stream and the original uncompressed size
            FileInfoData fileInfoData = new FileInfoData(
                    fileInfo.getFilename(),
                    decompressedStream,
                    fileInfo.getSize()
            );

            return Optional.of(fileInfoData);

        } catch (SQLException | IOException e) {
            log.error("Failed to create decompression stream for FileInfo ID: {}", fileInfoId, e);
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