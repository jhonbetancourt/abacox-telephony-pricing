package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import jakarta.persistence.EntityManager;
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
}